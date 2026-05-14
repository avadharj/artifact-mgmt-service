package com.anthropic.artifactmgmt.handlers;

/**
 * CloudWatch metrics abstraction. Production implementation is {@link PowertoolsMetricsPublisher}
 * which emits via Embedded Metric Format (EMF) — log-only, zero PutMetricData cost.
 */
public interface MetricsPublisher {
  /** VersionsCreated, dimension framework. */
  void recordVersionCreated(String framework);

  /** VersionsConfirmed, no dimensions. */
  void recordVersionConfirmed();

  /** UploadOrphansSwept, dimension outcome ("READY" or "FAILED"). */
  void recordOrphanSwept(String outcome);

  /**
   * VersionConflict, dimension operation. Fires whenever a conditional DDB update against the model
   * row or a version row's status loses a race against another writer.
   */
  void recordVersionConflict(String operation);

  /** IdempotencyReplay, no dimensions. The replay hit a live (non-expired) idempotency record. */
  void recordIdempotencyReplay();

  /**
   * IdempotencyExpiredReplay, no dimensions. The replay hit an idempotency record whose TTL has
   * already elapsed — operationally a code smell since the cleanup TTL on the Version row should
   * have removed it before clients could replay.
   */
  void recordIdempotencyExpiredReplay();

  static MetricsPublisher noOp() {
    return new MetricsPublisher() {
      @Override
      public void recordVersionCreated(String framework) {}

      @Override
      public void recordVersionConfirmed() {}

      @Override
      public void recordOrphanSwept(String outcome) {}

      @Override
      public void recordVersionConflict(String operation) {}

      @Override
      public void recordIdempotencyReplay() {}

      @Override
      public void recordIdempotencyExpiredReplay() {}
    };
  }
}
