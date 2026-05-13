package com.anthropic.artifactmgmt.dao;

import com.anthropic.artifactmgmt.exception.AccessDeniedException;
import com.anthropic.artifactmgmt.exception.ModelAlreadyExistsException;
import com.anthropic.artifactmgmt.exception.ModelNotFoundException;
import com.anthropic.artifactmgmt.exception.VersionConflictException;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.PaginatedResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.enhanced.dynamodb.model.ScanEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

public class ModelDao {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String STATUS_DELETED = "DELETED";

  private final DynamoDbEnhancedClient client;
  private final DynamoDbTable<ModelRecord> table;

  public ModelDao(DynamoDbEnhancedClient client, String tableName) {
    this.client = client;
    this.table = client.table(tableName, TableSchema.fromBean(ModelRecord.class));
  }

  public Model putIfNotExists(Model model) throws ModelAlreadyExistsException {
    ModelRecord record = toRecord(model);
    Expression condition =
        Expression.builder().expression("attribute_not_exists(model_name)").build();
    try {
      table.putItem(
          PutItemEnhancedRequest.builder(ModelRecord.class)
              .item(record)
              .conditionExpression(condition)
              .build());
    } catch (ConditionalCheckFailedException e) {
      throw new ModelAlreadyExistsException(model.getModelName());
    }
    return model;
  }

  public Optional<Model> get(String modelName) {
    ModelRecord record = table.getItem(Key.builder().partitionValue(modelName).build());
    if (record == null) {
      return Optional.empty();
    }
    return Optional.of(toModel(record));
  }

  public PaginatedResult<Model> list(int limit, String pageToken, boolean includeDeleted) {
    Map<String, AttributeValue> exclusiveStartKey = decodePageToken(pageToken);

    ScanEnhancedRequest.Builder requestBuilder = ScanEnhancedRequest.builder().limit(limit);

    if (exclusiveStartKey != null) {
      requestBuilder.exclusiveStartKey(exclusiveStartKey);
    }

    if (!includeDeleted) {
      requestBuilder.filterExpression(
          Expression.builder()
              .expression("#s <> :deleted")
              .putExpressionName("#s", "status")
              .putExpressionValue(":deleted", AttributeValue.fromS(STATUS_DELETED))
              .build());
    }

    List<Model> items = new ArrayList<>();
    String nextToken = null;

    for (Page<ModelRecord> page : table.scan(requestBuilder.build())) {
      for (ModelRecord record : page.items()) {
        items.add(toModel(record));
      }
      if (page.lastEvaluatedKey() != null && !page.lastEvaluatedKey().isEmpty()) {
        nextToken = encodePageToken(page.lastEvaluatedKey());
      }
      break; // one page per call
    }

    return new PaginatedResult<>(items, nextToken);
  }

  public void softDelete(String modelName, String expectedOwner, boolean isAdmin)
      throws ModelNotFoundException, AccessDeniedException {
    ModelRecord existing = table.getItem(Key.builder().partitionValue(modelName).build());
    if (existing == null) {
      throw new ModelNotFoundException(modelName);
    }
    if (STATUS_DELETED.equals(existing.getStatus())) {
      return; // idempotent
    }

    ModelRecord updated =
        ModelRecord.builder()
            .modelName(existing.getModelName())
            .owner(existing.getOwner())
            .frameworkHint(existing.getFrameworkHint())
            .description(existing.getDescription())
            .latestMajor(existing.getLatestMajor())
            .latestMinor(existing.getLatestMinor())
            .status(STATUS_DELETED)
            .createdAt(existing.getCreatedAt())
            .updatedAt(Instant.now().toString())
            .build();

    // Admin callers bypass ownership check; regular callers must own the model.
    Expression condition =
        Expression.builder()
            .expression("#owner = :owner OR :isAdmin = :true")
            .putExpressionName("#owner", "owner")
            .putExpressionValue(":owner", AttributeValue.fromS(expectedOwner))
            .putExpressionValue(":isAdmin", AttributeValue.fromBool(isAdmin))
            .putExpressionValue(":true", AttributeValue.fromBool(true))
            .build();

    try {
      table.putItem(
          PutItemEnhancedRequest.builder(ModelRecord.class)
              .item(updated)
              .conditionExpression(condition)
              .build());
    } catch (ConditionalCheckFailedException e) {
      throw new AccessDeniedException(
          "Caller " + expectedOwner + " does not own model " + modelName);
    }
  }

