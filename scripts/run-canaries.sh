#!/usr/bin/env bash
# Synthetic canary: exercises the full golden path against a deployed stage.
# Usage: ./scripts/run-canaries.sh <stage>
# Exit 0 = healthy; exit 1 = at least one check failed (triggers pipeline rollback).
# AWS credentials must be configured for the target account.
set -euo pipefail

STAGE="${1:?Usage: run-canaries.sh <stage>}"
REGION="${AWS_REGION:-us-east-1}"
STACK_NAME="ArtifactMgmt-Api-${STAGE}"
# Per-operation latency budget in milliseconds. S3 PUT has its own 15s timeout.
LATENCY_BUDGET_MS=5000

echo "[canary] stage=${STAGE}  resolving API URL from ${STACK_NAME} ..."
API_URL=$(aws cloudformation describe-stacks \
  --stack-name "${STACK_NAME}" \
  --region "${REGION}" \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" \
  --output text)

if [[ -z "${API_URL}" ]]; then
  echo "[canary] ERROR: could not resolve ApiUrl from stack ${STACK_NAME}" >&2
  exit 1
fi
echo "[canary] API_URL=${API_URL}"

pip install --quiet requests requests-aws4auth boto3 2>/dev/null

# Pass values as env vars so the heredoc uses 'PYEOF' (no bash interpolation).
STAGE="${STAGE}" \
API_URL="${API_URL}" \
REGION="${REGION}" \
LATENCY_BUDGET_MS="${LATENCY_BUDGET_MS}" \
python3 <<'PYEOF'
import os, sys, time, uuid
import requests
from requests_aws4auth import AWS4Auth
import boto3

stage   = os.environ["STAGE"]
api_url = os.environ["API_URL"].rstrip("/")
region  = os.environ["REGION"]
budget  = int(os.environ["LATENCY_BUDGET_MS"])

session = boto3.Session()
creds   = session.get_credentials().get_frozen_credentials()
auth    = AWS4Auth(
    creds.access_key, creds.secret_key, region, "execute-api",
    session_token=creds.token,
)

canary_name = f"canary-{uuid.uuid4().hex[:10]}"
failures: list[str] = []

# State tracked across steps for cleanup and dependent calls
version_key  = None
upload_url   = None
model_ok     = False
version_ok   = False

def timed(method: str, url: str, **kwargs):
    t0 = time.monotonic()
    r  = requests.request(method, url, auth=auth, timeout=10, **kwargs)
    return r, (time.monotonic() - t0) * 1000

def check(label: str, resp, expected: int, elapsed_ms: float) -> bool:
    if resp.status_code != expected:
        failures.append(
            f"{label}: expected HTTP {expected}, got {resp.status_code} — {resp.text[:200]}"
        )
        return False
    if elapsed_ms > budget:
        failures.append(f"{label}: {elapsed_ms:.0f}ms exceeds {budget}ms budget")
        return False
    print(f"  ✓  {label:<42} {resp.status_code}  {elapsed_ms:.0f}ms")
    return True

print(f"\n[canary] model={canary_name}")
print("─" * 64)

try:
    # ── 1. CreateModel ────────────────────────────────────────────────────────
    r, ms = timed("POST", f"{api_url}/models", json={
        "modelName":        canary_name,
        "description":      "synthetic canary — safe to delete",
        "framework":        "canary",
        "frameworkVersion": "0.0",
        "trainingMetadata": {},
        "depSnapshot":      {},
    })
    if check("CreateModel", r, 201, ms):
        model_ok = True

    # ── 2. CreateVersion ──────────────────────────────────────────────────────
    if model_ok:
        r, ms = timed("POST", f"{api_url}/models/{canary_name}/versions",
                      json={"description": "canary v1.0"})
        if check("CreateVersion", r, 201, ms):
            body        = r.json()
            version_key = body.get("versionKey")   # "1.0"
            upload_url  = body.get("uploadUrl")

    # ── 3. S3 presigned PUT (URL is already signed — no SigV4 needed) ─────────
    if upload_url:
        payload = b"canary-synthetic-artifact"
        t0      = time.monotonic()
        r2      = requests.put(upload_url, data=payload, timeout=15)
        ms2     = (time.monotonic() - t0) * 1000
        if r2.status_code not in (200, 204):
            failures.append(
                f"S3 presigned PUT: expected 200/204, got {r2.status_code} — {r2.text[:200]}"
            )
        else:
            print(f"  ✓  {'S3 presigned PUT':<42} {r2.status_code}  {ms2:.0f}ms")
            version_ok = True

    # ── 4. ConfirmVersion ─────────────────────────────────────────────────────
    if version_key and version_ok:
        major, minor = version_key.split(".")
        r, ms = timed(
            "POST",
            f"{api_url}/models/{canary_name}/versions/{major}/{minor}/confirm",
        )
        check("ConfirmVersion", r, 200, ms)

    # ── 5. GetLatestVersion ───────────────────────────────────────────────────
    if model_ok:
        r, ms = timed("GET", f"{api_url}/models/{canary_name}/versions/latest")
        if check("GetLatestVersion", r, 200, ms):
            status = r.json().get("status")
            if status != "READY":
                failures.append(f"GetLatestVersion: status={status!r}, expected 'READY'")

    # ── 6. GetModel ───────────────────────────────────────────────────────────
    if model_ok:
        r, ms = timed("GET", f"{api_url}/models/{canary_name}")
        check("GetModel", r, 200, ms)

    # ── 7. ListModels — verify canary model appears ───────────────────────────
    if model_ok:
        r, ms = timed("GET", f"{api_url}/models")
        if check("ListModels", r, 200, ms):
            names = [m.get("modelName") for m in r.json().get("items", [])]
            if canary_name not in names:
                failures.append(f"ListModels: {canary_name!r} not found in listing")

finally:
    # ── Cleanup — best-effort; never let cleanup failures mask the canary result
    print("\n[canary] cleaning up ...")
    try:
        if version_key:
            major, minor = version_key.split(".")
            requests.delete(
                f"{api_url}/models/{canary_name}/versions/{major}/{minor}",
                auth=auth, timeout=10,
            )
    except Exception as exc:
        print(f"  [warn] version delete failed: {exc}")
    try:
        if model_ok:
            requests.delete(f"{api_url}/models/{canary_name}", auth=auth, timeout=10)
    except Exception as exc:
        print(f"  [warn] model delete failed: {exc}")

print("─" * 64)
if failures:
    print(f"\n[canary] FAIL — {len(failures)} check(s) did not pass:")
    for f in failures:
        print(f"  ✗  {f}")
    sys.exit(1)
else:
    print(f"\n[canary] PASS — stage={stage} is healthy")
    sys.exit(0)
PYEOF
