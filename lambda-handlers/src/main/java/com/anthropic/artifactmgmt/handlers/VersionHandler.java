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
import com.anthropic.artifactmgmt.model.CreateVersionRequest;
import com.anthropic.artifactmgmt.model.CreateVersionResponse;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.Version;
import com.anthropic.artifactmgmt.model.VersionStatus;
import com.anthropic.artifactmgmt.version.IncrementResult;
import com.anthropic.artifactmgmt.version.VersionIncrementer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
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
      S3Presigner s3Presigner,
      String bucket,
      MetricsPublisher metrics) {
    this.modelDao = modelDao;
    this.versionDao = versionDao;
    this.incrementer = incrementer;
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
      String uploadUrl = presignPutUrl(v.getS3Key());
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

    // Resolve model
    Model model;
    try {
      model = modelDao.get(modelName).orElseThrow(() -> new ModelNotFoundException(modelName));
    } catch (ModelNotFoundException e) {
      return errorResponse(404, "MODEL_NOT_FOUND", e.getMessage());
    }

    // Compute next version
    IncrementResult incr;
    try {
      incr =
          incrementer.next(
              model.getLatestMajor(), model.getLatestMinor(), Optional.ofNullable(req.getMajor()));
    } catch (InvalidMajorVersionException e) {
      return errorResponse(400, "INVALID_MAJOR_VERSION", e.getMessage());
    }

    // Atomically update the model's latest version counter
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
            .build();
    versionDao.put(v);

    String uploadUrl = presignPutUrl(s3Key);
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

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private String presignPutUrl(String s3Key) {
    PutObjectPresignRequest presignRequest =
        PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofHours(1))
            .putObjectRequest(
                req -> req.bucket(bucket).key(s3Key).contentType("application/octet-stream"))
            .build();
    PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
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
