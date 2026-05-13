package com.anthropic.artifactmgmt.handlers;

/** Abstraction over CloudWatch metrics emission. Wired to Powertools EMF in story 7.1. */
public interface MetricsPublisher {
  void recordVersionCreated(String framework);

  void recordVersionConfirmed();

  /**
   * Story 6.1: emitted by SweeperHandler once per orphan reconciled. `outcome` is "READY" or
   * "FAILED" (the new status the orphan was flipped to). Becomes a CloudWatch dimension when
   * Powertools EMF is wired in 7.1.
   */
  void recordOrphanSwept(String outcome);

  static MetricsPublisher noOp() {
    return new MetricsPublisher() {
      @Override
      public void recordVersionCreated(String framework) {}

      @Override
      public void recordVersionConfirmed() {}

      @Override
      public void recordOrphanSwept(String outcome) {}
    };
  }
}
