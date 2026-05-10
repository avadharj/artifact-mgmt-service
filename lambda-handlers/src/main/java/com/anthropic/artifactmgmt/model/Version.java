package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Version {
  String modelName;
  int major;
  int minor;
  String versionKey;
  String s3Key;
  VersionStatus status;
  String depSnapshot;
  String trainingMetadata;
  String idempotencyKey;
  Long ttl;
  String createdAt;
  String createdBy;
}
