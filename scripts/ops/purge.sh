#!/usr/bin/env bash
# purge.sh — permanently delete a version's DDB row + S3 object. Hard delete, no recovery.
#
# Refuses unless the version's status is DELETED and updated_at is older than 30 days.
# This is the only path that touches S3 bytes after a soft delete; if you need to recover
# a model release within 30 days of its soft delete, use the DDB PITR window instead.
#
# Usage:
#   purge.sh --stage <stage> <model_name> <version>
#
# Examples:
#   purge.sh --stage alpha fraud-detector 1.0

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

print_help() {
  awk 'NR==1 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
}

PURGE_AGE_DAYS=30

STAGE=""
MODEL=""
VERSION=""

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h) print_help; exit 0 ;;
    --stage) STAGE=${2:-}; shift 2 ;;
    --yes-i-am-sure) export OPS_FORCE_YES=1; shift ;;
    --) shift; break ;;
    -*) ops_die "unknown flag: $1" ;;
    *)
      if [ -z "$MODEL" ]; then MODEL=$1
      elif [ -z "$VERSION" ]; then VERSION=$1
      else ops_die "unexpected positional argument: $1"
      fi
      shift ;;
  esac
done

[ -n "$STAGE" ] || ops_die "missing --stage. Try --help."
[ -n "$MODEL" ] || ops_die "missing model_name. Try --help."
[ -n "$VERSION" ] || ops_die "missing version. Try --help."

ops_resolve_stage "$STAGE"

major=${VERSION%.*}
minor=${VERSION#*.}
version_key=$(printf '%04d.%04d' "$major" "$minor")

current=$(aws dynamodb get-item \
  --table-name "$OPS_VERSIONS_TABLE" \
  --key "$(printf '{"model_name":{"S":"%s"},"version_key":{"S":"%s"}}' "$MODEL" "$version_key")" \
  --output json)

[ "$(echo "$current" | jq -r '.Item // empty')" != "" ] \
  || ops_die "version not found: model=$MODEL version=$VERSION (table=$OPS_VERSIONS_TABLE)"

status=$(echo "$current" | jq -r '.Item.status.S')
updated_at=$(echo "$current" | jq -r '.Item.updated_at.S // .Item.created_at.S')

[ "$status" = "DELETED" ] \
  || ops_die "refusing to purge: status=$status (must be DELETED). Soft-delete first via DELETE /versions/{v}."

# Age check: require updated_at older than PURGE_AGE_DAYS days. The shorter-age refusal is the
# load-bearing guardrail — PITR-based recovery is impossible after the row is gone, so we keep
# 30 days of "oops" buffer even after the operator-driven soft delete.
deleted_epoch=$(date -j -u -f '%Y-%m-%dT%H:%M:%S' "$(echo "$updated_at" | cut -c1-19)" '+%s' 2>/dev/null \
  || date -u -d "$updated_at" '+%s')
now_epoch=$(date -u +%s)
age_days=$(( (now_epoch - deleted_epoch) / 86400 ))

if [ "$age_days" -lt "$PURGE_AGE_DAYS" ]; then
  ops_die "refusing to purge: DELETED for only ${age_days} days (minimum is ${PURGE_AGE_DAYS}). Use DDB PITR to recover if needed; otherwise re-run after the buffer window expires."
fi

s3_key=$(echo "$current" | jq -r '.Item.s3_key.S')

printf '=== About to purge ===\n'
printf 'stage      : %s\n' "$STAGE"
printf 'model      : %s\n' "$MODEL"
printf 'version    : %s\n' "$VERSION"
printf 'ddb_key    : (%s, %s)\n' "$MODEL" "$version_key"
printf 's3_object  : s3://%s/%s\n' "$OPS_BUCKET" "$s3_key"
printf 'deleted_for: %s days (>= %s required)\n' "$age_days" "$PURGE_AGE_DAYS"

ops_confirm "This will PERMANENTLY delete the DDB row and the S3 object. There is NO recovery once this completes. Proceed?"

# Audit BEFORE the destructive ops so the trail survives even if something fails midway.
ops_audit purge "$(jq -nc \
  --arg stage "$STAGE" \
  --arg model "$MODEL" \
  --arg version "$VERSION" \
  --arg s3_key "$s3_key" \
  --arg age_days "$age_days" \
  '{stage:$stage, model_name:$model, version:$version, s3_key:$s3_key, age_days:($age_days|tonumber)}')"

aws s3api delete-object --bucket "$OPS_BUCKET" --key "$s3_key" >/dev/null
aws dynamodb delete-item \
  --table-name "$OPS_VERSIONS_TABLE" \
  --key "$(printf '{"model_name":{"S":"%s"},"version_key":{"S":"%s"}}' "$MODEL" "$version_key")" \
  >/dev/null

printf 'purged: model=%s version=%s stage=%s\n' "$MODEL" "$VERSION" "$STAGE"
