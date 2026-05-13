"""
Pytest fixtures for Models integration tests.

Two modes:
  CI mode   — INTEG_API_URL is pre-set by the pipeline (stack already deployed).
              No deploy/destroy; just run the tests.
  Local mode — INTEG_API_URL is absent; the fixture deploys a temporary CDK stack
              (stage=integ), retrieves the API URL from stack outputs, runs tests,
              then destroys the stack via a finalizer (runs even on failure).
"""

import json
import os
import subprocess
import time

import boto3
import pytest
import requests
from requests_aws4auth import AWS4Auth

INTEG_STAGE = "integ"
# Stack output key that emits the API Gateway invoke URL.
API_URL_OUTPUT_KEY = "ApiUrl"


def _get_stack_output(stack_name: str, output_key: str, region: str) -> str:
    cf = boto3.client("cloudformation", region_name=region)
    resp = cf.describe_stacks(StackName=stack_name)
    outputs = resp["Stacks"][0].get("Outputs", [])
    for o in outputs:
        if o["OutputKey"] == output_key:
            return o["OutputValue"]
    raise KeyError(f"Stack output {output_key!r} not found in {stack_name}")


@pytest.fixture(scope="session")
def integ_api_url(request, tmp_path_factory):
    """Return the API base URL; deploy a stack if INTEG_API_URL is not set."""
    url = os.environ.get("INTEG_API_URL")
    if url:
        yield url.rstrip("/")
        return

    # Local mode: deploy a temporary integ stack.
    region = os.environ.get("AWS_REGION", "us-east-1")
    repo_root = os.path.abspath(os.path.join(os.path.dirname(__file__), "../.."))
    infra_dir = os.path.join(repo_root, "infra-cdk")

    subprocess.run(
        ["npx", "cdk", "deploy", "--all", "--require-approval", "never",
         "-c", f"stage={INTEG_STAGE}"],
        cwd=infra_dir,
        check=True,
    )

    # Retrieve URL from CloudFormation outputs.
    stack_name = f"ArtifactMgmt-Api-{INTEG_STAGE}"
    url = _get_stack_output(stack_name, API_URL_OUTPUT_KEY, region)

    def _destroy():
        subprocess.run(
            ["npx", "cdk", "destroy", "--all", "--force", "-c", f"stage={INTEG_STAGE}"],
            cwd=infra_dir,
        )

    request.addfinalizer(_destroy)
    yield url.rstrip("/")


@pytest.fixture(scope="session")
def aws_auth():
    """SigV4 auth object for the test caller identity."""
    region = os.environ.get("AWS_REGION", "us-east-1")
    session = boto3.Session()
    creds = session.get_credentials().get_frozen_credentials()
    return AWS4Auth(
        creds.access_key,
        creds.secret_key,
        region,
        "execute-api",
        session_token=creds.token,
    )


@pytest.fixture(scope="session")
def api(integ_api_url, aws_auth):
    """Thin helper that returns a requests.Session pre-wired with SigV4."""

    class ApiClient:
        def __init__(self, base_url, auth):
            self._base = base_url
            self._auth = auth

        def _url(self, path):
            return f"{self._base}{path}"

        def post(self, path, **kwargs):
            return requests.post(self._url(path), auth=self._auth, **kwargs)

        def put(self, path, **kwargs):
            return requests.put(self._url(path), auth=self._auth, **kwargs)

        def get(self, path, **kwargs):
            return requests.get(self._url(path), auth=self._auth, **kwargs)

        def delete(self, path, **kwargs):
            return requests.delete(self._url(path), auth=self._auth, **kwargs)

        def get_unsigned(self, path, **kwargs):
            """GET without signing — used to verify 403 for unauthenticated callers."""
            return requests.get(self._url(path), **kwargs)

        def post_unsigned(self, path, **kwargs):
            return requests.post(self._url(path), **kwargs)

    return ApiClient(integ_api_url, aws_auth)