  public void updateLatestVersion(String modelName, int newMajor, int newMinor, int expectedMajor)
      throws VersionConflictException {
    ModelRecord record = table.getItem(Key.builder().partitionValue(modelName).build());
    if (record == null) {
      throw new ModelNotFoundException(modelName);
    }

    ModelRecord updated =
        ModelRecord.builder()
            .modelName(record.getModelName())
            .owner(record.getOwner())
            .frameworkHint(record.getFrameworkHint())
            .description(record.getDescription())
            .latestMajor(newMajor)
            .latestMinor(newMinor)
            .status(record.getStatus())
            .createdAt(record.getCreatedAt())
            .updatedAt(Instant.now().toString())
            .build();

    Expression condition =
        Expression.builder()
            .expression("latest_major = :expectedMajor")
            .putExpressionValue(
                ":expectedMajor", AttributeValue.fromN(String.valueOf(expectedMajor)))
            .build();

    try {
      table.putItem(
          PutItemEnhancedRequest.builder(ModelRecord.class)
              .item(updated)
              .conditionExpression(condition)
              .build());
    } catch (ConditionalCheckFailedException e) {
      // Re-read to get the actual current state so callers can report accurate conflict details.
      ModelRecord current = table.getItem(Key.builder().partitionValue(modelName).build());
      int actualMajor = current != null ? current.getLatestMajor() : expectedMajor;
      int actualMinor = current != null ? current.getLatestMinor() : -1;
      throw new VersionConflictException(actualMajor, actualMinor);
    }
  }

  private static ModelRecord toRecord(Model model) {
    return ModelRecord.builder()
        .modelName(model.getModelName())
        .owner(model.getOwner())
        .frameworkHint(model.getFrameworkHint())
        .description(model.getDescription())
        .latestMajor(model.getLatestMajor())
        .latestMinor(model.getLatestMinor())
        .status(model.getStatus())
        .createdAt(model.getCreatedAt())
        .updatedAt(model.getUpdatedAt())
        .build();
  }

  private static Model toModel(ModelRecord record) {
    return Model.builder()
        .modelName(record.getModelName())
        .owner(record.getOwner())
        .frameworkHint(record.getFrameworkHint())
        .description(record.getDescription())
        .latestMajor(record.getLatestMajor())
        .latestMinor(record.getLatestMinor())
        .status(record.getStatus())
        .createdAt(record.getCreatedAt())
        .updatedAt(record.getUpdatedAt())
        .build();
  }

  // Page token format: base64( JSON{ "key": {"type": "S"|"N", "value": "..."} } )
  // Using a typed wrapper to avoid assuming all key attributes are strings.
  private static Map<String, AttributeValue> decodePageToken(String pageToken) {
    if (pageToken == null || pageToken.isEmpty()) {
      return null;
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(pageToken);
      Map<String, Map<String, String>> raw =
          MAPPER.readValue(decoded, new TypeReference<Map<String, Map<String, String>>>() {});
      Map<String, AttributeValue> key = new HashMap<>();
      raw.forEach(
          (k, v) -> {
            String type = v.get("type");
            String value = v.get("value");
            if ("N".equals(type)) {
              key.put(k, AttributeValue.fromN(value));
            } else {
              key.put(k, AttributeValue.fromS(value));
            }
          });
      return key;
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid page token", e);
    }
  }

  private static String encodePageToken(Map<String, AttributeValue> lastEvaluatedKey) {
    try {
      Map<String, Map<String, String>> raw = new HashMap<>();
      lastEvaluatedKey.forEach(
          (k, v) -> {
            Map<String, String> entry = new HashMap<>();
            if (v.n() != null) {
              entry.put("type", "N");
              entry.put("value", v.n());
            } else {
              entry.put("type", "S");
              entry.put("value", v.s());
            }
            raw.put(k, entry);
          });
      byte[] encoded = MAPPER.writeValueAsBytes(raw);
      return Base64.getEncoder().encodeToString(encoded);
    } catch (Exception e) {
      throw new RuntimeException("Failed to encode page token", e);
    }
  }
}
