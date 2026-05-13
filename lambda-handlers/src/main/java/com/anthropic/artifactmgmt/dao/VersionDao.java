package com.anthropic.artifactmgmt.dao;

import com.anthropic.artifactmgmt.model.PaginatedResult;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionStatus;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbIndex;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.Page;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

public class VersionDao {

  private static final int FIND_LATEST_READY_LIMIT = 10;

  private final DynamoDbTable<VersionRecord> table;
  private final DynamoDbClient dynamoDbClient;
  private final String tableName;

  public VersionDao(DynamoDbClient dynamoDbClient, String tableName) {
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    this.table = enhanced.table(tableName, TableSchema.fromBean(VersionRecord.class));
  }

  VersionDao(DynamoDbTable<VersionRecord> table, DynamoDbClient dynamoDbClient, String tableName) {
    this.table = table;
    this.dynamoDbClient = dynamoDbClient;
    this.tableName = tableName;
  }

  public Version put(Version v) {
    VersionRecord record = toRecord(v);
    table.putItem(record);
    return v;
  }

  public Optional<Version> get(String modelName, int major, int minor) {
    Key key =
        Key.builder().partitionValue(modelName).sortValue(VersionKey.encode(major, minor)).build();
    VersionRecord record = table.getItem(key);
    return Optional.ofNullable(record).map(this::toDomain);
  }

