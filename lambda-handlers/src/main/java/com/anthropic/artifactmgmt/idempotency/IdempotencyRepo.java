package com.anthropic.artifactmgmt.idempotency;

import java.util.Optional;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Expression;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.model.PutItemEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

public class IdempotencyRepo {

  private static final Expression CONDITION_NOT_EXISTS =
      Expression.builder().expression("attribute_not_exists(idempotency_key)").build();

  private final DynamoDbTable<IdempotencyRecordBean> table;

  public IdempotencyRepo(DynamoDbClient dynamoDbClient, String tableName) {
    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamoDbClient).build();
    this.table = enhanced.table(tableName, TableSchema.fromBean(IdempotencyRecordBean.class));
  }

  IdempotencyRepo(DynamoDbTable<IdempotencyRecordBean> table) {
    this.table = table;
  }

  public Optional<IdempotencyRecord> find(String idempotencyKey) {
    IdempotencyRecordBean bean =
        table.getItem(Key.builder().partitionValue(idempotencyKey).build());
    if (bean == null) {
      return Optional.empty();
    }
    return Optional.of(
        new IdempotencyRecord(
            bean.getIdempotencyKey(), bean.getBodyHash(), bean.getResponse(), bean.getTtl()));
  }

  /**
   * Saves a new idempotency record using attribute_not_exists condition.
   *
   * @return true if saved, false if a concurrent request already wrote the record
   */
  public boolean save(
      String idempotencyKey, String bodyHash, String response, long ttlEpochSeconds) {
    IdempotencyRecordBean bean =
        IdempotencyRecordBean.builder()
            .idempotencyKey(idempotencyKey)
            .bodyHash(bodyHash)
            .response(response)
            .ttl(ttlEpochSeconds)
            .build();
    try {
      table.putItem(
          PutItemEnhancedRequest.builder(IdempotencyRecordBean.class)
              .item(bean)
              .conditionExpression(CONDITION_NOT_EXISTS)
              .build());
      return true;
    } catch (ConditionalCheckFailedException e) {
      return false;
    }
  }
}
