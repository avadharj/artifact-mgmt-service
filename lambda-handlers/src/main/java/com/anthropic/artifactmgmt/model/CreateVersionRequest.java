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
  // Optional base64-encoded SHA-256 of the artifact bytes. When present, the server presigns
  // the PUT URL with this exact value bound — S3 rejects the upload if the client's bytes
  // don't hash to it. When absent, the upload URL is unbound and ConfirmVersion can only
  // verify size (4.5 strict checksum path skipped).
  String checksumSha256;
}
