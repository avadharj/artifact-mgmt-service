package com.anthropic.artifactmgmt.model;

import lombok.Builder;
import lombok.Value;

/** Sparse projection returned by ListModels — omits description and timestamps. */
@Value
@Builder
public class ListModelItem {
  String modelName;
  String owner;
  String frameworkHint;
  Integer latestMajor;
  Integer latestMinor;
  String status;

  public static ListModelItem from(Model model) {
    return ListModelItem.builder()
        .modelName(model.getModelName())
        .owner(model.getOwner())
        .frameworkHint(model.getFrameworkHint())
        .latestMajor(model.getLatestMajor())
        .latestMinor(model.getLatestMinor())
        .status(model.getStatus())
        .build();
  }
}
