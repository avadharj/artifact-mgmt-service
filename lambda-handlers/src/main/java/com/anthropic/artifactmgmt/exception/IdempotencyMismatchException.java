package com.anthropic.artifactmgmt.exception;

public class IdempotencyMismatchException extends RuntimeException {
  public IdempotencyMismatchException() {
    super("Idempotency key reused with a different request body");
  }
}
