package com.anthropic.artifactmgmt.handlers;

import software.amazon.cloudwatchlogs.emf.model.DimensionSet;
import software.amazon.cloudwatchlogs.emf.model.Unit;
import software.amazon.lambda.powertools.metrics.MetricsUtils;

/**
 * Powertools-for-Java metrics publisher (Story 7.2). Each call emits one EMF log line via {@code
 * MetricsUtils.withSingleMetric}, which builds a one-shot {@code MetricsLogger}, sets the given
 * dimensions + value, and flushes immediately.
 *
 * <p>Using single-metric-per-line is intentional: CloudWatch EMF rolls all metrics in one emission
 * into the same dimension set, so emitting {@code VersionsCreated[framework=pytorch]} and {@code
 * VersionConflict[operation=create_version]} in the same line would produce a cross-dimensioned
 * mess. Separate lines, separate dimensions, separate metrics.
 *
 * <p>The namespace and service name come from the {@code POWERTOOLS_METRICS_NAMESPACE} and {@code
 * POWERTOOLS_SERVICE_NAME} env vars (wired in compute-stack.ts for all four stages).
 */
final class PowertoolsMetricsPublisher implements MetricsPublisher {

  @Override
  public void recordVersionCreated(String framework) {
    String dim = framework == null || framework.isBlank() ? "unknown" : framework;
    MetricsUtils.withSingleMetric(
        "VersionsCreated", 1d, Unit.COUNT, m -> m.setDimensions(DimensionSet.of("framework", dim)));
  }

  @Override
  public void recordVersionConfirmed() {
    MetricsUtils.withSingleMetric("VersionsConfirmed", 1d, Unit.COUNT, m -> {});
  }

  @Override
  public void recordOrphanSwept(String outcome) {
    String dim = outcome == null || outcome.isBlank() ? "unknown" : outcome;
    MetricsUtils.withSingleMetric(
        "UploadOrphansSwept",
        1d,
        Unit.COUNT,
        m -> m.setDimensions(DimensionSet.of("outcome", dim)));
  }

  @Override
  public void recordVersionConflict(String operation) {
    String dim = operation == null || operation.isBlank() ? "unknown" : operation;
    MetricsUtils.withSingleMetric(
        "VersionConflict", 1d, Unit.COUNT, m -> m.setDimensions(DimensionSet.of("operation", dim)));
  }

  @Override
  public void recordIdempotencyReplay() {
    MetricsUtils.withSingleMetric("IdempotencyReplay", 1d, Unit.COUNT, m -> {});
  }

  @Override
  public void recordIdempotencyExpiredReplay() {
    MetricsUtils.withSingleMetric("IdempotencyExpiredReplay", 1d, Unit.COUNT, m -> {});
  }
}
