package com.anthropic.artifactmgmt.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.anthropic.artifactmgmt.dao.ModelDao;
import com.anthropic.artifactmgmt.dao.VersionDao;
import com.anthropic.artifactmgmt.dao.VersionKey;
import com.anthropic.artifactmgmt.exception.InvalidMajorVersionException;
import com.anthropic.artifactmgmt.exception.ModelNotFoundException;
import com.anthropic.artifactmgmt.exception.VersionConflictException;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionStatus;
import com.anthropic.artifactmgmt.version.IncrementResult;
import com.anthropic.artifactmgmt.version.VersionIncrementer;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class VersionHandlerTest {

  private ModelDao mockModelDao;
  private VersionDao mockVersionDao;
  private VersionIncrementer mockIncrementer;
  private S3Presigner mockPresigner;
  private VersionHandler handler;

  private static final String BUCKET = "test-bucket";
  private static final String CALLER_ARN = "arn:aws:iam::123456789012:user/alice";
  private static final String UPLOAD_URL = "https://s3.amazonaws.com/test-bucket/key?presign=abc";

  @BeforeEach
  void setUp() throws Exception {
    mockModelDao = mock(ModelDao.class);
    mockVersionDao = mock(VersionDao.class);
    mockIncrementer = mock(VersionIncrementer.class);
    mockPresigner = mock(S3Presigner.class);

    // Default: no existing idempotency key
    when(mockVersionDao.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

    // Default presigner stub
    PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
    when(presigned.url()).thenReturn(new URL(UPLOAD_URL));
    when(mockPresigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);

    handler =
        new VersionHandler(
            mockModelDao,
            mockVersionDao,
            mockIncrementer,
            mockPresigner,
            BUCKET,
            MetricsPublisher.noOp());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private APIGatewayProxyRequestEvent postVersions(String modelName, String body) {
    RequestIdentity identity = new RequestIdentity();
    identity.setCaller(CALLER_ARN);
    ProxyRequestContext ctx = new ProxyRequestContext();
    ctx.setIdentity(identity);

    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions");
    e.setHttpMethod("POST");
    e.setPathParameters(Map.of("modelName", modelName));
    e.setRequestContext(ctx);
    e.setBody(body);
    return e;
  }

  private Model modelAt(String name, int major, int minor) {
    return Model.builder()
        .modelName(name)
        .owner("alice")
        .frameworkHint("pytorch")
        .description("test")
        .latestMajor(major)
        .latestMinor(minor)
        .status("ACTIVE")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-01T00:00:00Z")
        .build();
  }

  private Version pendingVersion(String modelName, int major, int minor, String idempotencyKey) {
    return Version.builder()
        .modelName(modelName)
        .major(major)
        .minor(minor)
        .versionKey(VersionKey.encode(major, minor))
        .s3Key(modelName + "/v" + major + "." + minor + "/weights.bin")
        .status(VersionStatus.PENDING)
        .depSnapshot("{}")
        .trainingMetadata("{}")
        .idempotencyKey(idempotencyKey)
        .ttl(Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond())
        .createdAt(Instant.now().toString())
        .createdBy("alice")
        .build();
  }

  private static final String BODY_MINOR =
      "{\"idempotencyKey\":\"00000000-0000-0000-0000-000000000001\","
          + "\"depSnapshot\":{\"framework\":{\"name\":\"pytorch\",\"version\":\"2.0\"},"
          + "\"pythonVersion\":\"3.11\",\"packages\":{},\"os\":\"linux\","
          + "\"capturedAt\":\"2026-01-01T00:00:00Z\"},"
          + "\"trainingMetadata\":{}}";

  private static final String BODY_MAJOR_4 =
      "{\"major\":4,\"idempotencyKey\":\"00000000-0000-0000-0000-000000000002\","
          + "\"depSnapshot\":{\"framework\":{\"name\":\"pytorch\",\"version\":\"2.0\"},"
          + "\"pythonVersion\":\"3.11\",\"packages\":{},\"os\":\"linux\","
          + "\"capturedAt\":\"2026-01-01T00:00:00Z\"},"
          + "\"trainingMetadata\":{}}";

  // ── AC: happy path minor bump ─────────────────────────────────────────────

  @Test
  void givenV1dot0Model_whenCreateVersionNoMajor_thenReturns201WithV1dot1() throws Exception {
    when(mockModelDao.get("fraud-detector"))
        .thenReturn(Optional.of(modelAt("fraud-detector", 1, 0)));
    when(mockIncrementer.next(1, 0, Optional.empty()))
        .thenReturn(IncrementResult.minorBump(1, 1, 1));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", BODY_MINOR), null);

    assertThat(resp.getStatusCode()).isEqualTo(201);
    assertThat(resp.getBody()).contains("\"version\":\"1.1\"");
    assertThat(resp.getBody()).contains("PENDING");
    assertThat(resp.getBody()).contains("uploadUrl");
    verify(mockVersionDao).put(any());
    verify(mockModelDao).updateLatestVersion("fraud-detector", 1, 1, 1);
  }

  // ── AC: happy path major bump ─────────────────────────────────────────────

  @Test
  void givenV3dot5Model_whenCreateVersionWithMajor4_thenReturns201WithV4dot0() throws Exception {
    when(mockModelDao.get("fraud-detector"))
        .thenReturn(Optional.of(modelAt("fraud-detector", 3, 5)));
    when(mockIncrementer.next(3, 5, Optional.of(4))).thenReturn(IncrementResult.majorBump(4, 0, 3));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", BODY_MAJOR_4), null);

    assertThat(resp.getStatusCode()).isEqualTo(201);
    assertThat(resp.getBody()).contains("\"version\":\"4.0\"");
    verify(mockModelDao).updateLatestVersion("fraud-detector", 4, 0, 3);
  }

  // ── AC: skip-major ────────────────────────────────────────────────────────

  @Test
  void givenV3dot5Model_whenCreateVersionWithMajor7_thenReturns201WithV7dot0() throws Exception {
    String body =
        "{\"major\":7,\"idempotencyKey\":\"00000000-0000-0000-0000-000000000003\","
            + "\"depSnapshot\":{\"framework\":{\"name\":\"pytorch\",\"version\":\"2.0\"},"
            + "\"pythonVersion\":\"3.11\",\"packages\":{},\"os\":\"linux\","
            + "\"capturedAt\":\"2026-01-01T00:00:00Z\"},"
            + "\"trainingMetadata\":{}}";
    when(mockModelDao.get("fraud-detector"))
        .thenReturn(Optional.of(modelAt("fraud-detector", 3, 5)));
    when(mockIncrementer.next(3, 5, Optional.of(7))).thenReturn(IncrementResult.majorBump(7, 0, 3));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(201);
    assertThat(resp.getBody()).contains("\"version\":\"7.0\"");
  }

  // ── AC: conflict ──────────────────────────────────────────────────────────

  @Test
  void givenVersionConflict_whenCreateVersion_thenReturns409WithCurrentVersion() throws Exception {
    when(mockModelDao.get("fraud-detector"))
        .thenReturn(Optional.of(modelAt("fraud-detector", 3, 5)));
    when(mockIncrementer.next(3, 5, Optional.empty()))
        .thenReturn(IncrementResult.minorBump(3, 6, 3));
    org.mockito.Mockito.doThrow(new VersionConflictException(3, 5))
        .when(mockModelDao)
        .updateLatestVersion(anyString(), anyInt(), anyInt(), anyInt());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", BODY_MINOR), null);

    assertThat(resp.getStatusCode()).isEqualTo(409);
    assertThat(resp.getBody()).contains("VERSION_CONFLICT");
    // current_major/minor come from the exception (re-fetched after conflict), not stale model
    assertThat(resp.getBody()).contains("\"current_major\":\"3\"");
    assertThat(resp.getBody()).contains("\"current_minor\":\"5\"");
    verify(mockVersionDao, never()).put(any());
  }

  // ── AC: invalid major ────────────────────────────────────────────────────

  @Test
  void givenInvalidMajor_whenCreateVersion_thenReturns400() throws Exception {
    when(mockModelDao.get("fraud-detector"))
        .thenReturn(Optional.of(modelAt("fraud-detector", 5, 2)));
    when(mockIncrementer.next(5, 2, Optional.of(4)))
        .thenThrow(new InvalidMajorVersionException(4, 5));

    String body =
        "{\"major\":4,\"idempotencyKey\":\"00000000-0000-0000-0000-000000000004\","
            + "\"depSnapshot\":{\"framework\":{\"name\":\"pytorch\",\"version\":\"2.0\"},"
            + "\"pythonVersion\":\"3.11\",\"packages\":{},\"os\":\"linux\","
            + "\"capturedAt\":\"2026-01-01T00:00:00Z\"},"
            + "\"trainingMetadata\":{}}";

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("INVALID_MAJOR_VERSION");
    verify(mockVersionDao, never()).put(any());
  }

  // ── AC: idempotency replay ────────────────────────────────────────────────

  @Test
  void givenExistingIdempotencyKey_whenCreateVersion_thenReturnsReplayWithFreshUrl()
      throws Exception {
    String key = "00000000-0000-0000-0000-000000000001";
    Version existing = pendingVersion("fraud-detector", 1, 1, key);
    when(mockVersionDao.findByIdempotencyKey(key)).thenReturn(Optional.of(existing));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", BODY_MINOR), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"version\":\"1.1\"");
    assertThat(resp.getBody()).contains("uploadUrl");
    // Model DAO never touched on replay
    verify(mockModelDao, never()).get(anyString());
    verify(mockVersionDao, never()).put(any());
  }

  // ── AC: unknown model ─────────────────────────────────────────────────────

  @Test
  void givenMissingModel_whenCreateVersion_thenReturns404() throws Exception {
    when(mockModelDao.get("no-such-model")).thenThrow(new ModelNotFoundException("no-such-model"));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("no-such-model", BODY_MINOR), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("MODEL_NOT_FOUND");
  }

  // ── Validation ────────────────────────────────────────────────────────────

  @Test
  void givenMissingIdempotencyKey_whenCreateVersion_thenReturns400() {
    String body = "{\"depSnapshot\":{\"framework\":{\"name\":\"pytorch\"}}}";

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("VALIDATION_ERROR");
  }
}
