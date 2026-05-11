package com.anthropic.artifactmgmt.exception;

public class VersionConflictException extends RuntimeException {
  private final int currentMajor;
  private final int currentMinor;

  public VersionConflictException(String message) {
    super(message);
    this.currentMajor = -1;
    this.currentMinor = -1;
  }

  public VersionConflictException(int currentMajor, int currentMinor) {
    super("Version conflict: current version is " + currentMajor + "." + currentMinor);
    this.currentMajor = currentMajor;
    this.currentMinor = currentMinor;
  }

  public int getCurrentMajor() {
    return currentMajor;
  }

  public int getCurrentMinor() {
    return currentMinor;
  }
}
