# Runbook: Orphan upload cleanup

## Symptom

- **Alarm:** `ArtifactMgmt-{stage}-OrphanRate` — Sweeper flipped more than 50 PENDING rows to READY/FAILED in the last hour.
- **What users see:** depends on the underlying cause:
  - If the sweep is mostly READY: nothing — uploads completed but clients never called `ConfirmVersion`. Their next read of `GET /versions/latest` may still 404 until the sweeper runs.
  - If the sweep is mostly FAILED: clients see their `PENDING` versions silently turn `FAILED` 24h after creation. Calls to `ConfirmVersion` on those rows return `412 PRECONDITION_FAILED`.

## Diagnosis

1. **Pull sweep activity from CloudWatch Logs:**
   ```
   fields @timestamp, model_name, version, to_status, reason
   | filter @log = "/aws/lambda/artifact-mgmt-sweeper-{stage}"
   | filter @message like /sweep_action/
   | stats count() by to_status, reason
   ```
   - High `to_status=READY, reason=matching-upload` → clients are uploading but skipping `ConfirmVersion`. Likely a client SDK regression.
   - High `to_status=FAILED, reason=no-upload` → clients hit `CreateVersion`, got a presigned URL, never PUT the bytes. SDK timeout or PUT failure.
   - High `to_status=FAILED, reason=checksum-mismatch` → bytes uploaded but the SHA-256 didn't match what the client baked into `CreateVersion`. Corruption in flight or a client-side hashing bug.

2. **Pivot to a representative orphan:**
   ```
   scripts/ops/show.sh --stage {stage} <model_name> <version>
   ```
   Look at `createdBy` (which IAM identity created the orphan) and `createdAt` (clock-correlate with client deploy timestamps).

3. **Check the `VersionsCreated` vs `VersionsConfirmed` widget on the dashboard.** A widening gap over the last 24h is the same signal at a different time-scale.

## Actions

- **If clients are skipping `ConfirmVersion`:** the sweeper has already done its job — the rows are now READY and discoverable. No code action needed in this stage. **Escalate to the client team** so they fix the SDK.
- **If uploads are failing (`reason=no-upload`):** check S3 access-log spikes from this stage's `artifacts-logging` bucket. If S3 itself is failing, raise an AWS support case.
- **If checksums are mismatching:** spot-check the affected version's stored `checksum_sha256` against the S3 object's stored SHA-256 (`HeadObject` with `ChecksumMode=ENABLED`). If S3's value matches but the row's stored value doesn't, the client is computing the hash wrong; if S3 returned null, the client didn't send the `x-amz-checksum-sha256` header (the upload URL probably wasn't bound to a checksum at `CreateVersion` time).
- **If a specific orphan needs manual resolution:**
  ```
  scripts/ops/force-ready.sh --stage {stage} --checksum <sha256> <model> <version>
  ```
  Use only after manually verifying the bytes on disk — `force-ready` bypasses the normal `ConfirmVersion` integrity check.

## Verification

- Re-run the CloudWatch query above with `@start = -10m`. Expect zero new `sweep_action` events for the impacted model after the client fix rolls out.
- The `OrphanRate` alarm transitions from `IN_ALARM` to `OK` once one full evaluation period (1h) passes below threshold. The SNS topic delivers an OK page so the on-call sees the recovery.
- For a specific row recovery: `scripts/ops/show.sh --stage {stage} <model> <version>` should show `status=READY` and a non-null `checksum_sha256`.

## Escalation

- **Client SDK regression suspected:** ping #ml-platform-sdk in Slack with the offending `createdBy` ARNs and timestamps.
- **S3 / DynamoDB / Lambda service issue:** open an AWS support case under the prod account (account ID from `stage-config.ts`). Severity 2 for any sustained burst.
- **Cannot recover a version via `force-ready` (operator believes bytes are bad):** escalate to the model owner; do not invent a checksum. Soft-delete the row via `DELETE /versions/{v}` and have the client re-upload.
