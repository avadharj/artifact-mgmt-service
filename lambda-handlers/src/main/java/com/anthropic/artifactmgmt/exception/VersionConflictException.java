package com.anthropic.artifactmgmt.exception;

public class VersionConflictException extends RuntimeException {
  public VersionConflictException(String message) {
    super(message);
  }
}
