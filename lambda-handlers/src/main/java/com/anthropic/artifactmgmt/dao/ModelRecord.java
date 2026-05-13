package com.anthropic.artifactmgmt.dao;

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
public class ModelRecord {
  private String modelName;
  private String owner;
  private String frameworkHint;
  private String description;
  private Integer latestMajor;
  private Integer latestMinor;
  private String status;
  private String createdAt;
  private String updatedAt;

  @DynamoDbPartitionKey
  @DynamoDbAttribute("model_name")
  public String getModelName() {
    return modelName;
  }

  @DynamoDbAttribute("framework_hint")
  public String getFrameworkHint() {
    return frameworkHint;
  }

  @DynamoDbAttribute("latest_major")
  public Integer getLatestMajor() {
    return latestMajor;
  }

  @DynamoDbAttribute("latest_minor")
  public Integer getLatestMinor() {
    return latestMinor;
  }

  @DynamoDbAttribute("created_at")
  public String getCreatedAt() {
    return createdAt;
  }

  @DynamoDbAttribute("updated_at")
  public String getUpdatedAt() {
    return updatedAt;
  }
}
