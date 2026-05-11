package com.anthropic.artifactmgmt.idempotency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;

@DynamoDbBean
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecordBean {

  private String idempotencyKey;
  private String bodyHash;
  private String response;
  private Long ttl;

  @DynamoDbPartitionKey
  @DynamoDbAttribute("idempotency_key")
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  @DynamoDbAttribute("body_hash")
  public String getBodyHash() {
    return bodyHash;
  }

  @DynamoDbAttribute("response")
  public String getResponse() {
    return response;
  }

  @DynamoDbAttribute("ttl")
  public Long getTtl() {
    return ttl;
  }
}
