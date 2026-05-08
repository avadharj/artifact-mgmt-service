package com.anthropic.artifactmgmt.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.artifactmgmt.exception.AccessDeniedException;
import com.anthropic.artifactmgmt.exception.ModelAlreadyExistsException;
import com.anthropic.artifactmgmt.exception.ModelNotFoundException;
import com.anthropic.artifactmgmt.exception.VersionConflictException;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.PaginatedResult;
import java.net.URI;
import java.util.ArrayList;
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
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

@Testcontainers
class ModelDaoTest {

  private static final String TABLE_NAME = "models-test";

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> DYNAMO =
      new GenericContainer<>("amazon/dynamodb-local:latest")
          .withExposedPorts(8000)
          .withCommand("-jar", "DynamoDBLocal.jar", "-inMemory", "-sharedDb");

  private ModelDao dao;

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
                    .attributeName("modelName")
                    .attributeType(ScalarAttributeType.S)
                    .build())
            .keySchema(
                KeySchemaElement.builder().attributeName("modelName").keyType(KeyType.HASH).build())
            .build());

    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
    dao = new ModelDao(enhanced, TABLE_NAME);
  }

  private Model model(String name) {
    return Model.builder()
        .modelName(name)
        .owner("alice")
        .frameworkHint("pytorch")
        .description("test model")
        .latestMajor(0)
        .latestMinor(-1)
        .status("ACTIVE")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-01T00:00:00Z")
        .build();
  }

  @Test
  void givenNewModel_whenPutIfNotExists_thenReturnsModel() {
    Model result = dao.putIfNotExists(model("fraud-detector"));
    assertThat(result.getModelName()).isEqualTo("fraud-detector");
  }

  @Test
  void givenExistingModel_whenPutIfNotExists_thenThrowsModelAlreadyExistsException() {
    dao.putIfNotExists(model("fraud-detector"));
    assertThatThrownBy(() -> dao.putIfNotExists(model("fraud-detector")))
        .isInstanceOf(ModelAlreadyExistsException.class)
        .hasMessageContaining("fraud-detector");
  }

  @Test
  void givenExistingModel_whenGet_thenReturnsModel() {
    dao.putIfNotExists(model("fraud-detector"));
    Optional<Model> result = dao.get("fraud-detector");
    assertThat(result).isPresent();
    assertThat(result.get().getModelName()).isEqualTo("fraud-detector");
  }

  @Test
  void givenMissingModel_whenGet_thenReturnsEmpty() {
    Optional<Model> result = dao.get("does-not-exist");
    assertThat(result).isEmpty();
  }

  @Test
  void givenNoModels_whenList_thenReturnsEmptyPage() {
    PaginatedResult<Model> result = dao.list(10, null, false);
    assertThat(result.items()).isEmpty();
    assertThat(result.nextPageToken()).isNull();
  }

  @Test
  void givenManyModels_whenListWithSmallLimit_thenPaginatesAcrossThreePages() {
    for (int i = 0; i < 7; i++) {
      dao.putIfNotExists(model("model-" + i));
    }

    // includeDeleted=true avoids a filter expression so DDB returns exactly `limit` items per page
    PaginatedResult<Model> page1 = dao.list(3, null, true);
    assertThat(page1.items()).hasSize(3);
    assertThat(page1.nextPageToken()).isNotNull();

    PaginatedResult<Model> page2 = dao.list(3, page1.nextPageToken(), true);
    assertThat(page2.items()).hasSize(3);
    assertThat(page2.nextPageToken()).isNotNull();

    PaginatedResult<Model> page3 = dao.list(3, page2.nextPageToken(), true);
    assertThat(page3.items()).hasSize(1);

    List<Model> all = new ArrayList<>();
    all.addAll(page1.items());
    all.addAll(page2.items());
    all.addAll(page3.items());
    assertThat(all).hasSize(7);
  }

  @Test
  void givenPaginationToken_thenTokenIsOpaqueBase64() {
    for (int i = 0; i < 5; i++) {
      dao.putIfNotExists(model("model-" + i));
    }
    PaginatedResult<Model> first = dao.list(2, null, false);
    String token = first.nextPageToken();
    assertThat(token).isNotNull();
    // Must be valid base64 and must not expose Java SDK class names or raw DDB type wrappers
    byte[] decoded = java.util.Base64.getDecoder().decode(token);
    String decodedStr = new String(decoded);
    assertThat(decodedStr).doesNotContain("AttributeValue");
    assertThat(decodedStr).doesNotContain("software.amazon");
    // Token must be usable as a cursor — decode and use it to retrieve the next page
    PaginatedResult<Model> second = dao.list(2, token, false);
    assertThat(second.items()).hasSize(2);
  }

  @Test
  void givenOwnerMatch_whenSoftDelete_thenModelIsDeleted() {
    dao.putIfNotExists(model("fraud-detector"));
    dao.softDelete("fraud-detector", "alice", false);
    Optional<Model> result = dao.get("fraud-detector");
    assertThat(result).isPresent();
    assertThat(result.get().getStatus()).isEqualTo("DELETED");
  }

  @Test
  void givenWrongOwner_whenSoftDelete_thenThrowsAccessDeniedException() {
    dao.putIfNotExists(model("fraud-detector"));
    assertThatThrownBy(() -> dao.softDelete("fraud-detector", "mallory", false))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void givenAlreadyDeleted_whenSoftDelete_thenIdempotentRegardlessOfCaller() {
    dao.putIfNotExists(model("fraud-detector"));
    dao.softDelete("fraud-detector", "alice", false);
    // Any caller (including non-owners) should not throw on already-deleted model
    dao.softDelete("fraud-detector", "alice", false);
    dao.softDelete("fraud-detector", "mallory", false);
    Optional<Model> result = dao.get("fraud-detector");
    assertThat(result.get().getStatus()).isEqualTo("DELETED");
  }

  @Test
  void givenAdminCaller_whenSoftDelete_thenSucceedsRegardlessOfOwner() {
    dao.putIfNotExists(model("fraud-detector"));
    dao.softDelete("fraud-detector", "admin-user", true);
    Optional<Model> result = dao.get("fraud-detector");
    assertThat(result.get().getStatus()).isEqualTo("DELETED");
  }

  @Test
  void givenMissingModel_whenSoftDelete_thenThrowsModelNotFoundException() {
    assertThatThrownBy(() -> dao.softDelete("missing", "alice", false))
        .isInstanceOf(ModelNotFoundException.class);
  }

  @Test
  void givenMatchingMajorVersion_whenUpdateLatestVersion_thenSucceeds() {
    dao.putIfNotExists(model("fraud-detector"));
    dao.updateLatestVersion("fraud-detector", 1, 0, 0);
    Optional<Model> result = dao.get("fraud-detector");
    assertThat(result.get().getLatestMajor()).isEqualTo(1);
    assertThat(result.get().getLatestMinor()).isEqualTo(0);
  }

  @Test
  void givenMismatchedMajorVersion_whenUpdateLatestVersion_thenThrowsVersionConflictException() {
    dao.putIfNotExists(model("fraud-detector"));
    assertThatThrownBy(() -> dao.updateLatestVersion("fraud-detector", 2, 0, 99))
        .isInstanceOf(VersionConflictException.class);
  }

  @Test
  void givenMissingModel_whenUpdateLatestVersion_thenThrowsModelNotFoundException() {
    assertThatThrownBy(() -> dao.updateLatestVersion("missing", 1, 0, 0))
        .isInstanceOf(ModelNotFoundException.class);
  }
}
