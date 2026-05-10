package com.anthropic.artifactmgmt.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class VersionKeyTest {

  @Test
  void givenValidInputs_whenEncode_thenZeroPads() {
    assertThat(VersionKey.encode(3, 5)).isEqualTo("0003.0005");
    assertThat(VersionKey.encode(0, 0)).isEqualTo("0000.0000");
    assertThat(VersionKey.encode(9999, 9999)).isEqualTo("9999.9999");
    assertThat(VersionKey.encode(100, 1)).isEqualTo("0100.0001");
  }

  @Test
  void givenNegativeMajor_whenEncode_thenThrows() {
    assertThatThrownBy(() -> VersionKey.encode(-1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-negative");
  }

  @Test
  void givenNegativeMinor_whenEncode_thenThrows() {
    assertThatThrownBy(() -> VersionKey.encode(0, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("non-negative");
  }

  @Test
  void givenMajorOverflow_whenEncode_thenThrows() {
    assertThatThrownBy(() -> VersionKey.encode(10000, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("9999");
  }

  @Test
  void givenMinorOverflow_whenEncode_thenThrows() {
    assertThatThrownBy(() -> VersionKey.encode(0, 10000))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("9999");
  }

  @Test
  void givenValidKey_whenDecode_thenReturnsParts() {
    int[] parts = VersionKey.decode("0003.0005");
    assertThat(parts).containsExactly(3, 5);
  }

  @Test
  void givenInvalidFormat_whenDecode_thenThrows() {
    assertThatThrownBy(() -> VersionKey.decode("3.5")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VersionKey.decode("00003.0005"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VersionKey.decode(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> VersionKey.decode("0003-0005"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void givenLexicographicSort_thenSemanticallyCorrect() {
    // "0003.0010" > "0003.0005" lexicographically, matching semantic order
    assertThat("0003.0010".compareTo("0003.0005")).isGreaterThan(0);
    // "0004.0000" > "0003.9999" lexicographically, matching semantic order
    assertThat("0004.0000".compareTo("0003.9999")).isGreaterThan(0);
  }

  @Test
  void givenRandomPairs_whenEncodeDecodeRoundTrip_thenEqual() {
    Random rng = new Random(42L);
    for (int i = 0; i < 10_000; i++) {
      int major = rng.nextInt(10_000);
      int minor = rng.nextInt(10_000);
      String encoded = VersionKey.encode(major, minor);
      int[] decoded = VersionKey.decode(encoded);
      assertThat(decoded[0]).as("major round-trip at i=%d", i).isEqualTo(major);
      assertThat(decoded[1]).as("minor round-trip at i=%d", i).isEqualTo(minor);
    }
  }
}
