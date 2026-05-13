#!/usr/bin/env bash
# Shared helpers for ops scripts. Source from each script.

set -euo pipefail

ops_die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

# Resolve a CloudFormation output value from a stack. Args: stack_name, output_key.
ops_stack_output() {
  local stack_name=$1
  local output_key=$2
  aws cloudformation describe-stacks \
    --stack-name "$stack_name" \
    --query "Stacks[0].Outputs[?OutputKey=='${output_key}'].OutputValue" \
    --output text 2>/dev/null
}

# Populate OPS_MODELS_TABLE, OPS_VERSIONS_TABLE, OPS_BUCKET, OPS_LOG_GROUP for the given stage.
# Refuses if any output is missing (the stack must be deployed).
ops_resolve_stage() {
  local stage=$1
  local data_stack="ArtifactMgmt-Data-${stage}"
  OPS_MODELS_TABLE=$(ops_stack_output "$data_stack" ModelsTableName)
  OPS_VERSIONS_TABLE=$(ops_stack_output "$data_stack" VersionsTableName)
  OPS_BUCKET=$(ops_stack_output "$data_stack" ArtifactsBucketName)
  OPS_LOG_GROUP="ops-audit-${stage}"
  [ -n "$OPS_MODELS_TABLE" ] || ops_die "Could not resolve ModelsTableName for stage=${stage}. Is the stack deployed?"
  [ -n "$OPS_VERSIONS_TABLE" ] || ops_die "Could not resolve VersionsTableName for stage=${stage}."
  [ -n "$OPS_BUCKET" ] || ops_die "Could not resolve ArtifactsBucketName for stage=${stage}."
}

# Audit-log a single ops action. The log group is created on first use; one stream per UTC date.
# Args: action_name, json_details_string (must already be valid JSON).
ops_audit() {
  local action=$1
  local details=$2
  local caller
  caller=$(aws sts get-caller-identity --query Arn --output text 2>/dev/null || echo "unknown")
  local timestamp_ms
  timestamp_ms=$(($(date +%s) * 1000))
  local stream
  stream="$(date -u +%Y-%m-%d)/$(basename "$0" .sh)"

  # Create group + stream if they don't exist. The exit-code dance keeps `set -e` happy.
  aws logs create-log-group --log-group-name "$OPS_LOG_GROUP" 2>/dev/null || true
  aws logs create-log-stream \
    --log-group-name "$OPS_LOG_GROUP" \
    --log-stream-name "$stream" 2>/dev/null || true

  local message
  message=$(printf '{"action":"%s","caller":"%s","details":%s}' "$action" "$caller" "$details")

  aws logs put-log-events \
    --log-group-name "$OPS_LOG_GROUP" \
    --log-stream-name "$stream" \
    --log-events "timestamp=${timestamp_ms},message=$(printf '%s' "$message" | jq -Rs '.')" \
    >/dev/null
}

# Prompt the operator to confirm a destructive action. Refuses if stdin is not a TTY (no
# accidental piping a "yes" through CI). Use --yes-i-am-sure to bypass programmatically.
ops_confirm() {
  local prompt=$1
  if [ "${OPS_FORCE_YES:-}" = "1" ]; then
    return 0
  fi
  if [ ! -t 0 ]; then
    ops_die "Refusing destructive action over non-interactive stdin. Pass --yes-i-am-sure to bypass."
  fi
  local reply
  printf '%s [type YES to confirm]: ' "$prompt" >&2
  read -r reply
  [ "$reply" = "YES" ] || ops_die "aborted"
}
