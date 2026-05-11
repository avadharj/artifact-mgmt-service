package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VersionResponse {
  String modelName;
  String version;
  VersionStatus status;
  String s3Key;
  String createdAt;
  String createdBy;

  public static VersionResponse from(Version v) {
    return VersionResponse.builder()
        .modelName(v.getModelName())
        .version(v.getMajor() + "." + v.getMinor())
        .status(v.getStatus())
        .s3Key(v.getS3Key())
        .createdAt(v.getCreatedAt())
        .createdBy(v.getCreatedBy())
        .build();
  }
}
