# Runbook: Manual deletion / hard purge

## Symptom

This runbook is **operator-driven** — it doesn't correspond to a CloudWatch alarm. Use it when:

- A model owner has explicitly requested permanent deletion of a version (legal request, data-classification mistake, etc.) AND the version is past the 30-day soft-delete buffer.
- A version was created in error (e.g. test data committed by mistake) and the cost/audit footprint of leaving it as `DELETED` is undesirable.
- DR / PITR exercise requires bringing the system to a known-clean state.

If the soft delete is recent (< 30 days), **stop and use DynamoDB PITR instead of purging.** Once `purge.sh` completes there is no recovery.

## Diagnosis

1. **Confirm the version is in the right state.** Hard-purge refuses any row that isn't `status=DELETED` and at least 30 days old (`scripts/ops/purge.sh:PURGE_AGE_DAYS`).
   ```
   scripts/ops/show.sh --stage {stage} <model_name> <version>
   ```
   Look at:
   - `status` — must be `DELETED`. If `ACTIVE` or `READY`, the operator wants `DELETE /models/{name}/versions/{v}` first (soft delete).
   - `updated_at` — must be older than 30 days. If younger, refuse and wait or use PITR.

2. **Confirm the operator has authority.** Cross-check:
   - The model owner approved the deletion (Slack thread, ticket, email — capture an audit trail).
   - The caller's IAM identity (`aws sts get-caller-identity`) is admin or the model owner. `purge.sh` does not enforce this — it trusts the caller; the audit log captures who did it.

3. **Note the S3 key from the version row.** `purge.sh` reads `s3_key` from DDB before deleting; if the operator wants to keep an out-of-band backup (e.g., to legal-hold storage), do that copy **before** running `purge.sh`.

## Actions

```bash
# 1. (Optional) Pull a copy of the bytes to a secure location before deleting.
aws s3 cp \
  s3://artifact-mgmt-{stage}-{account}/{model}/v{M}.{N}/weights.bin \
  s3://your-archive-bucket/manual-purges/$(date -u +%Y%m%d)/

# 2. Run the purge (interactive — refuses non-TTY stdin unless --yes-i-am-sure).
scripts/ops/purge.sh --stage {stage} <model_name> <version>
```

The script will:
- Refuse if `status != DELETED` or age < 30 days.
- Print a confirmation summary (stage, model, version, s3_key, age_days).
- Prompt for `YES` to confirm.
- Audit-log the action to `ops-audit-{stage}` BEFORE the destructive ops, then S3 `DeleteObject` and DDB `DeleteItem`.

**Do NOT use `--yes-i-am-sure` in production unless you're running the purge from a tested automation script.** The interactive prompt is the last line of defense.

## Verification

- `scripts/ops/show.sh --stage {stage} <model> <version>` should now show `"NOT FOUND"` for both the version row and the S3 object.
- The audit event in CloudWatch:
  ```
  fields @timestamp, action, caller, details.model_name, details.version, details.s3_key, details.age_days
  | filter @log = "ops-audit-{stage}"
  | filter action = "purge"
  | filter details.model_name = "<model>"
  ```
  must contain a single event with the operator's IAM ARN and the deleted version. If multiple events appear, the same purge was run twice — confirm idempotently that the row is actually gone (second run would have refused with "version not found").
- `GET /versions/{v}` against the deployed API now returns `404 VERSION_NOT_FOUND`.
- `GET /versions/latest` skips the purged row (was already skipping when it was `DELETED`).

## Escalation

- **Operator hits "refusing to purge: DELETED for only X days":** this is the load-bearing safety guardrail. Do not patch the script to bypass. If the deletion is legally time-sensitive, escalate to the on-call lead to authorize a manual `aws dynamodb delete-item` + `aws s3 delete-object`. Capture justification in the audit log retroactively (via `aws logs put-log-events` to `ops-audit-{stage}`).
- **Suspected concurrent deletion by another operator:** abort. Re-run `show.sh` to verify the current state. If two operators race, one will see "version not found" and exit safely.
- **Need to delete an entire model's history:** there is no batch-purge script by design. Loop `purge.sh` per version with a sleep between calls; the audit log captures every action. Notify the on-call lead before starting a multi-version purge.
- **Legal hold or e-discovery request:** stop. Hard-delete is irreversible. Contact legal + on-call lead before running anything in this runbook.
