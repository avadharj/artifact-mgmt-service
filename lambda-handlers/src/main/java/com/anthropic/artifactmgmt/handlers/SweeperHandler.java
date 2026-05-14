package com.anthropic.artifactmgmt.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.amazonaws.xray.interceptors.TracingInterceptor;
import com.anthropic.artifactmgmt.dao.VersionDao;
import com.anthropic.artifactmgmt.exception.VersionConflictException;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.lambda.powertools.logging.LoggingUtils;

/**
 * Story 6.1 — hourly EventBridge-scheduled Lambda that reconciles PENDING version rows whose upload
 * never completed (no ConfirmVersion call) or completed but was never confirmed.
 *
 * <p>For each PENDING row older than 24h:
 *
 * <ul>
 *   <li>S3 HeadObject hit + checksum match → flip to READY
 *   <li>S3 HeadObject hit + checksum mismatch → flip to FAILED
 *   <li>S3 NoSuchKey (upload never happened) → flip to FAILED
 * </ul>
 *
 * <p>Page size 100, capped at 1000 rows per invocation (DDB query-cost guardrail). Dry-run mode via
 * DRY_RUN=true env var leaves DDB untouched but still emits the audit log line.
 */
public class SweeperHandler implements RequestHandler<ScheduledEvent, Void> {

  private static final Logger logger = LogManager.getLogger(SweeperHandler.class);

  static final int BATCH_SIZE = 100;
  static final int MAX_PER_INVOCATION = 1000;
  static final Duration ORPHAN_AGE = Duration.ofHours(24);

  private final VersionDao versionDao;
  private final S3Client s3Client;
  private final String bucket;
  private final boolean dryRun;
  private final MetricsPublisher metrics;

  /** Production constructor — reads config from environment. */
  public SweeperHandler() {
    String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    // Story 7.3: one X-Ray subsegment per DDB / S3 API call. Shared across both clients.
    ClientOverrideConfiguration xrayConfig =
        ClientOverrideConfiguration.builder()
            .addExecutionInterceptor(new TracingInterceptor())
            .build();
    DynamoDbClient dynamo =
        DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(UrlConnectionHttpClient.create())
            .overrideConfiguration(xrayConfig)
            .build();
    this.versionDao = new VersionDao(dynamo, System.getenv("VERSIONS_TABLE"));
    this.s3Client =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(UrlConnectionHttpClient.create())
            .overrideConfiguration(xrayConfig)
            .build();
    this.bucket = System.getenv("ARTIFACTS_BUCKET");
    this.dryRun = Boolean.parseBoolean(System.getenv().getOrDefault("DRY_RUN", "false"));
    this.metrics = new PowertoolsMetricsPublisher();
  }

  /** Test constructor — accepts injected dependencies. */
  SweeperHandler(
      VersionDao versionDao,
      S3Client s3Client,
      String bucket,
      boolean dryRun,
      MetricsPublisher metrics) {
    this.versionDao = versionDao;
    this.s3Client = s3Client;
    this.bucket = bucket;
    this.dryRun = dryRun;
    this.metrics = metrics;
  }

  @Override
  public Void handleRequest(ScheduledEvent event, Context ctx) {
    // Top-level invocation context (Story 7.1). Cleared in finally so subsequent invocations
    // on a warm container don't inherit stale keys from this one.
    LoggingUtils.appendKey("operation", "sweep");
    LoggingUtils.appendKey("dry_run", String.valueOf(dryRun));
    try {
      Instant cutoff = Instant.now().minus(ORPHAN_AGE);
      int processed = 0;

      while (processed < MAX_PER_INVOCATION) {
        List<Version> orphans = versionDao.findOrphans(BATCH_SIZE, cutoff);
        if (orphans.isEmpty()) break;

        for (Version v : orphans) {
          if (processed >= MAX_PER_INVOCATION) break;
          processOne(v);
          processed++;
        }

        // If the DAO returned fewer than BATCH_SIZE, there's nothing more to scan in this run.
        if (orphans.size() < BATCH_SIZE) break;
      }

      LoggingUtils.appendKey("processed", String.valueOf(processed));
      logger.info("sweep_summary");
      return null;
    } finally {
      LoggingUtils.removeKey("operation");
      LoggingUtils.removeKey("dry_run");
      LoggingUtils.removeKey("processed");
    }
  }

  private void processOne(Version v) {
    VersionStatus targetStatus;
    String reason;

    try {
      HeadObjectResponse head =
          s3Client.headObject(
              req -> req.bucket(bucket).key(v.getS3Key()).checksumMode(ChecksumMode.ENABLED));
      if (matchesChecksum(head, v)) {
        targetStatus = VersionStatus.READY;
        reason = "matching-upload";
      } else {
        targetStatus = VersionStatus.FAILED;
        reason = "checksum-mismatch";
      }
    } catch (NoSuchKeyException e) {
      targetStatus = VersionStatus.FAILED;
      reason = "no-upload";
    }

    // Per-orphan keys appended so the audit + (optional) conflict log lines carry full context.
    LoggingUtils.appendKey("model_name", v.getModelName());
    LoggingUtils.appendKey("version", v.getMajor() + "." + v.getMinor());
    LoggingUtils.appendKey("from_status", VersionStatus.PENDING.name());
    LoggingUtils.appendKey("to_status", targetStatus.name());
    LoggingUtils.appendKey("reason", reason);

    try {
      logger.info(dryRun ? "sweep_action_dry_run" : "sweep_action");

      if (dryRun) {
        // No DDB write, no metric — dry-run must not pollute CloudWatch alarms.
        return;
      }

      try {
        versionDao.updateStatus(
            v.getModelName(), v.getMajor(), v.getMinor(), targetStatus, VersionStatus.PENDING);
      } catch (VersionConflictException e) {
        // Lost a race against ConfirmVersion or another sweeper invocation — the row is no
        // longer PENDING. That's the desired terminal state; nothing to do.
        LoggingUtils.appendKey("outcome", "conflict");
        logger.warn("sweep_conflict status-changed-during-update");
        metrics.recordVersionConflict("sweep");
        return;
      }

      LoggingUtils.appendKey("outcome", targetStatus.name());
      metrics.recordOrphanSwept(targetStatus.name());
    } finally {
      LoggingUtils.removeKey("model_name");
      LoggingUtils.removeKey("version");
      LoggingUtils.removeKey("from_status");
      LoggingUtils.removeKey("to_status");
      LoggingUtils.removeKey("reason");
      LoggingUtils.removeKey("outcome");
    }
  }

  /**
   * If the row was created with a bound checksum (Story 4.7), require the S3 object's stored
   * SHA-256 to match exactly. If the row has no stored checksum, we have no way to verify the bytes
   * — accept the presence of the object as good-enough and flip to READY.
   */
  private boolean matchesChecksum(HeadObjectResponse head, Version v) {
    String stored = v.getChecksumSha256();
    if (stored == null || stored.isBlank()) return true;
    return stored.equals(head.checksumSHA256());
  }
}
