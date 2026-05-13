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
  // Optional. When set at CreateVersion time, the presigned URL is bound to this checksum
  // and S3 enforces it on upload. Persisted so idempotency-replay can re-sign with the same
  // value (otherwise the second URL would not match the first).
  String checksumSha256;
}
