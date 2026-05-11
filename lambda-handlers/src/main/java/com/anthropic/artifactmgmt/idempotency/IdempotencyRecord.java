package com.anthropic.artifactmgmt.idempotency;

public final class IdempotencyRecord {

  private final String idempotencyKey;
  private final String bodyHash;
  private final String response;
  private final long ttl;

  public IdempotencyRecord(String idempotencyKey, String bodyHash, String response, long ttl) {
    this.idempotencyKey = idempotencyKey;
    this.bodyHash = bodyHash;
    this.response = response;
    this.ttl = ttl;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public String bodyHash() {
    return bodyHash;
  }

  public String response() {
    return response;
  }

  public long ttl() {
    return ttl;
  }
}
