package com.anthropic.artifactmgmt.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.artifactmgmt.exception.InvalidMajorVersionException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VersionIncrementerTest {

  private VersionIncrementer incrementer;

  @BeforeEach
  void setUp() {
    incrementer = new VersionIncrementer();
  }

  @Test
  void givenEmptyRequestedMajor_whenNext_thenMinorBump() {
    IncrementResult result = incrementer.next(3, 4, Optional.empty());

    assertThat(result.newMajor()).isEqualTo(3);
    assertThat(result.newMinor()).isEqualTo(5);
    assertThat(result.expectedCurrentMajor()).isEqualTo(3);
    assertThat(result.type()).isEqualTo(BumpType.MINOR);
  }

  @Test
  void givenEqualRequestedMajor_whenNext_thenMinorBump() {
    IncrementResult result = incrementer.next(3, 4, Optional.of(3));

    assertThat(result.newMajor()).isEqualTo(3);
    assertThat(result.newMinor()).isEqualTo(5);
    assertThat(result.expectedCurrentMajor()).isEqualTo(3);
    assertThat(result.type()).isEqualTo(BumpType.MINOR);
  }

  @Test
  void givenNextMajor_whenNext_thenMajorBump() {
    IncrementResult result = incrementer.next(3, 4, Optional.of(4));

    assertThat(result.newMajor()).isEqualTo(4);
    assertThat(result.newMinor()).isEqualTo(0);
    assertThat(result.expectedCurrentMajor()).isEqualTo(3);
    assertThat(result.type()).isEqualTo(BumpType.MAJOR);
  }

  @Test
  void givenSkippedMajor_whenNext_thenMajorBumpToRequestedVersion() {
    IncrementResult result = incrementer.next(3, 4, Optional.of(7));

    assertThat(result.newMajor()).isEqualTo(7);
    assertThat(result.newMinor()).isEqualTo(0);
    assertThat(result.expectedCurrentMajor()).isEqualTo(3);
    assertThat(result.type()).isEqualTo(BumpType.MAJOR);
  }

  @Test
  void givenLowerRequestedMajor_whenNext_thenThrowsInvalidMajorVersionException() {
    assertThatThrownBy(() -> incrementer.next(5, 2, Optional.of(4)))
        .isInstanceOf(InvalidMajorVersionException.class)
        .hasMessageContaining("4")
        .hasMessageContaining("5");
  }

  @Test
  void givenFirstVersionState_whenNext_thenProducesV1dot0() {
    // Models are created with latestMajor=1, latestMinor=-1 so first bump → (1, 0)
    IncrementResult result = incrementer.next(1, -1, Optional.empty());

    assertThat(result.newMajor()).isEqualTo(1);
    assertThat(result.newMinor()).isEqualTo(0);
    assertThat(result.type()).isEqualTo(BumpType.MINOR);
  }
}
