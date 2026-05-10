package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

@Value
@Builder
@Jacksonized
public class CreateModelRequest {
  String modelName;
  String frameworkHint;
  String description;
}
