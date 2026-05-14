package com.anthropic.artifactmgmt.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anthropic.artifactmgmt.dao.VersionDao;
import com.anthropic.artifactmgmt.dao.VersionKey;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionStatus;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Testcontainers
class SweeperHandlerTest {

  private static final String TABLE_NAME = "versions-sweeper-test";
  private static final String BUCKET = "test-bucket";

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> DYNAMO =
      new GenericContainer<>("amazon/dynamodb-local:latest")
          .withExposedPorts(8000)
          .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

  private VersionDao versionDao;
  private S3Client mockS3;
  private MetricsPublisher mockMetrics;

  @BeforeEach
  void setUp() {
    String endpoint = "http://localhost:" + DYNAMO.getMappedPort(8000);
    DynamoDbClient dynamo =
        DynamoDbClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build();

    try {
      dynamo.deleteTable(b -> b.tableName(TABLE_NAME));
    } catch (Exception ignored) {
    }

    dynamo.createTable(
        CreateTableRequest.builder()
            .tableName(TABLE_NAME)
            .billingMode(BillingMode.PAY_PER_REQUEST)
            .attributeDefinitions(
                AttributeDefinition.builder()
                    .attributeName("model_name")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("version_key")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("idempotency_key")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("status")
                    .attributeType(ScalarAttributeType.S)
                    .build(),
                AttributeDefinition.builder()
                    .attributeName("created_at")
                    .attributeType(ScalarAttributeType.S)
                    .build())
            .keySchema(
                KeySchemaElement.builder()
                    .attributeName("model_name")
                    .keyType(KeyType.HASH)
                    .build(),
                KeySchemaElement.builder()
                    .attributeName("version_key")
                    .keyType(KeyType.RANGE)
                    .build())
            .globalSecondaryIndexes(
                GlobalSecondaryIndex.builder()
                    .indexName("idempotency-gsi")
                    .keySchema(
                        KeySchemaElement.builder()
                            .attributeName("idempotency_key")
                            .keyType(KeyType.HASH)
                            .build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .build(),
                GlobalSecondaryIndex.builder()
                    .indexName("status-created-gsi")
                    .keySchema(
                        KeySchemaElement.builder()
                            .attributeName("status")
                            .keyType(KeyType.HASH)
                            .build(),
                        KeySchemaElement.builder()
                            .attributeName("created_at")
                            .keyType(KeyType.RANGE)
                            .build())
                    .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                    .build())
            .build());

    versionDao = new VersionDao(dynamo, TABLE_NAME);
    mockS3 = mock(S3Client.class);
    mockMetrics = mock(MetricsPublisher.class);
  }

  private Version pendingOrphan(String modelName, int major, int minor, String checksum) {
    return Version.builder()
        .modelName(modelName)
        .major(major)
        .minor(minor)
        .versionKey(VersionKey.encode(major, minor))
        .s3Key(modelName + "/v" + major + "." + minor + "/weights.bin")
        .status(VersionStatus.PENDING)
        .depSnapshot("{}")
        .trainingMetadata("{}")
        .idempotencyKey("idem-" + modelName + "-" + major + "-" + minor)
        // Sweeper looks for rows older than 24h. Put created_at well past that cutoff.
        .createdAt(Instant.now().minus(48, ChronoUnit.HOURS).toString())
        .createdBy("alice")
        .checksumSha256(checksum)
        .build();
  }

  @SuppressWarnings("unchecked")
  private HeadObjectResponse stubHead(String sha256) {
    HeadObjectResponse.Builder b = HeadObjectResponse.builder().contentLength(1024L);
    if (sha256 != null) b.checksumSHA256(sha256);
    HeadObjectResponse head = b.build();
    when(mockS3.headObject(any(Consumer.class))).thenReturn(head);
    return head;
  }

  // ── AC: matching upload → PENDING flipped to READY ────────────────────────

  @Test
  void givenOrphanWithMatchingUpload_whenSweep_thenStatusFlipsToReady() {
    versionDao.put(pendingOrphan("fraud-detector", 1, 0, "abc123sha"));
    stubHead("abc123sha");

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    assertThat(versionDao.get("fraud-detector", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.READY);
    verify(mockMetrics).recordOrphanSwept("READY");
  }

  // ── AC: checksum mismatch → FAILED ───────────────────────────────────────

  @Test
  void givenOrphanWithMismatchedChecksum_whenSweep_thenStatusFlipsToFailed() {
    versionDao.put(pendingOrphan("fraud-detector", 1, 0, "expected-sha"));
    stubHead("different-sha-on-s3");

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    assertThat(versionDao.get("fraud-detector", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.FAILED);
    verify(mockMetrics).recordOrphanSwept("FAILED");
  }

  // ── AC: S3 object missing → FAILED ────────────────────────────────────────

  @Test
  @SuppressWarnings("unchecked")
  void givenOrphanWithNoS3Upload_whenSweep_thenStatusFlipsToFailed() {
    versionDao.put(pendingOrphan("fraud-detector", 1, 0, null));
    when(mockS3.headObject(any(Consumer.class)))
        .thenThrow(NoSuchKeyException.builder().message("not found").build());

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    assertThat(versionDao.get("fraud-detector", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.FAILED);
    verify(mockMetrics).recordOrphanSwept("FAILED");
  }

  // ── AC: no stored checksum + bytes present → READY ────────────────────────

  @Test
  void givenOrphanWithoutStoredChecksum_whenSweep_thenAcceptsBytesAndFlipsToReady() {
    // Story 4.7: checksumSha256 is optional on CreateVersion. Sweeper accepts the upload as-is.
    versionDao.put(pendingOrphan("fraud-detector", 1, 0, null));
    stubHead(null);

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    assertThat(versionDao.get("fraud-detector", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.READY);
  }

  // ── AC: dry-run leaves DDB unchanged ──────────────────────────────────────

  @Test
  void givenDryRun_whenSweep_thenStatusUnchanged() {
    versionDao.put(pendingOrphan("fraud-detector", 1, 0, "abc123sha"));
    stubHead("abc123sha");

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, true, mockMetrics);
    sweeper.handleRequest(null, null);

    // Status stays PENDING; metric is NOT emitted on dry-run (only real status changes count).
    assertThat(versionDao.get("fraud-detector", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.PENDING);
    verify(mockMetrics, never()).recordOrphanSwept(anyString());
  }

  // ── AC: only orphans older than 24h are considered ───────────────────────

  @Test
  void givenRecentPending_whenSweep_thenSkipped() {
    Version recent =
        Version.builder()
            .modelName("fresh-model")
            .major(1)
            .minor(0)
            .versionKey(VersionKey.encode(1, 0))
            .s3Key("fresh-model/v1.0/weights.bin")
            .status(VersionStatus.PENDING)
            .depSnapshot("{}")
            .trainingMetadata("{}")
            .idempotencyKey("idem-fresh")
            // 1 hour old — well within the 24h grace window.
            .createdAt(Instant.now().minus(1, ChronoUnit.HOURS).toString())
            .createdBy("alice")
            .build();
    versionDao.put(recent);

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    assertThat(versionDao.get("fresh-model", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.PENDING);
    verify(mockMetrics, never()).recordOrphanSwept(anyString());
  }

  // ── AC: per-orphan metric emission ────────────────────────────────────────

  @Test
  void givenMultipleOrphans_whenSweep_thenEmitsOneMetricEach() {
    versionDao.put(pendingOrphan("model-a", 1, 0, "sha-a"));
    versionDao.put(pendingOrphan("model-b", 1, 0, "sha-b"));
    versionDao.put(pendingOrphan("model-c", 1, 0, "sha-c"));
    // All three S3 objects exist with matching checksums — the same mock returns the same head
    // for any key, which fine for this metric-count assertion (each orphan still gets its own
    // updateStatus and metric emission).
    stubHead("sha-a"); // model-a → READY
    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);

    sweeper.handleRequest(null, null);

    // 3 orphans processed → 3 metric emissions. Outcomes depend on per-row checksum match:
    // only model-a will match (the head stub returns sha-a). model-b and model-c → FAILED.
    verify(mockMetrics, times(3)).recordOrphanSwept(anyString());
    verify(mockMetrics).recordOrphanSwept("READY");
    verify(mockMetrics, times(2)).recordOrphanSwept("FAILED");
  }

  // ── Idempotency: re-running the sweep after a successful flip is a no-op ──

  @Test
  void givenAlreadyReadyVersion_whenSweepRunsAgain_thenStatusUnchangedAndNoMetric() {
    versionDao.put(pendingOrphan("fraud-detector", 1, 0, "abc"));
    stubHead("abc");

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null); // first run flips to READY
    sweeper.handleRequest(null, null); // second run should find no orphans

    // findOrphans queries status=PENDING, so the now-READY row isn't returned.
    verify(mockMetrics, times(1)).recordOrphanSwept("READY");
    assertThat(versionDao.get("fraud-detector", 1, 0).get().getStatus())
        .isEqualTo(VersionStatus.READY);
  }

  // ── findOrphans batch boundary: max 1000 per invocation is enforced ──────

  @Test
  void givenManyOrphans_whenSweep_thenCapsAt1000PerInvocation() {
    // 1005 orphans — verify only 1000 are processed in one invocation.
    // Trim down the runtime cost: assert via interaction-count rather than DB inspection.
    for (int i = 0; i < 1005; i++) {
      Version v =
          Version.builder()
              .modelName("bulk-model")
              .major(1)
              .minor(i)
              .versionKey(VersionKey.encode(1, i))
              .s3Key("bulk-model/v1." + i + "/weights.bin")
              .status(VersionStatus.PENDING)
              .depSnapshot("{}")
              .trainingMetadata("{}")
              .idempotencyKey("idem-bulk-" + i)
              .createdAt(Instant.now().minus(48, ChronoUnit.HOURS).toString())
              .createdBy("alice")
              .build();
      versionDao.put(v);
    }
    stubHead(null); // all uploads pass (no stored checksum on these rows)

    SweeperHandler sweeper = new SweeperHandler(versionDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    verify(mockMetrics, times(SweeperHandler.MAX_PER_INVOCATION)).recordOrphanSwept(anyString());
    // 5 rows remain PENDING for the next invocation.
    List<Version> remaining =
        versionDao.findOrphans(
            SweeperHandler.BATCH_SIZE, Instant.now().minus(24, ChronoUnit.HOURS));
    assertThat(remaining).isNotEmpty();
  }

  // ── Story 7.2: VersionConflict metric on race ────────────────────────────

  @Test
  void givenRaceOnUpdateStatus_whenSweep_thenEmitsVersionConflictMetricNotOrphanSwept() {
    // Simulate: findOrphans returns a PENDING row, but between that lookup and updateStatus,
    // ConfirmVersion (or another sweeper) flipped the row out of PENDING. The conditional
    // updateStatus throws VersionConflictException; the handler must emit
    // VersionConflict[operation=sweep] and skip the orphan-swept metric (the winner emits
    // its own signal).
    versionDao.put(pendingOrphan("race-model", 1, 0, null));
    stubHead(null);

    com.anthropic.artifactmgmt.dao.VersionDao spyDao = org.mockito.Mockito.spy(versionDao);
    org.mockito.Mockito.doThrow(
            new com.anthropic.artifactmgmt.exception.VersionConflictException("status changed"))
        .when(spyDao)
        .updateStatus(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());

    SweeperHandler sweeper = new SweeperHandler(spyDao, mockS3, BUCKET, false, mockMetrics);
    sweeper.handleRequest(null, null);

    verify(mockMetrics).recordVersionConflict("sweep");
    verify(mockMetrics, never()).recordOrphanSwept(anyString());
  }
}
