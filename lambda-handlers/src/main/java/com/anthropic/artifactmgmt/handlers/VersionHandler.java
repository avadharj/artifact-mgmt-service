package com.anthropic.artifactmgmt.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.anthropic.artifactmgmt.dao.ModelDao;
import com.anthropic.artifactmgmt.dao.VersionDao;
import com.anthropic.artifactmgmt.dao.VersionKey;
import com.anthropic.artifactmgmt.exception.InvalidMajorVersionException;
import com.anthropic.artifactmgmt.exception.ModelNotFoundException;
import com.anthropic.artifactmgmt.exception.VersionConflictException;
import com.anthropic.artifactmgmt.model.ConfirmVersionRequest;
import com.anthropic.artifactmgmt.model.CreateVersionRequest;
import com.anthropic.artifactmgmt.model.CreateVersionResponse;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionResponse;
import com.anthropic.artifactmgmt.model.VersionStatus;
import com.anthropic.artifactmgmt.version.IncrementResult;
import com.anthropic.artifactmgmt.version.VersionIncrementer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

public class VersionHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  private final ModelDao modelDao;
  private final VersionDao versionDao;
  private final VersionIncrementer incrementer;
  private final S3Client s3Client;
  private final S3Presigner s3Presigner;
  private final String bucket;
  private final MetricsPublisher metrics;

  /** Production constructor — reads config from environment. */
  public VersionHandler() {
    String region = System.getenv().getOrDefault("AWS_REGION", "us-east-1");
    DynamoDbClient dynamo =
        DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    DynamoDbEnhancedClient enhanced =
        DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build();
    this.modelDao = new ModelDao(enhanced, System.getenv("MODELS_TABLE"));
    this.versionDao = new VersionDao(dynamo, System.getenv("VERSIONS_TABLE"));
    this.incrementer = new VersionIncrementer();
    this.s3Client =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    this.s3Presigner =
        S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    this.bucket = System.getenv("ARTIFACTS_BUCKET");
    this.metrics = MetricsPublisher.noOp();
  }

  /** Test constructor — accepts injected dependencies. */
  VersionHandler(
      ModelDao modelDao,
      VersionDao versionDao,
      VersionIncrementer incrementer,
      S3Client s3Client,
      S3Presigner s3Presigner,
      String bucket,
      MetricsPublisher metrics) {
    this.modelDao = modelDao;
    this.versionDao = versionDao;
    this.incrementer = incrementer;
    this.s3Client = s3Client;
    this.s3Presigner = s3Presigner;
    this.bucket = bucket;
    this.metrics = metrics;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context ctx) {
    String resource = event.getResource();
    String method = event.getHttpMethod();
    try {
      if ("/models/{modelName}/versions".equals(resource) && "POST".equals(method)) {
        return createVersion(event);
      }
      if ("/models/{modelName}/versions/{version}/confirm".equals(resource)
          && "PUT".equals(method)) {
        return confirmVersion(event);
      }
      if ("/models/{modelName}/versions/latest".equals(resource) && "GET".equals(method)) {
        return getLatestVersion(event);
      }
      if ("/models/{modelName}/versions/{version}".equals(resource) && "GET".equals(method)) {
        return getVersion(event);
      }
      return errorResponse(404, "NOT_FOUND", "Route not found");
    } catch (Exception e) {
      System.err.println("Unhandled exception: " + e.getMessage());
      return errorResponse(500, "INTERNAL_ERROR", "Internal server error");
    }
  }

  // ── CreateVersion ─────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent createVersion(APIGatewayProxyRequestEvent event) {
    Map<String, String> pathParams = event.getPathParameters();
    String modelName = pathParams != null ? pathParams.get("modelName") : null;
    if (modelName == null || modelName.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName path parameter is required");
    }

    CreateVersionRequest req;
    try {
      req = MAPPER.readValue(event.getBody(), CreateVersionRequest.class);
    } catch (Exception e) {
      return errorResponse(400, "VALIDATION_ERROR", "Invalid request body: " + e.getMessage());
    }

    if (req.getIdempotencyKey() == null || req.getIdempotencyKey().isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "idempotencyKey is required");
    }
    if (req.getDepSnapshot() == null) {
      return errorResponse(400, "VALIDATION_ERROR", "depSnapshot is required");
    }

    // Idempotency: the Version row IS the idempotency record (stored on-row via idempotency_key
    // GSI). On replay we re-sign a fresh presigned URL so the client never gets an expired URL,
    // regardless of how much time has passed since the original request. HTTP 200 (not 201)
    // signals "replayed, no new resource created".
    Optional<Version> existing = versionDao.findByIdempotencyKey(req.getIdempotencyKey());
    if (existing.isPresent()) {
      Version v = existing.get();
      // Re-sign with the stored checksum so the replayed URL binds to the same value as the
      // original. If the original was unbound, the replay is unbound too.
      String uploadUrl = presignPutUrl(v.getS3Key(), v.getChecksumSha256());
      Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
      return jsonResponse(
          200,
          CreateVersionResponse.builder()
              .version(v.getMajor() + "." + v.getMinor())
              .status(v.getStatus())
              .uploadUrl(uploadUrl)
              .uploadUrlExpiresAt(expiresAt.toString())
              .build());
    }

    Model model;
    try {
      model = modelDao.get(modelName).orElseThrow(() -> new ModelNotFoundException(modelName));
    } catch (ModelNotFoundException e) {
      return errorResponse(404, "MODEL_NOT_FOUND", e.getMessage());
    }

    IncrementResult incr;
    try {
      incr =
          incrementer.next(
              model.getLatestMajor(), model.getLatestMinor(), Optional.ofNullable(req.getMajor()));
    } catch (InvalidMajorVersionException e) {
      return errorResponse(400, "INVALID_MAJOR_VERSION", e.getMessage());
    }

    try {
      modelDao.updateLatestVersion(
          modelName, incr.newMajor(), incr.newMinor(), incr.expectedCurrentMajor());
    } catch (VersionConflictException e) {
      // e.getCurrentMajor()/getCurrentMinor() reflect the actual current state re-read after
      // the conditional update failed, not the stale pre-fetch.
      return errorResponse(
          409,
          "VERSION_CONFLICT",
          "Concurrent version creation conflict",
          Map.of(
              "current_major", String.valueOf(e.getCurrentMajor()),
              "current_minor", String.valueOf(e.getCurrentMinor())));
    }

    String s3Key =
        String.format("%s/v%d.%d/weights.bin", modelName, incr.newMajor(), incr.newMinor());
    String now = Instant.now().toString();
    String createdBy = extractOwner(event);

    Version v =
        Version.builder()
            .modelName(modelName)
            .major(incr.newMajor())
            .minor(incr.newMinor())
            .versionKey(VersionKey.encode(incr.newMajor(), incr.newMinor()))
            .s3Key(s3Key)
            .status(VersionStatus.PENDING)
            .depSnapshot(req.getDepSnapshot() != null ? req.getDepSnapshot().toString() : null)
            .trainingMetadata(
                req.getTrainingMetadata() != null ? req.getTrainingMetadata().toString() : null)
            .idempotencyKey(req.getIdempotencyKey())
            .ttl(Instant.now().plus(24, ChronoUnit.HOURS).getEpochSecond())
            .createdAt(now)
            .createdBy(createdBy)
            .checksumSha256(req.getChecksumSha256())
            .build();
    versionDao.put(v);

    String uploadUrl = presignPutUrl(s3Key, req.getChecksumSha256());
    Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);

    String framework =
        req.getDepSnapshot() != null
            ? req.getDepSnapshot().path("framework").path("name").asText("unknown")
            : "unknown";
    metrics.recordVersionCreated(framework);

    return jsonResponse(
        201,
        CreateVersionResponse.builder()
            .version(incr.newMajor() + "." + incr.newMinor())
            .status(VersionStatus.PENDING)
            .uploadUrl(uploadUrl)
            .uploadUrlExpiresAt(expiresAt.toString())
            .build());
  }

  // ── ConfirmVersion ────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent confirmVersion(APIGatewayProxyRequestEvent event) {
    Map<String, String> pathParams = event.getPathParameters();
    String modelName = pathParams != null ? pathParams.get("modelName") : null;
    String versionParam = pathParams != null ? pathParams.get("version") : null;

    if (modelName == null || modelName.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName path parameter is required");
    }
    if (versionParam == null || versionParam.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "version path parameter is required");
    }

    int major;
    int minor;
    try {
      String[] parts = versionParam.split("\\.");
      if (parts.length != 2) throw new IllegalArgumentException("expected M.N format");
      major = Integer.parseInt(parts[0]);
      minor = Integer.parseInt(parts[1]);
    } catch (Exception e) {
      return errorResponse(400, "VALIDATION_ERROR", "version must be in M.N format");
    }

    ConfirmVersionRequest req;
    try {
      req = MAPPER.readValue(event.getBody(), ConfirmVersionRequest.class);
    } catch (Exception e) {
      return errorResponse(400, "VALIDATION_ERROR", "Invalid request body: " + e.getMessage());
    }

    // Fetch the version row
    Optional<Version> found = versionDao.get(modelName, major, minor);
    if (found.isEmpty()) {
      return errorResponse(404, "VERSION_NOT_FOUND", "Version not found: " + versionParam);
    }
    Version version = found.get();

    // Idempotent confirm: already READY — verify checksum then return current state
    if (version.getStatus() == VersionStatus.READY) {
      HeadObjectResponse head = headS3Object(version.getS3Key());
      if (head == null) {
        return errorResponse(404, "UPLOAD_NOT_FOUND", "S3 object not found for version");
      }
      if (!checksumMatches(head, req)) {
        return errorResponse(409, "CHECKSUM_MISMATCH", "Checksum does not match stored object");
      }
      return jsonResponse(200, VersionResponse.from(version));
    }

    // Non-PENDING status (DELETED, FAILED) with no idempotent-replay → 412
    if (version.getStatus() != VersionStatus.PENDING) {
      return errorResponse(
          412,
          "PRECONDITION_FAILED",
          "Version status is " + version.getStatus() + ", expected PENDING");
    }

    // Verify upload exists in S3
    HeadObjectResponse head = headS3Object(version.getS3Key());
    if (head == null) {
      return errorResponse(
          404, "UPLOAD_NOT_FOUND", "Upload not found — PUT to the upload URL first");
    }

    // Verify size — reject if client provided sizeBytes and S3 contentLength doesn't match
    if (req.getSizeBytes() != null) {
      if (head.contentLength() == null || !head.contentLength().equals(req.getSizeBytes())) {
        return errorResponse(
            409, "CHECKSUM_MISMATCH", "Content-Length mismatch: expected " + req.getSizeBytes());
      }
    }

    // Verify SHA-256 — reject if client provided checksum and S3 checksum doesn't match
    if (req.getChecksumSha256() != null) {
      if (head.checksumSHA256() == null || !head.checksumSHA256().equals(req.getChecksumSha256())) {
        return errorResponse(409, "CHECKSUM_MISMATCH", "SHA-256 checksum mismatch");
      }
    }

    // Flip PENDING → READY
    versionDao.updateStatus(modelName, major, minor, VersionStatus.READY, VersionStatus.PENDING);

    metrics.recordVersionConfirmed();

    Version confirmed =
        versionDao
            .get(modelName, major, minor)
            .orElse(
                Version.builder()
                    .modelName(version.getModelName())
                    .major(version.getMajor())
                    .minor(version.getMinor())
                    .versionKey(version.getVersionKey())
                    .s3Key(version.getS3Key())
                    .status(VersionStatus.READY)
                    .depSnapshot(version.getDepSnapshot())
                    .trainingMetadata(version.getTrainingMetadata())
                    .idempotencyKey(version.getIdempotencyKey())
                    .createdAt(version.getCreatedAt())
                    .createdBy(version.getCreatedBy())
                    .build());

    // Write metadata mirror with confirmed (READY) state (best-effort)
    writeMetadataMirror(confirmed);

    return jsonResponse(200, VersionResponse.from(confirmed));
  }

  // ── GetVersion ────────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent getVersion(APIGatewayProxyRequestEvent event) {
    Map<String, String> pathParams = event.getPathParameters();
    String modelName = pathParams != null ? pathParams.get("modelName") : null;
    String versionParam = pathParams != null ? pathParams.get("version") : null;
    if (modelName == null || modelName.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName path parameter is required");
    }
    if (versionParam == null || versionParam.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "version path parameter is required");
    }

    int major;
    int minor;
    try {
      String[] parts = versionParam.split("\\.");
      if (parts.length != 2) throw new IllegalArgumentException("expected M.N format");
      major = Integer.parseInt(parts[0]);
      minor = Integer.parseInt(parts[1]);
    } catch (Exception e) {
      return errorResponse(400, "VALIDATION_ERROR", "version must be in M.N format");
    }

    Optional<Version> found = versionDao.get(modelName, major, minor);
    if (found.isEmpty()) {
      return errorResponse(404, "VERSION_NOT_FOUND", "Version not found: " + versionParam);
    }
    Version v = found.get();
    // Per Story 5.1: download URL scoped to GetObject only; only populate for READY rows since
    // the bytes are guaranteed to exist there. PENDING/FAILED/DELETED return metadata with no URL.
    String downloadUrl = null;
    String expiresAt = null;
    if (v.getStatus() == VersionStatus.READY) {
      downloadUrl = presignGetUrl(v.getS3Key());
      expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();
    }
    return jsonResponse(200, VersionResponse.fromWithDownload(v, MAPPER, downloadUrl, expiresAt));
  }

  // ── GetLatestVersion ──────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent getLatestVersion(APIGatewayProxyRequestEvent event) {
    Map<String, String> pathParams = event.getPathParameters();
    String modelName = pathParams != null ? pathParams.get("modelName") : null;
    if (modelName == null || modelName.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName path parameter is required");
    }

    Optional<Version> latest = versionDao.findLatestReady(modelName);
    if (latest.isEmpty()) {
      return errorResponse(404, "VERSION_NOT_FOUND", "No READY version for model: " + modelName);
    }
    Version v = latest.get();
    String downloadUrl = presignGetUrl(v.getS3Key());
    String expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).toString();
    return jsonResponse(200, VersionResponse.fromWithDownload(v, MAPPER, downloadUrl, expiresAt));
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Returns null if the object does not exist in S3. */
  private HeadObjectResponse headS3Object(String s3Key) {
    try {
      // ChecksumMode.ENABLED is required for S3 to return the stored SHA-256 on the response;
      // without it, head.checksumSHA256() is always null even when S3 has the value.
      return s3Client.headObject(
          req -> req.bucket(bucket).key(s3Key).checksumMode(ChecksumMode.ENABLED));
    } catch (NoSuchKeyException e) {
      return null;
    }
  }

  private boolean checksumMatches(HeadObjectResponse head, ConfirmVersionRequest req) {
    if (req.getSizeBytes() != null) {
      if (head.contentLength() == null || !head.contentLength().equals(req.getSizeBytes())) {
        return false;
      }
    }
    if (req.getChecksumSha256() != null) {
      if (head.checksumSHA256() == null || !head.checksumSHA256().equals(req.getChecksumSha256())) {
        return false;
      }
    }
    return true;
  }

  private void writeMetadataMirror(Version v) {
    try {
      String mirrorKey =
          String.format("%s/v%d.%d/metadata.json", v.getModelName(), v.getMajor(), v.getMinor());
      String metadata = MAPPER.writeValueAsString(VersionResponse.from(v));
      s3Client.putObject(
          req -> req.bucket(bucket).key(mirrorKey).contentType("application/json"),
          RequestBody.fromString(metadata, StandardCharsets.UTF_8));
    } catch (Exception e) {
      System.err.println("[warn] Failed to write metadata mirror: " + e.getMessage());
    }
  }

  private String presignPutUrl(String s3Key, String checksumSha256) {
    // When checksumSha256 is provided, bake the value into the presigned URL via
    // PutObjectRequest.checksumSHA256 — S3 will reject the upload unless the client's bytes
    // hash to the same value, and HeadObject will return the checksum so ConfirmVersion's
    // strict verification path (Story 4.5) succeeds. When null, the URL is unbound and the
    // checksum field on Confirm is effectively skipped.
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .putObjectRequest(
                req -> {
                  req.bucket(bucket).key(s3Key).contentType("application/octet-stream");
                  if (checksumSha256 != null && !checksumSha256.isBlank()) {
                    req.checksumSHA256(checksumSha256);
                  }
                })
            .build();
    PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
    return presigned.url().toString();
  }

  /** Presigned S3 GetObject URL with 1h TTL — used by Get/GetLatestVersion (Story 5.1). */
  private String presignGetUrl(String s3Key) {
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .getObjectRequest(req -> req.bucket(bucket).key(s3Key))
            .build();
    PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
    return presigned.url().toString();
  }

  private static String extractOwner(APIGatewayProxyRequestEvent event) {
    try {
      String caller = event.getRequestContext().getIdentity().getCaller();
      if (caller == null || caller.isBlank()) return "unknown";
      int slash = caller.lastIndexOf('/');
      return slash >= 0 ? caller.substring(slash + 1) : caller;
    } catch (Exception e) {
      return "unknown";
    }
  }

  private APIGatewayProxyResponseEvent jsonResponse(int statusCode, Object body) {
    try {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(Map.of("Content-Type", "application/json"))
          .withBody(MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      return errorResponse(500, "SERIALIZATION_ERROR", "Failed to serialize response");
    }
  }

  private APIGatewayProxyResponseEvent errorResponse(int status, String code, String message) {
    return errorResponse(status, code, message, Map.of());
  }

  private APIGatewayProxyResponseEvent errorResponse(
      int status, String code, String message, Map<String, String> details) {
    try {
      java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("code", code);
      body.put("message", message);
      if (!details.isEmpty()) {
        body.put("details", details);
      }
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(status)
          .withHeaders(Map.of("Content-Type", "application/json"))
          .withBody(MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(500)
          .withBody("{\"code\":\"SERIALIZATION_ERROR\"}");
    }
  }
}
