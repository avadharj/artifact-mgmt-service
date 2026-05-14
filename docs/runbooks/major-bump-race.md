# Runbook: Major-bump race / VersionConflict alarm

## Symptom

- **Alarm:** `ArtifactMgmt-{stage}-VersionConflictRate` — more than 10 `VersionConflict` events in 5 minutes.
- **What users see:** `POST /models/{name}/versions` returns `409 VERSION_CONFLICT` with `details.current_major` / `current_minor` reflecting the actually-current state. `DELETE /models/{name}/versions/{v}` may also 409 with `VERSION_CONFLICT` if a Sweeper or `ConfirmVersion` runs between the operator's view and the delete. Clients that don't implement retry-on-409 will see hard errors.

## Diagnosis

The `VersionConflict` metric has an `operation` dimension. Split it first:

1. **Identify the operation:**
   ```
   stats Sum(VersionConflict) by operation
   ```
   - `operation=create_version` → multiple writers racing on the same model. The expected pattern when an automation pipeline kicks off N parallel training runs on a shared model. The handler returns 409 with the actual current major/minor so callers can retry; sustained high rate means callers aren't retrying.
   - `operation=delete_version` → operator delete raced with Sweeper or `ConfirmVersion` on the same row. Usually transient.
   - `operation=sweep` → Sweeper raced against `ConfirmVersion` on the same orphan. Benign — the row reached its terminal state via the other writer; Sweeper just yields. Not actionable.

2. **For `create_version` conflicts**, pivot to the affected model(s):
   ```
   fields @timestamp, model_name, current_major, current_minor
   | filter @log = "/aws/lambda/artifact-mgmt-version-alpha"
   | filter @message like /VERSION_CONFLICT/
   | stats count() by model_name
   ```
   If one model dominates → likely an upstream job fan-out without coordination.

3. **Check the model's recent latest_major/minor** via:
   ```
   scripts/ops/show.sh --stage {stage} <model_name>
   ```
   Compare against the timestamps of the 409s. If `latest_major` is advancing rapidly (multiple `major` per minute), confirms a fan-out.

## Actions

- **`operation=sweep`:** no action. Annotate the page as resolved.
- **`operation=delete_version`:** instruct the operator to retry. If the operator is using `scripts/ops/purge.sh` and it's racing, increase the `ops_confirm` delay or schedule the purge during a low-traffic window.
- **`operation=create_version` from an automated pipeline:**
  - Confirm the client implements retry-with-backoff on 409. If yes, the system is working as designed; raise the alarm threshold in `infra-cdk/lib/observability-stack.ts:VersionConflictRate.threshold` if the new baseline is acceptable.
  - If no, contact the client owner and have them add retry logic. As an interim, the alarm should remain in alarm — do not raise the threshold blind.
- **Pathological case (single client locked in an infinite 409 loop with no retry jitter):** find the IAM principal in the `createdBy` field via:
  ```
  scripts/ops/show.sh --stage {stage} <model_name> <version>
  ```
  Coordinate with the client team to stop the runaway job. There is no kill-switch in this service — rate-limiting is the API Gateway's job (see throttling config on `ApiStack`).

## Verification

- The `VersionConflictRate` alarm transitions to `OK` once `Sum(VersionConflict) < 10` over a 5-minute window. SNS delivers the recovery page.
- For a specific model: `scripts/ops/show.sh --stage {stage} <model>` shows `latest_major` settling (no further rapid advances).
- Spot a successful retry in CloudWatch: `filter @message like /create_version/ and @message like /201/`.

## Escalation

- **Sustained `operation=create_version` storm and no client owner reachable:** consider temporarily reducing API Gateway throttle rate for this stage (`ApiStack` -> `throttleRate`). Requires CDK deploy through the pipeline (alpha can patch directly).
- **VersionConflictRate combined with `High5xxRate`:** treat as a single incident — the conflict storm is likely overwhelming the conditional-update path and exhausting Lambda concurrency. Page the on-call lead.
- **Suspected DynamoDB throttling causing apparent conflicts:** check the DDB consumed-capacity widget on the dashboard. If `WriteThrottleEvents > 0`, the root cause is DDB capacity, not application contention.
