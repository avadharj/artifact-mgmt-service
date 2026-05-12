"""
Integration tests for the Versions resource (Epic 4): full create → upload → confirm flow.

Run against a deployed beta (or integ) stack:

    INTEG_API_URL=https://<api-id>.execute-api.<region>.amazonaws.com/prod \
        AWS_REGION=us-east-1 \
        pytest tests/integration/test_versions.py -v

The conftest.py fixture handles SigV4 signing via the caller's current AWS
credentials. Presigned S3 PUTs are made with plain requests (the URL itself
carries the signature).
"""

import concurrent.futures
import hashlib
import os
import uuid

import pytest
import requests


def unique_name(prefix="integ-version"):
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


def new_idempotency_key():
    return str(uuid.uuid4())


def make_dep_snapshot(framework="pytorch"):
    return {
        "framework": {"name": framework, "version": "2.0"},
        "pythonVersion": "3.11",
        "packages": {},
        "os": "linux",
        "capturedAt": "2026-01-01T00:00:00Z",
    }


@pytest.fixture
def model(api):
    """Create a fresh model for a test; teardown deletes it."""
    name = unique_name()
    resp = api.post(
        "/models", json={"modelName": name, "frameworkHint": "pytorch"}
    )
    assert resp.status_code == 201, resp.text
    yield name
    api.delete(f"/models/{name}")


def create_version(api, model_name, *, major=None, idempotency_key=None):
    body = {
        "idempotencyKey": idempotency_key or new_idempotency_key(),
        "depSnapshot": make_dep_snapshot(),
        "trainingMetadata": {},
    }
    if major is not None:
        body["major"] = major
    return api.post(f"/models/{model_name}/versions", json=body)


def upload_blob(upload_url, blob):
    """PUT bytes to a presigned S3 URL — the URL carries the signature."""
    return requests.put(
        upload_url, data=blob, headers={"Content-Type": "application/octet-stream"}
    )


def sha256_b64(blob: bytes) -> str:
    import base64

    return base64.b64encode(hashlib.sha256(blob).digest()).decode("ascii")


# ── AC1: Happy path — create → upload → confirm → get returns READY ─────────


def test_happy_path_create_upload_confirm(api, model):
    resp = create_version(api, model)
    assert resp.status_code == 201, resp.text
    created = resp.json()
    assert created["version"] == "1.0"
    assert created["status"] == "PENDING"
    upload_url = created["uploadUrl"]

    blob = os.urandom(10 * 1024 * 1024)  # 10 MB
    put_resp = upload_blob(upload_url, blob)
    assert put_resp.status_code in (200, 204), put_resp.text

    confirm_resp = api.post(
        f"/models/{model}/versions/1.0/confirm",
        json={"sizeBytes": len(blob), "checksumSha256": sha256_b64(blob)},
    )
    assert confirm_resp.status_code == 200, confirm_resp.text
    assert confirm_resp.json()["status"] == "READY"

    get_resp = api.get(f"/models/{model}/versions/1.0")
    assert get_resp.status_code == 200, get_resp.text
    assert get_resp.json()["status"] == "READY"


# ── AC2: Major bump — list returns [v2.0, v1.0] newest-first ─────────────────


def test_major_bump_returns_newest_first(api, model):
    r1 = create_version(api, model)
    assert r1.status_code == 201
    r2 = create_version(api, model, major=2)
    assert r2.status_code == 201
    assert r2.json()["version"] == "2.0"

    listing = api.get(f"/models/{model}/versions")
    assert listing.status_code == 200, listing.text
    versions = [item["version"] for item in listing.json()["items"]]
    assert versions[:2] == ["2.0", "1.0"]


# ── AC3: Skip-major — list returns [v5.0, v1.0] ───────────────────────────────


