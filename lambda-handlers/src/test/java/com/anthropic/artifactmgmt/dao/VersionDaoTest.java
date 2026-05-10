package com.anthropic.artifactmgmt.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.artifactmgmt.exception.VersionConflictException;
import com.anthropic.artifactmgmt.model.PaginatedResult;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionStatus;
import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
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

@Testcontainers
class VersionDaoTest {

  private static final String TABLE_NAME = "versions-test";

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> DYNAMO =
      new GenericContainer<>("amazon/dynamodb-local:latest")
          .withExposedPorts(8000)
          .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

  private DynamoDbClient dynamo;
  private VersionDao dao;

  @BeforeEach
  void setUp() {
    String endpoint = "http://localhost:" + DYNAMO.getMappedPort(8000);
    dynamo =
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
                    .projection(
                        Projection.builder()
                            .projectionType(ProjectionType.INCLUDE)
                            .nonKeyAttributes(
                                "s3_key", "idempotency_key", "model_name", "version_key")
                            .build())
                    .build())
            .build());

    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
    dao =
        new VersionDao(
            enhanced.table(TABLE_NAME, TableSchema.fromBean(VersionRecord.class)),
            dynamo,
            TABLE_NAME);
  }

  private Version version(String modelName, int major, int minor, VersionStatus status) {
    return Version.builder()
        .modelName(modelName)
        .major(major)
        .minor(minor)
        .versionKey(VersionKey.encode(major, minor))
        .s3Key(modelName + "/v" + major + "." + minor + "/weights.bin")
        .status(status)
        .depSnapshot("{\"python\":\"3.11\"}")
        .trainingMetadata("{\"epochs\":10}")
        .idempotencyKey("idem-" + modelName + "-" + major + "-" + minor)
        .ttl(Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond())
        .createdAt(Instant.now().minus(1, ChronoUnit.HOURS).toString())
        .createdBy("alice")
        .build();
  }

  // ── put and get ──────────────────────────────────────────────────────────────

  @Test
  void givenNewVersion_whenPut_thenGetReturnsIt() {
    Version v = version("fraud-detector", 1, 0, VersionStatus.PENDING);
    dao.put(v);

    Optional<Version> result = dao.get("fraud-detector", 1, 0);
    assertThat(result).isPresent();
    assertThat(result.get().getModelName()).isEqualTo("fraud-detector");
    assertThat(result.get().getMajor()).isEqualTo(1);
    assertThat(result.get().getMinor()).isEqualTo(0);
    assertThat(result.get().getStatus()).isEqualTo(VersionStatus.PENDING);
    assertThat(result.get().getS3Key()).contains("fraud-detector");
    assertThat(result.get().getDepSnapshot()).contains("python");
  }

  @Test
  void givenMissingVersion_whenGet_thenReturnsEmpty() {
    Optional<Version> result = dao.get("no-such-model", 1, 0);
    assertThat(result).isEmpty();
  }

  // ── findByIdempotencyKey ──────────────────────────────────────────────────

  @Test
  void givenVersionWithIdempotencyKey_whenFindByIdempotencyKey_thenReturnsIt() {
    Version v = version("fraud-detector", 1, 0, VersionStatus.PENDING);
    dao.put(v);

    Optional<Version> result = dao.findByIdempotencyKey("idem-fraud-detector-1-0");
    assertThat(result).isPresent();
    assertThat(result.get().getVersionKey()).isEqualTo("0001.0000");
  }

  @Test
  void givenNoMatchingKey_whenFindByIdempotencyKey_thenReturnsEmpty() {
    Optional<Version> result = dao.findByIdempotencyKey("key-that-does-not-exist");
    assertThat(result).isEmpty();
  }

  // ── list ─────────────────────────────────────────────────────────────────

  @Test
  void givenVersions_whenList_thenFiltersCorrectly() {
    dao.put(version("model-a", 1, 0, VersionStatus.READY));
    dao.put(version("model-a", 1, 1, VersionStatus.PENDING));
    dao.put(version("model-a", 1, 2, VersionStatus.DELETED));

    // includePending=false, includeDeleted=false → only READY
    PaginatedResult<Version> result = dao.list("model-a", 10, null, false, false);
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).getStatus()).isEqualTo(VersionStatus.READY);

    // includePending=true → READY + PENDING
    PaginatedResult<Version> result2 = dao.list("model-a", 10, null, true, false);
    assertThat(result2.items()).hasSize(2);

    // includeDeleted=true → all 3
    PaginatedResult<Version> result3 = dao.list("model-a", 10, null, true, true);
    assertThat(result3.items()).hasSize(3);
  }

  @Test
  void givenMultipleVersions_whenListWithPagination_thenPageTokenWorks() {
    for (int i = 0; i < 5; i++) {
      dao.put(version("paginate-model", 1, i, VersionStatus.READY));
    }

    PaginatedResult<Version> page1 = dao.list("paginate-model", 2, null, true, true);
    assertThat(page1.items()).hasSize(2);
    assertThat(page1.nextPageToken()).isNotNull();

    PaginatedResult<Version> page2 =
        dao.list("paginate-model", 2, page1.nextPageToken(), true, true);
    assertThat(page2.items()).hasSize(2);

    PaginatedResult<Version> page3 =
        dao.list("paginate-model", 2, page2.nextPageToken(), true, true);
    assertThat(page3.items()).hasSize(1);
    assertThat(page3.nextPageToken()).isNull();
  }

  // ── findLatestReady ───────────────────────────────────────────────────────

  @Test
  void givenReadyVersions_whenFindLatestReady_thenReturnsMostRecent() {
    dao.put(version("churn-model", 1, 0, VersionStatus.READY));
    dao.put(version("churn-model", 1, 1, VersionStatus.READY));
    dao.put(version("churn-model", 1, 2, VersionStatus.PENDING));

    Optional<Version> result = dao.findLatestReady("churn-model");
    assertThat(result).isPresent();
    // 0001.0001 sorts after 0001.0000 in descending order
    assertThat(result.get().getMinor()).isEqualTo(1);
    assertThat(result.get().getStatus()).isEqualTo(VersionStatus.READY);
  }

  @Test
  void givenNoReadyVersions_whenFindLatestReady_thenReturnsEmpty() {
    dao.put(version("empty-model", 1, 0, VersionStatus.PENDING));

    Optional<Version> result = dao.findLatestReady("empty-model");
    assertThat(result).isEmpty();
  }

  @Test
  void givenNoVersions_whenFindLatestReady_thenReturnsEmpty() {
    Optional<Version> result = dao.findLatestReady("nonexistent-model");
    assertThat(result).isEmpty();
  }

  // ── updateStatus ─────────────────────────────────────────────────────────

  @Test
  void givenPendingVersion_whenUpdateStatusToReady_thenSucceeds() {
    dao.put(version("confirm-model", 1, 0, VersionStatus.PENDING));

    dao.updateStatus("confirm-model", 1, 0, VersionStatus.READY, VersionStatus.PENDING);

    Optional<Version> result = dao.get("confirm-model", 1, 0);
    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo(VersionStatus.READY);
  }

  @Test
  void givenWrongExpectedStatus_whenUpdateStatus_thenThrowsVersionConflictException() {
    dao.put(version("conflict-model", 1, 0, VersionStatus.READY));

    assertThatThrownBy(
            () ->
                dao.updateStatus(
                    "conflict-model", 1, 0, VersionStatus.DELETED, VersionStatus.PENDING))
        .isInstanceOf(VersionConflictException.class);
  }

  // ── findOrphans ───────────────────────────────────────────────────────────

  @Test
  void givenOldPendingVersions_whenFindOrphans_thenReturnsThem() {
    // Create a version with an old created_at timestamp
    Version oldPending =
        Version.builder()
            .modelName("orphan-model")
            .major(1)
            .minor(0)
            .versionKey(VersionKey.encode(1, 0))
            .s3Key("orphan-model/v1.0/weights.bin")
            .status(VersionStatus.PENDING)
            .depSnapshot("{}")
            .trainingMetadata("{}")
            .idempotencyKey("idem-orphan-old")
            .ttl(Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond())
            .createdAt(Instant.now().minus(48, ChronoUnit.HOURS).toString())
            .createdBy("alice")
            .build();
    dao.put(oldPending);

    // A recent PENDING version that should NOT be returned
    Version recentPending =
        Version.builder()
            .modelName("orphan-model")
            .major(1)
            .minor(1)
            .versionKey(VersionKey.encode(1, 1))
            .s3Key("orphan-model/v1.1/weights.bin")
            .status(VersionStatus.PENDING)
            .depSnapshot("{}")
            .trainingMetadata("{}")
            .idempotencyKey("idem-orphan-new")
            .ttl(Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond())
            .createdAt(Instant.now().toString())
            .createdBy("alice")
            .build();
    dao.put(recentPending);

    Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
    List<Version> orphans = dao.findOrphans(10, cutoff);

    assertThat(orphans).hasSize(1);
    assertThat(orphans.get(0).getMinor()).isEqualTo(0);
  }

  @Test
  void givenReadyVersionsOnly_whenFindOrphans_thenReturnsEmpty() {
    dao.put(version("ready-model", 1, 0, VersionStatus.READY));

    Instant cutoff = Instant.now().minus(1, ChronoUnit.HOURS);
    List<Version> orphans = dao.findOrphans(10, cutoff);
    assertThat(orphans).isEmpty();
  }
}
