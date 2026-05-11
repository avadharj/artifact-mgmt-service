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
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

class VersionHandlerTest {

  private ModelDao mockModelDao;
  private VersionDao mockVersionDao;
  private VersionIncrementer mockIncrementer;
  private S3Client mockS3Client;
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
    mockS3Client = mock(S3Client.class);
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
            mockS3Client,
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

  // ── confirmVersion helpers ────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private APIGatewayProxyRequestEvent postConfirm(String modelName, String version, String body) {
    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource("/models/{modelName}/versions/{version}/confirm");
    e.setHttpMethod("POST");
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
}
