package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ModelResponse {
  String modelName;
  String owner;
  String frameworkHint;
  String description;
  Integer latestMajor;
  Integer latestMinor;
  String status;
  String createdAt;
  String updatedAt;

  public static ModelResponse from(Model model) {
    return ModelResponse.builder()
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
}
