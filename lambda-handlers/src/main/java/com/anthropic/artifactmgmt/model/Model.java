package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Model {
  String modelName;
  String owner;
  String frameworkHint;
  String description;
  Integer latestMajor;
  Integer latestMinor;
  String status;
  String createdAt;
  String updatedAt;
}
