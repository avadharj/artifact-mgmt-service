package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class CreateVersionResponse {
  String version;
  VersionStatus status;
  String uploadUrl;
  String uploadUrlExpiresAt;
}
