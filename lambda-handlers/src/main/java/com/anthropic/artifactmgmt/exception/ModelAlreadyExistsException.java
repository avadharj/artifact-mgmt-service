package com.anthropic.artifactmgmt.exception;

public class ModelAlreadyExistsException extends RuntimeException {
  public ModelAlreadyExistsException(String modelName) {
    super("Model already exists: " + modelName);
  }
}
