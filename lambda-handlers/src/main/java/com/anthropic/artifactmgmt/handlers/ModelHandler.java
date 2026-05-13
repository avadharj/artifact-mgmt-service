package com.anthropic.artifactmgmt.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.anthropic.artifactmgmt.dao.ModelDao;
import com.anthropic.artifactmgmt.exception.AccessDeniedException;
import com.anthropic.artifactmgmt.exception.ModelAlreadyExistsException;
import com.anthropic.artifactmgmt.exception.ModelNotFoundException;
import com.anthropic.artifactmgmt.model.CreateModelRequest;
import com.anthropic.artifactmgmt.model.ListModelItem;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.ModelResponse;
import com.anthropic.artifactmgmt.model.PaginatedResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class ModelHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";
  private static final int DEFAULT_LIST_LIMIT = 50;
  private static final int MAX_LIST_LIMIT = 200;

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  private final ModelDao modelDao;
  private final String adminRoleArn;

  /** Production constructor — reads config from environment. */
  public ModelHandler() {
    String tableName = System.getenv("MODELS_TABLE");
    DynamoDbClient dynamo =
        DynamoDbClient.builder()
            .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    this.modelDao =
        new ModelDao(DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), tableName);
    this.adminRoleArn = System.getenv().getOrDefault("ADMIN_ROLE_ARN", "");
  }

  /** Test constructor — accepts injected dependencies. */
  ModelHandler(ModelDao modelDao, String adminRoleArn) {
    this.modelDao = modelDao;
    this.adminRoleArn = adminRoleArn;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context ctx) {
    String resource = event.getResource();
    String method = event.getHttpMethod();
    try {
      if ("/models".equals(resource)) {
        if ("POST".equals(method)) return createModel(event);
        if ("GET".equals(method)) return listModels(event);
      }
      if ("/models/{modelName}".equals(resource)) {
        if ("GET".equals(method)) return getModel(event);
        if ("DELETE".equals(method)) return deleteModel(event);
      }
      return errorResponse(404, "NOT_FOUND", "Route not found");
    } catch (Exception e) {
      System.err.println("Unhandled exception: " + e.getMessage());
      return errorResponse(500, "INTERNAL_ERROR", "Internal server error");
    }
  }

  // ── CreateModel ──────────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent createModel(APIGatewayProxyRequestEvent event) {
    CreateModelRequest req;
    try {
      req = MAPPER.readValue(event.getBody(), CreateModelRequest.class);
    } catch (Exception e) {
      return errorResponse(400, "VALIDATION_ERROR", "Invalid request body: " + e.getMessage());
    }

    if (req.getModelName() == null || req.getModelName().isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName is required");
    }

    String owner = extractOwner(event);
    String now = Instant.now().toString();
    Model model =
        Model.builder()
            .modelName(req.getModelName())
            .owner(owner)
            .frameworkHint(req.getFrameworkHint())
            .description(req.getDescription())
            // latestMajor=1, latestMinor=-1 → first VersionIncrementer.next produces (1, 0),
            // making the first user-visible version v1.0 per the spec invariant.
            .latestMajor(1)
            .latestMinor(-1)
            .status("ACTIVE")
            .createdAt(now)
            .updatedAt(now)
            .build();

    try {
      Model created = modelDao.putIfNotExists(model);
      return jsonResponse(201, ModelResponse.from(created));
    } catch (ModelAlreadyExistsException e) {
      Map<String, String> headers = event.getHeaders();
      boolean hasIdempotencyKey = headers != null && headers.containsKey(IDEMPOTENCY_KEY_HEADER);
      if (hasIdempotencyKey) {
        return modelDao
            .get(req.getModelName())
            .map(existing -> jsonResponse(200, ModelResponse.from(existing)))
            .orElseGet(() -> errorResponse(409, "MODEL_ALREADY_EXISTS", e.getMessage()));
      }
      return errorResponse(409, "MODEL_ALREADY_EXISTS", e.getMessage());
    }
  }

  // ── GetModel ─────────────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent getModel(APIGatewayProxyRequestEvent event) {
    Map<String, String> pathParams = event.getPathParameters();
    String modelName = pathParams != null ? pathParams.get("modelName") : null;
    if (modelName == null || modelName.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName path parameter is required");
    }

    boolean includeDeleted = isIncludeDeleted(event) && isAdmin(event);

    Optional<Model> found = modelDao.get(modelName);
    if (found.isEmpty()) {
      return errorResponse(404, "MODEL_NOT_FOUND", "Model not found: " + modelName);
    }
    Model model = found.get();
    if ("DELETED".equals(model.getStatus()) && !includeDeleted) {
      return errorResponse(404, "MODEL_NOT_FOUND", "Model not found: " + modelName);
    }

    return readResponse(200, ModelResponse.from(model));
  }

  // ── ListModels ───────────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent listModels(APIGatewayProxyRequestEvent event) {
    Map<String, String> qs = event.getQueryStringParameters();

    int limit = DEFAULT_LIST_LIMIT;
    if (qs != null && qs.containsKey("limit")) {
      try {
        limit = Integer.parseInt(qs.get("limit"));
      } catch (NumberFormatException e) {
        return errorResponse(400, "VALIDATION_ERROR", "limit must be an integer");
      }
    }
    limit = Math.min(limit, MAX_LIST_LIMIT);
    if (limit <= 0) limit = DEFAULT_LIST_LIMIT;

    String pageToken = qs != null ? qs.get("pageToken") : null;
    boolean includeDeleted = isIncludeDeleted(event) && isAdmin(event);

    PaginatedResult<Model> result = modelDao.list(limit, pageToken, includeDeleted);

    List<ListModelItem> items =
        result.items().stream().map(ListModelItem::from).collect(Collectors.toList());

    Map<String, Object> body = new HashMap<>();
    body.put("items", items);
    body.put("nextPageToken", result.nextPageToken());

    return readResponse(200, body);
  }

  // ── DeleteModel ──────────────────────────────────────────────────────────────

  private APIGatewayProxyResponseEvent deleteModel(APIGatewayProxyRequestEvent event) {
    Map<String, String> pathParams = event.getPathParameters();
    String modelName = pathParams != null ? pathParams.get("modelName") : null;
    if (modelName == null || modelName.isBlank()) {
      return errorResponse(400, "VALIDATION_ERROR", "modelName path parameter is required");
    }

    String owner = extractOwner(event);
    boolean admin = isAdmin(event);

    try {
      modelDao.softDelete(modelName, owner, admin);
      return new APIGatewayProxyResponseEvent().withStatusCode(204).withBody("");
    } catch (ModelNotFoundException e) {
      return errorResponse(404, "MODEL_NOT_FOUND", e.getMessage());
    } catch (AccessDeniedException e) {
      return errorResponse(403, "ACCESS_DENIED", e.getMessage());
    }
  }

  // ── Helpers ──────────────────────────────────────────────────────────────────

  private boolean isIncludeDeleted(APIGatewayProxyRequestEvent event) {
    Map<String, String> qs = event.getQueryStringParameters();
    return qs != null && "true".equalsIgnoreCase(qs.get("includeDeleted"));
  }

  private boolean isAdmin(APIGatewayProxyRequestEvent event) {
    if (adminRoleArn == null || adminRoleArn.isBlank()) return false;
    try {
      String caller = event.getRequestContext().getIdentity().getCaller();
      return adminRoleArn.equals(caller);
    } catch (Exception e) {
      return false;
    }
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

  /** Read response with Cache-Control: no caching. */
  private APIGatewayProxyResponseEvent readResponse(int statusCode, Object body) {
    try {
      return new APIGatewayProxyResponseEvent()
          .withStatusCode(statusCode)
          .withHeaders(
              Map.of(
                  "Content-Type", "application/json",
                  "Cache-Control", "max-age=0, must-revalidate"))
          .withBody(MAPPER.writeValueAsString(body));
    } catch (Exception e) {
      return errorResponse(500, "SERIALIZATION_ERROR", "Failed to serialize response");
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

  private static APIGatewayProxyResponseEvent errorResponse(
      int statusCode, String code, String message) {
    String body =
        String.format("{\"code\":\"%s\",\"message\":\"%s\"}", code, message.replace("\"", "'"));
    return new APIGatewayProxyResponseEvent()
        .withStatusCode(statusCode)
        .withHeaders(Map.of("Content-Type", "application/json"))
        .withBody(body);
  }
}
