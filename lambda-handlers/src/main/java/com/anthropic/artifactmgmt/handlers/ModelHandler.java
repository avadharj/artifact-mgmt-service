package com.anthropic.artifactmgmt.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.anthropic.artifactmgmt.dao.ModelDao;
import com.anthropic.artifactmgmt.exception.ModelAlreadyExistsException;
import com.anthropic.artifactmgmt.model.CreateModelRequest;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.ModelResponse;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.Instant;
import java.util.Map;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class ModelHandler
    implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

  static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
          .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

  private final ModelDao modelDao;

  /** Production constructor — reads MODELS_TABLE_NAME from environment. */
  public ModelHandler() {
    String tableName = System.getenv("MODELS_TABLE_NAME");
    DynamoDbClient dynamo =
        DynamoDbClient.builder()
            .region(Region.of(System.getenv().getOrDefault("AWS_REGION", "us-east-1")))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    this.modelDao =
        new ModelDao(DynamoDbEnhancedClient.builder().dynamoDbClient(dynamo).build(), tableName);
  }

  /** Test constructor — accepts an injected DAO. */
  ModelHandler(ModelDao modelDao) {
    this.modelDao = modelDao;
  }

  @Override
  public APIGatewayProxyResponseEvent handleRequest(
      APIGatewayProxyRequestEvent event, Context ctx) {
    String resource = event.getResource();
    String method = event.getHttpMethod();
    try {
      if ("/models".equals(resource) && "POST".equals(method)) {
        return createModel(event);
      }
      return errorResponse(404, "NOT_FOUND", "Route not found");
    } catch (Exception e) {
      System.err.println("Unhandled exception: " + e.getMessage());
      return errorResponse(500, "INTERNAL_ERROR", "Internal server error");
    }
  }

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
            .latestMajor(0)
            .latestMinor(-1)
            .status("ACTIVE")
            .createdAt(now)
            .updatedAt(now)
            .build();

    try {
      Model created = modelDao.putIfNotExists(model);
      return jsonResponse(201, ModelResponse.from(created));
    } catch (ModelAlreadyExistsException e) {
      // Idempotency: if caller supplied an idempotency key, treat the duplicate as a replay
      // and return the existing model with 200. Without a key, it is a true conflict → 409.
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

  private static String extractOwner(APIGatewayProxyRequestEvent event) {
    try {
      String caller = event.getRequestContext().getIdentity().getCaller();
      if (caller == null || caller.isBlank()) {
        return "unknown";
      }
      // ARN format: arn:aws:iam::123456789012:user/alice → "alice"
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