  public Optional<Version> findByIdempotencyKey(String idempotencyKey) {
    DynamoDbIndex<VersionRecord> gsi = table.index("idempotency-gsi");
    QueryEnhancedRequest request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(idempotencyKey).build()))
            .limit(1)
            .build();
    for (Page<VersionRecord> page : gsi.query(request)) {
      List<VersionRecord> items = page.items();
      if (!items.isEmpty()) {
        return Optional.of(toDomain(items.get(0)));
      }
    }
    return Optional.empty();
  }

  public PaginatedResult<Version> list(
      String modelName,
      int limit,
      String pageToken,
      boolean includePending,
      boolean includeDeleted) {

    QueryEnhancedRequest.Builder reqBuilder =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(modelName).build()))
            .limit(limit);

    if (pageToken != null) {
      reqBuilder.exclusiveStartKey(decodePageToken(pageToken));
    }

    QueryEnhancedRequest request = reqBuilder.build();
    List<Version> results = new ArrayList<>();
    String nextToken = null;

    for (Page<VersionRecord> page : table.query(request)) {
      for (VersionRecord r : page.items()) {
        VersionStatus status = VersionStatus.valueOf(r.getStatus());
        if (!includePending && status == VersionStatus.PENDING) continue;
        if (!includeDeleted && status == VersionStatus.DELETED) continue;
        results.add(toDomain(r));
        if (results.size() >= limit) {
          break;
        }
      }
      if (results.size() >= limit && page.lastEvaluatedKey() != null) {
        nextToken = encodePageToken(page.lastEvaluatedKey());
      }
      break;
    }

    return new PaginatedResult<>(results, nextToken);
  }

  public Optional<Version> findLatestReady(String modelName) {
    QueryEnhancedRequest request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.keyEqualTo(Key.builder().partitionValue(modelName).build()))
            .scanIndexForward(false)
            .limit(FIND_LATEST_READY_LIMIT)
            .filterExpression(
                software.amazon.awssdk.enhanced.dynamodb.Expression.builder()
                    .expression("#st = :ready")
                    .putExpressionName("#st", "status")
                    .putExpressionValue(":ready", AttributeValue.fromS(VersionStatus.READY.name()))
                    .build())
            .build();

    for (Page<VersionRecord> page : table.query(request)) {
      List<VersionRecord> items = page.items();
      if (!items.isEmpty()) {
        return Optional.of(toDomain(items.get(0)));
      }
      // If FIND_LATEST_READY_LIMIT items were all non-READY, continue paging
      if (page.lastEvaluatedKey() == null) {
        break;
      }
    }
    return Optional.empty();
  }

  public void updateStatus(
      String modelName,
      int major,
      int minor,
      VersionStatus newStatus,
      VersionStatus expectedStatus) {

    String versionKey = VersionKey.encode(major, minor);
    software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest updateRequest =
        software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest.builder()
            .tableName(this.tableName)
            .key(
                Map.of(
                    "model_name", AttributeValue.fromS(modelName),
                    "version_key", AttributeValue.fromS(versionKey)))
            .updateExpression("SET #st = :new_status")
            .conditionExpression("#st = :expected_status")
            .expressionAttributeNames(Map.of("#st", "status"))
            .expressionAttributeValues(
                Map.of(
                    ":new_status", AttributeValue.fromS(newStatus.name()),
                    ":expected_status", AttributeValue.fromS(expectedStatus.name())))
            .build();

    try {
      dynamoDbClient.updateItem(updateRequest);
    } catch (ConditionalCheckFailedException e) {
      throw new com.anthropic.artifactmgmt.exception.VersionConflictException(
          "Status update failed: expected "
              + expectedStatus
              + " for "
              + modelName
              + " v"
              + major
              + "."
              + minor);
    }
  }

  public List<Version> findOrphans(int batchSize, Instant olderThan) {
    DynamoDbIndex<VersionRecord> gsi = table.index("status-created-gsi");
    QueryEnhancedRequest request =
        QueryEnhancedRequest.builder()
            .queryConditional(
                QueryConditional.sortLessThan(
                    Key.builder()
                        .partitionValue(VersionStatus.PENDING.name())
                        .sortValue(olderThan.toString())
                        .build()))
            .limit(batchSize)
            .build();

    List<Version> results = new ArrayList<>();
    for (Page<VersionRecord> page : gsi.query(request)) {
      for (VersionRecord r : page.items()) {
        results.add(toDomain(r));
        if (results.size() >= batchSize) break;
      }
      break;
    }
    return results;
  }

  private VersionRecord toRecord(Version v) {
    return VersionRecord.builder()
        .modelName(v.getModelName())
        .versionKey(v.getVersionKey())
        .major(v.getMajor())
        .minor(v.getMinor())
        .s3Key(v.getS3Key())
        .status(v.getStatus().name())
        .depSnapshot(v.getDepSnapshot())
        .trainingMetadata(v.getTrainingMetadata())
        .idempotencyKey(v.getIdempotencyKey())
        .ttl(v.getTtl())
        .createdAt(v.getCreatedAt())
        .createdBy(v.getCreatedBy())
        .checksumSha256(v.getChecksumSha256())
        .build();
  }

  private Version toDomain(VersionRecord r) {
    int[] parts = VersionKey.decode(r.getVersionKey());
    return Version.builder()
        .modelName(r.getModelName())
        .versionKey(r.getVersionKey())
        .major(parts[0])
        .minor(parts[1])
        .s3Key(r.getS3Key())
        .status(VersionStatus.valueOf(r.getStatus()))
        .depSnapshot(r.getDepSnapshot())
        .trainingMetadata(r.getTrainingMetadata())
        .idempotencyKey(r.getIdempotencyKey())
        .ttl(r.getTtl())
        .createdAt(r.getCreatedAt())
        .createdBy(r.getCreatedBy())
        .checksumSha256(r.getChecksumSha256())
        .build();
  }

  private String encodePageToken(Map<String, AttributeValue> key) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      java.util.Map<String, java.util.Map<String, String>> encoded =
          new java.util.LinkedHashMap<>();
      for (Map.Entry<String, AttributeValue> entry : key.entrySet()) {
        java.util.Map<String, String> typed = new java.util.LinkedHashMap<>();
        if (entry.getValue().s() != null) {
          typed.put("type", "S");
          typed.put("value", entry.getValue().s());
        } else if (entry.getValue().n() != null) {
          typed.put("type", "N");
          typed.put("value", entry.getValue().n());
        }
        encoded.put(entry.getKey(), typed);
      }
      byte[] json = mapper.writeValueAsBytes(encoded);
      return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (Exception e) {
      throw new RuntimeException("Failed to encode page token", e);
    }
  }

  private Map<String, AttributeValue> decodePageToken(String token) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      byte[] json = java.util.Base64.getUrlDecoder().decode(token);
      java.util.Map<String, java.util.Map<String, String>> decoded =
          mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
      java.util.Map<String, AttributeValue> result = new java.util.LinkedHashMap<>();
      for (Map.Entry<String, java.util.Map<String, String>> entry : decoded.entrySet()) {
        String type = entry.getValue().get("type");
        String value = entry.getValue().get("value");
        if ("S".equals(type)) {
          result.put(entry.getKey(), AttributeValue.fromS(value));
        } else if ("N".equals(type)) {
          result.put(entry.getKey(), AttributeValue.fromN(value));
        }
      }
      return result;
    } catch (Exception e) {
      throw new RuntimeException("Failed to decode page token", e);
    }
  }
}
