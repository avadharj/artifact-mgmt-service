package com.anthropic.artifactmgmt.exception;

public class ModelNotFoundException extends RuntimeException {
  public ModelNotFoundException(String modelName) {
    super("Model not found: " + modelName);
  }
}