def test_skip_major_allowed(api, model):
    assert create_version(api, model).status_code == 201
    r2 = create_version(api, model, major=5)
    assert r2.status_code == 201
    assert r2.json()["version"] == "5.0"

    listing = api.get(f"/models/{model}/versions")
    versions = [item["version"] for item in listing.json()["items"]]
    assert versions[:2] == ["5.0", "1.0"]


# ── AC4: Race — 10 concurrent CreateVersion calls produce 10 distinct sequential versions ──


@pytest.mark.parametrize("run", range(5))  # determinism: 5 runs
def test_race_10_concurrent_creates(api, run):
    """Spec requires this be deterministic across 5 runs — parametrized to enforce."""
    name = unique_name(f"race-{run}")
    api.post("/models", json={"modelName": name, "frameworkHint": "pytorch"})

    def call_create(_):
        return create_version(api, name)

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        results = list(ex.map(call_create, range(10)))

    versions = []
    for r in results:
        assert r.status_code in (201, 409), f"unexpected: {r.status_code} {r.text}"
        if r.status_code == 201:
            versions.append(r.json()["version"])

    # All 10 should ultimately succeed once retried; but in pure race semantics
    # we expect either 10 distinct successes (if the server serializes via
    # conditional updates) OR a mix of 201/409 where 409s should be retried.
    # The spec requires "10 distinct sequential versions, no gaps, no duplicates" —
    # this assumes the server retries internally OR clients retry. Our handler
    # surfaces 409 to callers, so retry here.

    retries = 0
    while sum(1 for r in results if r.status_code == 409) > 0 and retries < 20:
        retry_targets = [i for i, r in enumerate(results) if r.status_code == 409]
        for i in retry_targets:
            results[i] = create_version(api, name)
        retries += 1

    versions = sorted(r.json()["version"] for r in results if r.status_code == 201)
    assert len(versions) == 10, f"expected 10 successes, got {versions}"
    assert len(set(versions)) == 10, f"duplicates found: {versions}"
    # Sequential: 1.0, 1.1, 1.2, ..., 1.9
    expected = [f"1.{i}" for i in range(10)]
    assert sorted(versions) == sorted(expected), f"got {versions}"

    api.delete(f"/models/{name}")


# ── AC5: Confirm without upload → 404 ────────────────────────────────────────


def test_confirm_without_upload_returns_404(api, model):
    r = create_version(api, model)
    assert r.status_code == 201

    confirm = api.post(
        f"/models/{model}/versions/1.0/confirm",
        json={"sizeBytes": 1024},
    )
    assert confirm.status_code == 404, confirm.text
    assert confirm.json()["code"] == "UPLOAD_NOT_FOUND"


# ── AC6: Confirm with bad checksum → 409 ─────────────────────────────────────


def test_confirm_with_bad_checksum_returns_409(api, model):
    r = create_version(api, model)
    assert r.status_code == 201
    upload_url = r.json()["uploadUrl"]

    blob = os.urandom(1024 * 1024)
    assert upload_blob(upload_url, blob).status_code in (200, 204)

    confirm = api.post(
        f"/models/{model}/versions/1.0/confirm",
        json={"sizeBytes": len(blob), "checksumSha256": "wrong-hash"},
    )
    assert confirm.status_code == 409, confirm.text
    assert confirm.json()["code"] == "CHECKSUM_MISMATCH"


# ── AC7: Idempotency — same key returns identical version, only one DDB row ──


def test_idempotency_replay_returns_same_version(api, model):
    key = new_idempotency_key()
    r1 = create_version(api, model, idempotency_key=key)
    assert r1.status_code == 201
    v1 = r1.json()["version"]

    r2 = create_version(api, model, idempotency_key=key)
    assert r2.status_code == 200, r2.text  # 200 on replay (not 201)
    assert r2.json()["version"] == v1

    # Verify only one row exists by listing versions for the model
    listing = api.get(f"/models/{model}/versions")
    versions = [item["version"] for item in listing.json()["items"]]
    assert versions.count(v1) == 1, f"duplicate row found: {versions}"
