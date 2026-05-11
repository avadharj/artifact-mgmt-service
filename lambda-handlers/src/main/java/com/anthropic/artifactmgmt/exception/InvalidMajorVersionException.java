package com.anthropic.artifactmgmt.exception;

public class InvalidMajorVersionException extends RuntimeException {
  public InvalidMajorVersionException(int requested, int current) {
    super(
        "Requested major version " + requested + " is less than current major version " + current);
  }
}
