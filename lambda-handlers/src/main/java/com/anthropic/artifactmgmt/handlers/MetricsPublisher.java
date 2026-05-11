package com.anthropic.artifactmgmt.handlers;

/** Abstraction over CloudWatch metrics emission. Wired to Powertools EMF in story 7.1. */
public interface MetricsPublisher {
  void recordVersionCreated(String framework);

  void recordVersionConfirmed();

  static MetricsPublisher noOp() {
    return new MetricsPublisher() {
      @Override
      public void recordVersionCreated(String framework) {}

      @Override
      public void recordVersionConfirmed() {}
    };
  }
}
