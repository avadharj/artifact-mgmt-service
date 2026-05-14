package com.anthropic.artifactmgmt.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class VersionHandlerTest {

  private ModelDao mockModelDao;
  private VersionDao mockVersionDao;
  private VersionIncrementer mockIncrementer;
  private S3Client mockS3Client;
  private S3Presigner mockPresigner;
  private MetricsPublisher mockMetrics;
  private VersionHandler handler;

  private static final String BUCKET = "test-bucket";
  private static final String CALLER_ARN = "arn:aws:iam::123456789012:user/alice";
  private static final String UPLOAD_URL = "https://s3.amazonaws.com/test-bucket/key?presign=abc";
  private static final String DOWNLOAD_URL =
      "https://s3.amazonaws.com/test-bucket/key?presign=download";

  @BeforeEach
  void setUp() throws Exception {
    mockModelDao = mock(ModelDao.class);
    mockVersionDao = mock(VersionDao.class);
    mockIncrementer = mock(VersionIncrementer.class);
    mockS3Client = mock(S3Client.class);
    mockPresigner = mock(S3Presigner.class);
    mockMetrics = mock(MetricsPublisher.class);

    // Default: no existing idempotency key
    when(mockVersionDao.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());

    // Default presigner stub — PUT (upload URL) and GET (download URL for Story 5.1).
    PresignedPutObjectRequest presigned = mock(PresignedPutObjectRequest.class);
    when(presigned.url()).thenReturn(new URL(UPLOAD_URL));
    when(mockPresigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presigned);
    PresignedGetObjectRequest presignedGet = mock(PresignedGetObjectRequest.class);
    when(presignedGet.url()).thenReturn(new URL(DOWNLOAD_URL));
    when(mockPresigner.presignGetObject(any(GetObjectPresignRequest.class)))
        .thenReturn(presignedGet);

    handler =
        new VersionHandler(
            mockModelDao,
            mockVersionDao,
            mockIncrementer,
            mockS3Client,
            mockPresigner,
            BUCKET,
            mockMetrics);
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
    // Story 7.2: VersionsCreated emitted with framework dimension parsed from depSnapshot.
    verify(mockMetrics).recordVersionCreated("pytorch");
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
    // Story 7.2: VersionConflict metric emitted with operation dimension.
    verify(mockMetrics).recordVersionConflict("create_version");
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
    // Story 7.2: live (non-expired) replay emits IdempotencyReplay, not the expired variant.
    verify(mockMetrics).recordIdempotencyReplay();
    verify(mockMetrics, never()).recordIdempotencyExpiredReplay();
  }

  @Test
  void givenExpiredIdempotencyRecord_whenCreateVersion_thenEmitsExpiredReplayMetric()
      throws Exception {
    // Story 7.2: a replay whose TTL has already elapsed is a code smell — the cleanup TTL on
    // the Version row was supposed to remove it before clients could replay. Emit a separate
    // metric so dashboards can alert on this specifically.
    String key = "00000000-0000-0000-0000-000000000099";
    Version expired =
        Version.builder()
            .modelName("fraud-detector")
            .major(1)
            .minor(1)
            .versionKey(VersionKey.encode(1, 1))
            .s3Key("fraud-detector/v1.1/weights.bin")
            .status(VersionStatus.PENDING)
            .depSnapshot("{}")
            .trainingMetadata("{}")
            .idempotencyKey(key)
            .ttl(Instant.now().minus(1, ChronoUnit.HOURS).getEpochSecond()) // already elapsed
            .createdAt(Instant.now().toString())
            .createdBy("alice")
            .build();
    when(mockVersionDao.findByIdempotencyKey(key)).thenReturn(Optional.of(expired));

    String body =
        "{\"idempotencyKey\":\""
            + key
            + "\","
            + "\"depSnapshot\":{\"framework\":{\"name\":\"pytorch\",\"version\":\"2.0\"},"
            + "\"pythonVersion\":\"3.11\",\"packages\":{},\"os\":\"linux\","
            + "\"capturedAt\":\"2026-01-01T00:00:00Z\"},"
            + "\"trainingMetadata\":{}}";
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postVersions("fraud-detector", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    verify(mockMetrics).recordIdempotencyExpiredReplay();
    verify(mockMetrics, never()).recordIdempotencyReplay();
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

  // ── confirmVersion helpers ────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private APIGatewayProxyRequestEvent postConfirm(String modelName, String version, String body) {
    // ConfirmVersion is PUT per the Smithy contract (Story 4.5 alpha smoke test, 2026-05-13).
    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions/{version}/confirm");
    e.setHttpMethod("PUT");
    e.setPathParameters(Map.of("modelName", modelName, "version", version));
    e.setBody(body);
    return e;
  }

  private Version versionWithStatus(String modelName, int major, int minor, VersionStatus status) {
    return Version.builder()
        .modelName(modelName)
        .major(major)
        .minor(minor)
        .versionKey(VersionKey.encode(major, minor))
        .s3Key(modelName + "/v" + major + "." + minor + "/weights.bin")
        .status(status)
        .depSnapshot("{}")
        .trainingMetadata("{}")
        .idempotencyKey("idem-key")
        .createdAt(Instant.now().toString())
        .createdBy("alice")
        .build();
  }

  @SuppressWarnings("unchecked")
  private void stubHeadObject(String s3Key, Long contentLength, String checksumSha256) {
    HeadObjectResponse.Builder b = HeadObjectResponse.builder().contentLength(contentLength);
    if (checksumSha256 != null) {
      b.checksumSHA256(checksumSha256);
    }
    HeadObjectResponse head = b.build();
    when(mockS3Client.headObject(any(Consumer.class))).thenReturn(head);
  }

  @SuppressWarnings("unchecked")
  private void stubHeadObjectNotFound() {
    when(mockS3Client.headObject(any(Consumer.class)))
        .thenThrow(NoSuchKeyException.builder().message("not found").build());
  }

  // ── AC: happy path confirm ────────────────────────────────────────────────

  @Test
  void givenPendingVersionAndS3Uploaded_whenConfirm_thenReturns200Ready() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    Version ready = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(ready));
    stubHeadObject("fraud-detector/v1.0/weights.bin", 1024L, "abc123");

    String body = "{\"sizeBytes\":1024,\"checksumSha256\":\"abc123\"}";
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("READY");
    verify(mockVersionDao)
        .updateStatus("fraud-detector", 1, 0, VersionStatus.READY, VersionStatus.PENDING);
  }

  // ── AC: idempotent confirm when already READY ─────────────────────────────

  @Test
  void givenReadyVersion_whenConfirmAgain_thenReturns200Idempotent() {
    Version ready = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(ready));
    stubHeadObject("fraud-detector/v1.0/weights.bin", 1024L, "abc123");

    String body = "{\"sizeBytes\":1024,\"checksumSha256\":\"abc123\"}";
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("READY");
    verify(mockVersionDao, never()).updateStatus(anyString(), anyInt(), anyInt(), any(), any());
  }

  // ── AC: 404 when version does not exist ───────────────────────────────────

  @Test
  void givenNoVersion_whenConfirm_thenReturns404VersionNotFound() {
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.empty());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", "{}"), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("VERSION_NOT_FOUND");
  }

  // ── AC: 412 when version is DELETED or FAILED ─────────────────────────────

  @Test
  void givenDeletedVersion_whenConfirm_thenReturns412PreconditionFailed() {
    Version deleted = versionWithStatus("fraud-detector", 1, 0, VersionStatus.DELETED);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(deleted));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", "{}"), null);

    assertThat(resp.getStatusCode()).isEqualTo(412);
    assertThat(resp.getBody()).contains("PRECONDITION_FAILED");
  }

  // ── AC: 412 when version is FAILED ───────────────────────────────────────

  @Test
  void givenFailedVersion_whenConfirm_thenReturns412PreconditionFailed() {
    Version failed = versionWithStatus("fraud-detector", 1, 0, VersionStatus.FAILED);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(failed));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", "{}"), null);

    assertThat(resp.getStatusCode()).isEqualTo(412);
    assertThat(resp.getBody()).contains("PRECONDITION_FAILED");
  }

  // ── AC: 404 when S3 object not uploaded yet ───────────────────────────────

  @Test
  void givenPendingVersionButNoS3Upload_whenConfirm_thenReturns404UploadNotFound() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(pending));
    stubHeadObjectNotFound();

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", "{}"), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("UPLOAD_NOT_FOUND");
  }

  // ── AC: 409 when content-length mismatches ────────────────────────────────

  @Test
  void givenSizeMismatch_whenConfirm_thenReturns409ChecksumMismatch() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(pending));
    stubHeadObject("fraud-detector/v1.0/weights.bin", 512L, null);

    String body = "{\"sizeBytes\":1024}";
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(409);
    assertThat(resp.getBody()).contains("CHECKSUM_MISMATCH");
    verify(mockVersionDao, never()).updateStatus(anyString(), anyInt(), anyInt(), any(), any());
  }

  // ── AC: 409 when SHA-256 mismatches ──────────────────────────────────────

  @Test
  void givenSha256Mismatch_whenConfirm_thenReturns409ChecksumMismatch() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(pending));
    stubHeadObject("fraud-detector/v1.0/weights.bin", 1024L, "wrong-hash");

    String body = "{\"sizeBytes\":1024,\"checksumSha256\":\"correct-hash\"}";
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", body), null);

    assertThat(resp.getStatusCode()).isEqualTo(409);
    assertThat(resp.getBody()).contains("CHECKSUM_MISMATCH");
    verify(mockVersionDao, never()).updateStatus(anyString(), anyInt(), anyInt(), any(), any());
  }

  // ── AC: metadata mirror written on confirm ────────────────────────────────

  @Test
  void givenSuccessfulConfirm_whenConfirm_thenMetadataMirrorWritten() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    Version ready = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(ready));
    stubHeadObject("fraud-detector/v1.0/weights.bin", 1024L, null);

    handler.handleRequest(postConfirm("fraud-detector", "1.0", "{\"sizeBytes\":1024}"), null);

    verify(mockS3Client)
        .putObject(any(Consumer.class), any(software.amazon.awssdk.core.sync.RequestBody.class));
  }

  // ── AC: mirror failure is best-effort (still returns 200) ─────────────────

  @Test
  @SuppressWarnings("unchecked")
  void givenMirrorWriteFails_whenConfirm_thenStillReturns200() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    Version ready = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0))
        .thenReturn(Optional.of(pending))
        .thenReturn(Optional.of(ready));
    stubHeadObject("fraud-detector/v1.0/weights.bin", 1024L, null);
    when(mockS3Client.putObject(
            any(Consumer.class), any(software.amazon.awssdk.core.sync.RequestBody.class)))
        .thenThrow(new RuntimeException("S3 unavailable"));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postConfirm("fraud-detector", "1.0", "{\"sizeBytes\":1024}"), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("READY");
    verify(mockVersionDao)
        .updateStatus("fraud-detector", 1, 0, VersionStatus.READY, VersionStatus.PENDING);
  }

  // ── Story 5.1: GetVersion + GetLatestVersion ─────────────────────────────

  private APIGatewayProxyRequestEvent getVersionEvent(String modelName, String version) {
    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions/{version}");
    e.setHttpMethod("GET");
    e.setPathParameters(Map.of("modelName", modelName, "version", version));
    return e;
  }

  private APIGatewayProxyRequestEvent getLatestVersionEvent(String modelName) {
    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions/latest");
    e.setHttpMethod("GET");
    e.setPathParameters(Map.of("modelName", modelName));
    return e;
  }

  @Test
  void givenReadyVersion_whenGetVersion_thenReturns200WithDownloadUrl() {
    Version ready = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(ready));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getVersionEvent("fraud-detector", "1.0"), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"version\":\"1.0\"");
    assertThat(resp.getBody()).contains("\"status\":\"READY\"");
    assertThat(resp.getBody()).contains("downloadUrl");
    assertThat(resp.getBody()).contains("downloadUrlExpiresAt");
    verify(mockPresigner).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  void givenPendingVersion_whenGetVersion_thenReturns200WithoutDownloadUrl() {
    Version pending = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(pending));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getVersionEvent("fraud-detector", "1.0"), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"status\":\"PENDING\"");
    // Story 5.1: download URL only populated for READY rows; bytes may not exist yet otherwise.
    assertThat(resp.getBody()).doesNotContain("downloadUrl");
    verify(mockPresigner, never()).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  void givenMissingVersion_whenGetVersion_thenReturns404() {
    when(mockVersionDao.get("fraud-detector", 9, 9)).thenReturn(Optional.empty());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getVersionEvent("fraud-detector", "9.9"), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("VERSION_NOT_FOUND");
  }

  @Test
  void givenMalformedVersionParam_whenGetVersion_thenReturns400() {
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getVersionEvent("fraud-detector", "not-a-version"), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("VALIDATION_ERROR");
  }

  @Test
  void givenLatestReadyExists_whenGetLatestVersion_thenReturns200WithDownloadUrl() {
    Version latest = versionWithStatus("fraud-detector", 3, 7, VersionStatus.READY);
    when(mockVersionDao.findLatestReady("fraud-detector")).thenReturn(Optional.of(latest));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getLatestVersionEvent("fraud-detector"), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"version\":\"3.7\"");
    assertThat(resp.getBody()).contains("\"status\":\"READY\"");
    assertThat(resp.getBody()).contains("downloadUrl");
    verify(mockPresigner).presignGetObject(any(GetObjectPresignRequest.class));
  }

  @Test
  void givenNoReadyVersion_whenGetLatestVersion_thenReturns404() {
    when(mockVersionDao.findLatestReady("fraud-detector")).thenReturn(Optional.empty());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getLatestVersionEvent("fraud-detector"), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("VERSION_NOT_FOUND");
  }

  // ── Story 5.2: ListVersions ───────────────────────────────────────────────

  private APIGatewayProxyRequestEvent listVersionsEvent(
      String modelName, Map<String, String> queryParams, String callerArn) {
    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions");
    e.setHttpMethod("GET");
    e.setPathParameters(Map.of("modelName", modelName));
    if (queryParams != null) e.setQueryStringParameters(queryParams);
    if (callerArn != null) {
      RequestIdentity identity = new RequestIdentity();
      identity.setCaller(callerArn);
      ProxyRequestContext ctx = new ProxyRequestContext();
      ctx.setIdentity(identity);
      e.setRequestContext(ctx);
    }
    return e;
  }

  private com.anthropic.artifactmgmt.model.PaginatedResult<Version> page(
      java.util.List<Version> items, String nextToken) {
    return new com.anthropic.artifactmgmt.model.PaginatedResult<>(items, nextToken);
  }

  @Test
  void givenTwoReadyVersions_whenListVersions_thenReturns200NewestFirst() {
    Version v20 = versionWithStatus("fraud-detector", 2, 0, VersionStatus.READY);
    Version v10 = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.list("fraud-detector", 50, null, false, false))
        .thenReturn(page(java.util.List.of(v20, v10), null));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(listVersionsEvent("fraud-detector", null, null), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    String body = resp.getBody();
    // Newest-first: 2.0 must appear before 1.0 in the response array
    assertThat(body.indexOf("\"version\":\"2.0\"")).isLessThan(body.indexOf("\"version\":\"1.0\""));
    assertThat(body).contains("\"nextPageToken\":null");
  }

  @Test
  void givenMaxResults_whenListVersions_thenLimitPassedToDao() {
    when(mockVersionDao.list("fraud-detector", 10, null, false, false))
        .thenReturn(page(java.util.List.of(), null));

    handler.handleRequest(
        listVersionsEvent("fraud-detector", Map.of("maxResults", "10"), null), null);

    verify(mockVersionDao).list("fraud-detector", 10, null, false, false);
  }

  @Test
  void givenMaxResultsOver200_whenListVersions_thenClampedTo200() {
    when(mockVersionDao.list(
            anyString(), org.mockito.ArgumentMatchers.eq(200), any(), anyBoolean(), anyBoolean()))
        .thenReturn(page(java.util.List.of(), null));

    handler.handleRequest(
        listVersionsEvent("fraud-detector", Map.of("maxResults", "9999"), null), null);

    verify(mockVersionDao).list("fraud-detector", 200, null, false, false);
  }

  @Test
  void givenIncludePendingTrue_whenListVersions_thenFlagPassedToDao() {
    when(mockVersionDao.list(
            anyString(), anyInt(), any(), org.mockito.ArgumentMatchers.eq(true), anyBoolean()))
        .thenReturn(page(java.util.List.of(), null));

    handler.handleRequest(
        listVersionsEvent("fraud-detector", Map.of("includePending", "true"), null), null);

    verify(mockVersionDao).list("fraud-detector", 50, null, true, false);
  }

  @Test
  void givenIncludeDeletedTrueWithoutAdmin_whenListVersions_thenReturns403() {
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            listVersionsEvent("fraud-detector", Map.of("includeDeleted", "true"), CALLER_ARN),
            null);

    assertThat(resp.getStatusCode()).isEqualTo(403);
    assertThat(resp.getBody()).contains("FORBIDDEN");
    verify(mockVersionDao, never()).list(anyString(), anyInt(), any(), anyBoolean(), anyBoolean());
  }

  @Test
  void givenIncludeDeletedTrueWithAdmin_whenListVersions_thenFlagPassedToDao() throws Exception {
    String adminArn = "arn:aws:iam::123456789012:role/Admins";
    VersionHandler adminHandler =
        new VersionHandler(
            mockModelDao,
            mockVersionDao,
            mockIncrementer,
            mockS3Client,
            mockPresigner,
            BUCKET,
            MetricsPublisher.noOp(),
            adminArn);
    when(mockVersionDao.list(
            anyString(), anyInt(), any(), anyBoolean(), org.mockito.ArgumentMatchers.eq(true)))
        .thenReturn(page(java.util.List.of(), null));

    APIGatewayProxyResponseEvent resp =
        adminHandler.handleRequest(
            listVersionsEvent("fraud-detector", Map.of("includeDeleted", "true"), adminArn), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    verify(mockVersionDao).list("fraud-detector", 50, null, false, true);
  }

  @Test
  void givenPageToken_whenListVersions_thenTokenPassedThrough() {
    when(mockVersionDao.list("fraud-detector", 50, "abc123", false, false))
        .thenReturn(page(java.util.List.of(), "next-token"));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            listVersionsEvent("fraud-detector", Map.of("pageToken", "abc123"), null), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("\"nextPageToken\":\"next-token\"");
  }

  @Test
  void givenMalformedMaxResults_whenListVersions_thenReturns400() {
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            listVersionsEvent("fraud-detector", Map.of("maxResults", "abc"), null), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("VALIDATION_ERROR");
  }

  // Lock in the 1h TTL: inspect the GetObjectPresignRequest passed to the presigner.
  @Test
  void givenGetLatestVersion_whenPresigning_thenSignatureDurationIsOneHour() {
    Version latest = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.findLatestReady("fraud-detector")).thenReturn(Optional.of(latest));

    handler.handleRequest(getLatestVersionEvent("fraud-detector"), null);

    org.mockito.ArgumentCaptor<GetObjectPresignRequest> captor =
        org.mockito.ArgumentCaptor.forClass(GetObjectPresignRequest.class);
    verify(mockPresigner).presignGetObject(captor.capture());
    assertThat(captor.getValue().signatureDuration()).isEqualTo(java.time.Duration.ofHours(1));
  }

  // ── Story 5.3: DeleteVersion ───────────────────────────────────────────────

  private APIGatewayProxyRequestEvent deleteVersionEvent(
      String modelName, String version, String callerArn) {
    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions/{version}");
    e.setHttpMethod("DELETE");
    e.setPathParameters(Map.of("modelName", modelName, "version", version));
    RequestIdentity identity = new RequestIdentity();
    identity.setCaller(callerArn);
    ProxyRequestContext ctx = new ProxyRequestContext();
    ctx.setIdentity(identity);
    e.setRequestContext(ctx);
    return e;
  }

  @Test
  void givenOwnerCaller_whenDeleteReadyVersion_thenReturns204AndFlipsStatus() {
    Model model = modelAt("fraud-detector", 1, 0); // owner = "alice"
    when(mockModelDao.get("fraud-detector")).thenReturn(Optional.of(model));
    Version v = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(v));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteVersionEvent("fraud-detector", "1.0", CALLER_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(204);
    verify(mockVersionDao)
        .updateStatus("fraud-detector", 1, 0, VersionStatus.DELETED, VersionStatus.READY);
  }

  @Test
  void givenNonOwnerCaller_whenDeleteVersion_thenReturns403() {
    Model model = modelAt("fraud-detector", 1, 0); // owner = "alice"
    when(mockModelDao.get("fraud-detector")).thenReturn(Optional.of(model));

    String mallory = "arn:aws:iam::123456789012:user/mallory";
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteVersionEvent("fraud-detector", "1.0", mallory), null);

    assertThat(resp.getStatusCode()).isEqualTo(403);
    assertThat(resp.getBody()).contains("FORBIDDEN");
    verify(mockVersionDao, never()).updateStatus(anyString(), anyInt(), anyInt(), any(), any());
  }

  @Test
  void givenAdminCaller_whenDeleteVersion_thenReturns204RegardlessOfOwner() {
    String adminArn = "arn:aws:iam::123456789012:role/Admins";
    VersionHandler adminHandler =
        new VersionHandler(
            mockModelDao,
            mockVersionDao,
            mockIncrementer,
            mockS3Client,
            mockPresigner,
            BUCKET,
            MetricsPublisher.noOp(),
            adminArn);

    Model model = modelAt("fraud-detector", 1, 0); // owner = "alice"
    when(mockModelDao.get("fraud-detector")).thenReturn(Optional.of(model));
    Version v = versionWithStatus("fraud-detector", 1, 0, VersionStatus.READY);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(v));

    APIGatewayProxyResponseEvent resp =
        adminHandler.handleRequest(deleteVersionEvent("fraud-detector", "1.0", adminArn), null);

    assertThat(resp.getStatusCode()).isEqualTo(204);
    verify(mockVersionDao)
        .updateStatus("fraud-detector", 1, 0, VersionStatus.DELETED, VersionStatus.READY);
  }

  @Test
  void givenMissingModel_whenDeleteVersion_thenReturns404() {
    when(mockModelDao.get("no-such-model")).thenReturn(Optional.empty());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteVersionEvent("no-such-model", "1.0", CALLER_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("MODEL_NOT_FOUND");
    verify(mockVersionDao, never()).get(anyString(), anyInt(), anyInt());
  }

  @Test
  void givenMissingVersion_whenDeleteVersion_thenReturns404() {
    Model model = modelAt("fraud-detector", 1, 0);
    when(mockModelDao.get("fraud-detector")).thenReturn(Optional.of(model));
    when(mockVersionDao.get("fraud-detector", 9, 9)).thenReturn(Optional.empty());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteVersionEvent("fraud-detector", "9.9", CALLER_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("VERSION_NOT_FOUND");
  }

  @Test
  void givenAlreadyDeletedVersion_whenDeleteVersion_thenIdempotent204NoDdbWrite() {
    Model model = modelAt("fraud-detector", 1, 0);
    when(mockModelDao.get("fraud-detector")).thenReturn(Optional.of(model));
    Version v = versionWithStatus("fraud-detector", 1, 0, VersionStatus.DELETED);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(v));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteVersionEvent("fraud-detector", "1.0", CALLER_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(204);
    verify(mockVersionDao, never()).updateStatus(anyString(), anyInt(), anyInt(), any(), any());
  }

  @Test
  void givenRaceCondition_whenDeleteVersion_thenReturns409() {
    Model model = modelAt("fraud-detector", 1, 0);
    when(mockModelDao.get("fraud-detector")).thenReturn(Optional.of(model));
    Version v = versionWithStatus("fraud-detector", 1, 0, VersionStatus.PENDING);
    when(mockVersionDao.get("fraud-detector", 1, 0)).thenReturn(Optional.of(v));
    org.mockito.Mockito.doThrow(new VersionConflictException("status changed"))
        .when(mockVersionDao)
        .updateStatus("fraud-detector", 1, 0, VersionStatus.DELETED, VersionStatus.PENDING);

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteVersionEvent("fraud-detector", "1.0", CALLER_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(409);
    assertThat(resp.getBody()).contains("VERSION_CONFLICT");
    // Story 7.2: VersionConflict metric emitted with operation=delete_version.
    verify(mockMetrics).recordVersionConflict("delete_version");
  }

  @Test
  void givenMalformedVersionParam_whenDeleteVersion_thenReturns400() {
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            deleteVersionEvent("fraud-detector", "not-a-version", CALLER_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("VALIDATION_ERROR");
    verify(mockModelDao, never()).get(anyString());
  }
}
