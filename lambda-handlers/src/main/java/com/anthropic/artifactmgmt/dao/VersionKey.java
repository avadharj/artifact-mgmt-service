package com.anthropic.artifactmgmt.dao;

public final class VersionKey {

  private static final String FORMAT = "%04d.%04d";
  private static final java.util.regex.Pattern PATTERN =
      java.util.regex.Pattern.compile("^(\\d{4})\\.(\\d{4})$");

  private VersionKey() {}

  public static String encode(int major, int minor) {
    if (major < 0 || minor < 0) {
      throw new IllegalArgumentException(
          "major and minor must be non-negative, got: " + major + "." + minor);
    }
    if (major > 9999 || minor > 9999) {
      throw new IllegalArgumentException(
          "major and minor must be ≤ 9999, got: " + major + "." + minor);
    }
    return String.format(FORMAT, major, minor);
  }

  public static int[] decode(String key) {
    if (key == null) {
      throw new IllegalArgumentException("version key must not be null");
    }
    java.util.regex.Matcher m = PATTERN.matcher(key);
    if (!m.matches()) {
      throw new IllegalArgumentException("invalid version key format: " + key);
    }
    return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
  }
}
