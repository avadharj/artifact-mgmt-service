package com.anthropic.artifactmgmt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfirmVersionRequest {
  Long sizeBytes;
  String checksumSha256;
}
