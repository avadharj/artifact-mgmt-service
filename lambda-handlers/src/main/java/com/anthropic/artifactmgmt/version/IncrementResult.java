package com.anthropic.artifactmgmt.version;

public final class IncrementResult {

  private final int newMajor;
  private final int newMinor;
  private final int expectedCurrentMajor;
  private final BumpType type;

  private IncrementResult(int newMajor, int newMinor, int expectedCurrentMajor, BumpType type) {
    this.newMajor = newMajor;
    this.newMinor = newMinor;
    this.expectedCurrentMajor = expectedCurrentMajor;
    this.type = type;
  }

  public static IncrementResult minorBump(int major, int minor, int expectedMajor) {
    return new IncrementResult(major, minor, expectedMajor, BumpType.MINOR);
  }

  public static IncrementResult majorBump(int major, int minor, int expectedMajor) {
    return new IncrementResult(major, minor, expectedMajor, BumpType.MAJOR);
  }

  public int newMajor() {
    return newMajor;
  }

  public int newMinor() {
    return newMinor;
  }

  public int expectedCurrentMajor() {
    return expectedCurrentMajor;
  }

  public BumpType type() {
    return type;
  }
}
