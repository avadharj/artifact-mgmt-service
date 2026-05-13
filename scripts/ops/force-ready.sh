#!/usr/bin/env bash
# force-ready.sh — flip a version's status to READY without going through ConfirmVersion.
#
# Use case: the sweeper marked a row FAILED in error (e.g. a flaky S3 propagation delay),
# or the API path is broken and an admin needs to manually unblock a model release. The
# checksum must be supplied to demonstrate the operator verified the bytes out-of-band.
#
# Usage:
#   force-ready.sh --stage <stage> --checksum <sha256-base64> <model_name> <version>
#
# Required: --checksum (base64 SHA-256 of the artifact). The bytes-on-disk integrity guarantee
# is on you when bypassing the normal Confirm path.
#
# Examples:
#   force-ready.sh --stage alpha --checksum 'PKJZ3gbrmC/G0p1qhTgx3+/GUVwDEY71/VosrKw9T0A=' \
#       fraud-detector 1.0

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

print_help() {
  awk 'NR==1 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
}

STAGE=""
CHECKSUM=""
MODEL=""
VERSION=""

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h) print_help; exit 0 ;;
    --stage) STAGE=${2:-}; shift 2 ;;
    --checksum) CHECKSUM=${2:-}; shift 2 ;;
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
[ -n "$CHECKSUM" ] || ops_die "missing --checksum. Try --help. (force-ready always requires the SHA-256.)"
[ -n "$MODEL" ] || ops_die "missing model_name. Try --help."
[ -n "$VERSION" ] || ops_die "missing version. Try --help."

ops_resolve_stage "$STAGE"

major=${VERSION%.*}
minor=${VERSION#*.}
version_key=$(printf '%04d.%04d' "$major" "$minor")

# Show the current state before flipping anything, so the operator can read & decide.
printf '=== Current state (before force-ready) ===\n'
current=$(aws dynamodb get-item \
  --table-name "$OPS_VERSIONS_TABLE" \
  --key "$(printf '{"model_name":{"S":"%s"},"version_key":{"S":"%s"}}' "$MODEL" "$version_key")" \
  --output json)
echo "$current" | jq '.Item // "NOT FOUND"'

[ "$(echo "$current" | jq -r '.Item // empty')" != "" ] \
  || ops_die "version not found: model=$MODEL version=$VERSION (table=$OPS_VERSIONS_TABLE)"

prior_status=$(echo "$current" | jq -r '.Item.status.S')
prior_checksum=$(echo "$current" | jq -r '.Item.checksum_sha256.S // empty')

ops_confirm "About to force model=$MODEL version=$VERSION from $prior_status to READY on stage=$STAGE. Proceed?"

# Audit BEFORE the destructive op so the trail survives partial failure (matches purge.sh).
# No conditional check on update — operator already eyeballed the prior state above and
# confirmed; the audit payload captures from_status/prior_checksum for forensics.
ops_audit force-ready "$(jq -nc \
  --arg stage "$STAGE" \
  --arg model "$MODEL" \
  --arg version "$VERSION" \
  --arg from "$prior_status" \
  --arg prior_ck "$prior_checksum" \
  --arg new_ck "$CHECKSUM" \
  '{stage:$stage, model_name:$model, version:$version, from_status:$from, to_status:"READY", prior_checksum:$prior_ck, new_checksum:$new_ck}')"

aws dynamodb update-item \
  --table-name "$OPS_VERSIONS_TABLE" \
  --key "$(printf '{"model_name":{"S":"%s"},"version_key":{"S":"%s"}}' "$MODEL" "$version_key")" \
  --update-expression "SET #st = :ready, checksum_sha256 = :ck" \
  --expression-attribute-names '{"#st":"status"}' \
  --expression-attribute-values "$(printf '{":ready":{"S":"READY"},":ck":{"S":"%s"}}' "$CHECKSUM")" \
  >/dev/null

printf 'force-ready applied: model=%s version=%s stage=%s\n' "$MODEL" "$VERSION" "$STAGE"
