package com.anthropic.artifactmgmt.dao;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondarySortKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;

@DynamoDbBean
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VersionRecord {

  private String modelName;
  private String versionKey;
  private int major;
  private int minor;
  private String s3Key;
  private String status;
  private String depSnapshot;
  private String trainingMetadata;
  private String idempotencyKey;
  private Long ttl;
  private String createdAt;
  private String createdBy;
  private String checksumSha256;

  @DynamoDbPartitionKey
  @DynamoDbAttribute("model_name")
  public String getModelName() {
    return modelName;
  }

  @DynamoDbSortKey
  @DynamoDbAttribute("version_key")
  public String getVersionKey() {
    return versionKey;
  }

  @DynamoDbAttribute("s3_key")
  public String getS3Key() {
    return s3Key;
  }

  @DynamoDbAttribute("dep_snapshot")
  public String getDepSnapshot() {
    return depSnapshot;
  }

  @DynamoDbAttribute("training_metadata")
  public String getTrainingMetadata() {
    return trainingMetadata;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = "status-created-gsi")
  public String getStatus() {
    return status;
  }

  @DynamoDbSecondaryPartitionKey(indexNames = "idempotency-gsi")
  @DynamoDbAttribute("idempotency_key")
  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  @DynamoDbSecondarySortKey(indexNames = "status-created-gsi")
  @DynamoDbAttribute("created_at")
  public String getCreatedAt() {
    return createdAt;
  }

  @DynamoDbAttribute("created_by")
  public String getCreatedBy() {
    return createdBy;
  }

  @DynamoDbAttribute("checksum_sha256")
  public String getChecksumSha256() {
    return checksumSha256;
  }
}
