package com.anthropic.artifactmgmt.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class CreateVersionRequest {
  Integer major;
  String idempotencyKey;
  JsonNode depSnapshot;
  JsonNode trainingMetadata;
}
