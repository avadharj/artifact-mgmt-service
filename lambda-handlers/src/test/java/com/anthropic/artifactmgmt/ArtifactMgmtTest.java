package com.anthropic.artifactmgmt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArtifactMgmtTest {

  @Test
  void projectBootstraps() {
    assertThat(ArtifactMgmt.class).isNotNull();
  }
}
