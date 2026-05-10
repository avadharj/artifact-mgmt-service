#!/usr/bin/env bash
# Run integration tests against a deployed stage.
# Usage: ./scripts/integ-test.sh <stage>
# The script resolves the API URL from CloudFormation outputs and passes it to
# pytest as INTEG_API_URL. AWS credentials must be configured for the target account.
set -euo pipefail

STAGE="${1:?Usage: integ-test.sh <stage>}"
REGION="${AWS_REGION:-us-east-1}"
STACK_NAME="ArtifactMgmt-Api-${STAGE}"

echo "[integ] Resolving API URL from stack: ${STACK_NAME}"
API_URL=$(aws cloudformation describe-stacks \
  --stack-name "${STACK_NAME}" \
  --region "${REGION}" \
  --query "Stacks[0].Outputs[?OutputKey=='ApiUrl'].OutputValue" \
  --output text)

if [[ -z "${API_URL}" ]]; then
  echo "[integ] ERROR: Could not resolve ApiUrl from stack ${STACK_NAME}" >&2
  exit 1
fi

echo "[integ] API_URL=${API_URL}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

pip install --quiet -r "${REPO_ROOT}/tests/integration/requirements.txt"

INTEG_API_URL="${API_URL}" \
AWS_REGION="${REGION}" \
  pytest "${REPO_ROOT}/tests/integration/" \
    --tb=short \
    --timeout=120 \
    -v
