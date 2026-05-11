package com.anthropic.artifactmgmt.handlers;

/** Abstraction over CloudWatch metrics emission. Wired to Powertools EMF in story 7.1. */
@FunctionalInterface
public interface MetricsPublisher {
  void recordVersionCreated(String framework);

  static MetricsPublisher noOp() {
    return framework -> {};
  }
}
