#!/usr/bin/env bash
# show.sh — read-only operator inspect of a model and (optionally) a specific version.
#
# Usage:
#   show.sh --stage <stage> <model_name>
#   show.sh --stage <stage> <model_name> <version>     # M.N format
#
# Examples:
#   show.sh --stage alpha fraud-detector
#   show.sh --stage beta  fraud-detector 3.5

set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=_lib.sh
. "$SCRIPT_DIR/_lib.sh"

print_help() {
  # Print the leading comment block (lines 2.. until the first non-comment line).
  awk 'NR==1 {next} /^#/ {sub(/^# ?/,""); print; next} {exit}' "${BASH_SOURCE[0]}"
}

STAGE=""
MODEL=""
VERSION=""

while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h) print_help; exit 0 ;;
    --stage) STAGE=${2:-}; shift 2 ;;
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

ops_resolve_stage "$STAGE"

ops_audit show "$(printf '{"stage":"%s","model_name":"%s","version":"%s"}' "$STAGE" "$MODEL" "${VERSION:-}")"

printf '=== Model: %s (stage=%s) ===\n' "$MODEL" "$STAGE"
aws dynamodb get-item \
  --table-name "$OPS_MODELS_TABLE" \
  --key "$(printf '{"model_name":{"S":"%s"}}' "$MODEL")" \
  --output json | jq '.Item // "NOT FOUND"'

if [ -n "$VERSION" ]; then
  # Encode "3.5" → "0003.0005" to match the SK encoding in VersionKey.encode().
  major=${VERSION%.*}
  minor=${VERSION#*.}
  version_key=$(printf '%04d.%04d' "$major" "$minor")

  printf '\n=== Version: %s (version_key=%s) ===\n' "$VERSION" "$version_key"
  aws dynamodb get-item \
    --table-name "$OPS_VERSIONS_TABLE" \
    --key "$(printf '{"model_name":{"S":"%s"},"version_key":{"S":"%s"}}' "$MODEL" "$version_key")" \
    --output json | jq '.Item // "NOT FOUND"'

  printf '\n=== S3 object: s3://%s/%s/v%s/weights.bin ===\n' "$OPS_BUCKET" "$MODEL" "$VERSION"
  aws s3api head-object --bucket "$OPS_BUCKET" --key "$MODEL/v$VERSION/weights.bin" 2>&1 \
    | jq '.' 2>/dev/null || echo "(no object or HeadObject failed — see error above)"
fi
