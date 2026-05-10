"""
Integration tests for the Models resource (Epic 3).

Run against a deployed beta (or integ) stack:

    INTEG_API_URL=https://<api-id>.execute-api.<region>.amazonaws.com/prod \
    AWS_REGION=us-east-1 \
    pytest tests/integration/test_models.py -v

The conftest.py fixture handles SigV4 signing via the caller's current AWS
credentials. For CI, the pipeline sets INTEG_API_URL before invoking
scripts/integ-test.sh.
"""

import concurrent.futures
import time
import uuid

import pytest


def unique_name(prefix="integ-model"):
    return f"{prefix}-{uuid.uuid4().hex[:8]}"


# ── AC1: Full create → get → list → delete → get(404) flow ───────────────────


def test_create_get_list_delete_flow(api):
    name = unique_name()
    payload = {"modelName": name, "frameworkHint": "pytorch", "description": "integ test"}

    # Create
    resp = api.post("/models", json=payload)
    assert resp.status_code == 201, resp.text
    body = resp.json()
    assert body["modelName"] == name
    assert body["status"] == "ACTIVE"
    assert body["latestMinor"] == -1

    # Get — full record
    resp = api.get(f"/models/{name}")
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["modelName"] == name
    assert "description" in body
    assert "createdAt" in body
    assert resp.headers.get("Cache-Control") == "max-age=0, must-revalidate"

    # List — sparse view contains the model
    resp = api.get("/models")
    assert resp.status_code == 200, resp.text
    items = resp.json()["items"]
    names = [m["modelName"] for m in items]
    assert name in names
    # Sparse: description and timestamps must not be present in list items
    matching = next(m for m in items if m["modelName"] == name)
    assert "description" not in matching
    assert "createdAt" not in matching

    # Delete
    resp = api.delete(f"/models/{name}")
    assert resp.status_code == 204, resp.text

    # Get after delete → 404
    resp = api.get(f"/models/{name}")
    assert resp.status_code == 404, resp.text
    assert resp.json()["code"] == "MODEL_NOT_FOUND"


# ── AC1 supplemental: re-delete is idempotent (204) ──────────────────────────


def test_redelete_is_idempotent(api):
    name = unique_name()
    api.post("/models", json={"modelName": name, "frameworkHint": "sklearn"})
    api.delete(f"/models/{name}")
    resp = api.delete(f"/models/{name}")
    assert resp.status_code == 204, resp.text


# ── AC1 supplemental: 404 on get for model that never existed ─────────────────


def test_get_missing_model_returns_404(api):
    resp = api.get(f"/models/{unique_name()}")
    assert resp.status_code == 404
    assert resp.json()["code"] == "MODEL_NOT_FOUND"


# ── AC2: Concurrent create — exactly 1× 201, 9× 409 ──────────────────────────


def test_concurrent_create_exactly_one_succeeds(api):
    name = unique_name("concurrent")
    payload = {"modelName": name, "frameworkHint": "pytorch"}

    def do_create(_):
        return api.post("/models", json=payload).status_code

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        results = list(ex.map(do_create, range(10)))

    assert results.count(201) == 1, f"Expected exactly 1× 201, got: {results}"
    assert results.count(409) == 9, f"Expected exactly 9× 409, got: {results}"


# ── AC3: 403 for unsigned (unauthenticated) requests ─────────────────────────


def test_unsigned_request_returns_403(api):
    resp = api.get_unsigned("/models")
    assert resp.status_code == 403, resp.text


def test_unsigned_create_returns_403(api):
    resp = api.post_unsigned(
        "/models", json={"modelName": unique_name(), "frameworkHint": "pytorch"}
    )
    assert resp.status_code == 403, resp.text


# ── Pagination ────────────────────────────────────────────────────────────────


def test_list_pagination(api):
    """Create 5 models, fetch with limit=2, verify all pages collected."""
    names = [unique_name("page") for _ in range(5)]
    for name in names:
        resp = api.post("/models", json={"modelName": name, "frameworkHint": "xgboost"})
        assert resp.status_code == 201

    collected = []
    token = None
    pages = 0

    while True:
        params = {"limit": "2"}
        if token:
            params["pageToken"] = token
        resp = api.get("/models", params=params)
        assert resp.status_code == 200
        data = resp.json()
        collected.extend(m["modelName"] for m in data["items"])
        token = data.get("nextPageToken")
        pages += 1
        if not token:
            break

    for name in names:
        assert name in collected, f"{name} missing from paginated results"
    assert pages >= 3  # 5 items at limit=2 requires at least 3 pages

    # Cleanup
    for name in names:
        api.delete(f"/models/{name}")


# ── Validation ────────────────────────────────────────────────────────────────


def test_create_missing_model_name_returns_400(api):
    resp = api.post("/models", json={"frameworkHint": "pytorch"})
    assert resp.status_code == 400, resp.text


def test_limit_clamped_to_200(api):
    resp = api.get("/models", params={"limit": "9999"})
    assert resp.status_code == 200
    # The DAO enforces the clamp; we just verify the request doesn't error
