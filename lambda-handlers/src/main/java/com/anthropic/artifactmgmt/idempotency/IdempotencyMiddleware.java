package com.anthropic.artifactmgmt.idempotency;

import com.anthropic.artifactmgmt.exception.IdempotencyMismatchException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Function;

public class IdempotencyMiddleware {

  private static final ObjectMapper HASH_MAPPER =
      new ObjectMapper()
          .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
          .configure(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN, true);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final IdempotencyRepo repo;

  public IdempotencyMiddleware(IdempotencyRepo repo) {
    this.repo = repo;
  }

  public <I, O> O execute(
      String idempotencyKey, I input, Function<I, O> work, Class<O> responseType) {
    String bodyHash = canonicalSha256(input);

    Optional<IdempotencyRecord> existing = repo.find(idempotencyKey);
    if (existing.isPresent()) {
      if (!existing.get().bodyHash().equals(bodyHash)) {
        throw new IdempotencyMismatchException();
      }
      return deserialize(existing.get().response(), responseType);
    }

    O result = work.apply(input);
    String serialized = serialize(result);
    long ttl = Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond();

    boolean saved = repo.save(idempotencyKey, bodyHash, serialized, ttl);
    if (!saved) {
      // Concurrent first-time request won the race — re-read and treat as replay
      IdempotencyRecord winner =
          repo.find(idempotencyKey)
              .orElseThrow(() -> new IllegalStateException("Idempotency record disappeared"));
      if (!winner.bodyHash().equals(bodyHash)) {
        throw new IdempotencyMismatchException();
      }
      return deserialize(winner.response(), responseType);
    }

    return result;
  }

  private String canonicalSha256(Object input) {
    try {
      byte[] json = HASH_MAPPER.writeValueAsBytes(input);
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(json);
      return HexFormat.of().formatHex(hash);
    } catch (Exception e) {
      throw new RuntimeException("Failed to compute body hash", e);
    }
  }

  private String serialize(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new RuntimeException("Failed to serialize response", e);
    }
  }

  private <O> O deserialize(String json, Class<O> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new RuntimeException("Failed to deserialize stored response", e);
    }
  }
}
