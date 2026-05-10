package com.anthropic.artifactmgmt.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.anthropic.artifactmgmt.dao.ModelDao;
import com.anthropic.artifactmgmt.exception.ModelAlreadyExistsException;
import com.anthropic.artifactmgmt.model.Model;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelHandlerTest {

  private ModelDao mockDao;
  private ModelHandler handler;

  @BeforeEach
  void setUp() {
    mockDao = mock(ModelDao.class);
    handler = new ModelHandler(mockDao);
  }

  private APIGatewayProxyRequestEvent postModels(String body, Map<String, String> headers) {
    RequestIdentity identity = new RequestIdentity();
    identity.setCaller("arn:aws:iam::123456789012:user/alice");
    ProxyRequestContext ctx = new ProxyRequestContext();
    ctx.setIdentity(identity);

    APIGatewayProxyRequestEvent event = new APIGatewayProxyRequestEvent();
    event.setResource("/models");
    event.setHttpMethod("POST");
    event.setRequestContext(ctx);
    event.setBody(body);
    event.setHeaders(headers);
    return event;
  }

  private Model stubModel() {
    return Model.builder()
        .modelName("fraud-detector")
        .owner("alice")
        .frameworkHint("pytorch")
        .description("test")
        .latestMajor(0)
        .latestMinor(-1)
        .status("ACTIVE")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-01T00:00:00Z")
        .build();
  }

  @Test
  void givenValidRequest_whenCreateModel_thenReturns201WithModel() throws Exception {
    when(mockDao.putIfNotExists(any())).thenReturn(stubModel());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            postModels(
                "{\"modelName\":\"fraud-detector\",\"frameworkHint\":\"pytorch\","
                    + "\"description\":\"test\"}",
                Map.of()),
            null);

    assertThat(resp.getStatusCode()).isEqualTo(201);
    assertThat(resp.getBody()).contains("fraud-detector");
    assertThat(resp.getBody()).contains("ACTIVE");
  }

  @Test
  void givenDuplicateWithoutIdempotencyKey_whenCreateModel_thenReturns409() throws Exception {
    when(mockDao.putIfNotExists(any()))
        .thenThrow(new ModelAlreadyExistsException("fraud-detector"));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            postModels(
                "{\"modelName\":\"fraud-detector\",\"frameworkHint\":\"pytorch\"}", Map.of()),
            null);

    assertThat(resp.getStatusCode()).isEqualTo(409);
    assertThat(resp.getBody()).contains("MODEL_ALREADY_EXISTS");
  }

  @Test
  void givenDuplicateWithIdempotencyKey_whenCreateModel_thenReturns200WithExistingModel()
      throws Exception {
    when(mockDao.putIfNotExists(any()))
        .thenThrow(new ModelAlreadyExistsException("fraud-detector"));
    when(mockDao.get("fraud-detector")).thenReturn(Optional.of(stubModel()));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            postModels(
                "{\"modelName\":\"fraud-detector\",\"frameworkHint\":\"pytorch\"}",
                Map.of(ModelHandler.IDEMPOTENCY_KEY_HEADER, "key-abc")),
            null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("fraud-detector");
  }

  @Test
  void givenMissingModelName_whenCreateModel_thenReturns400() {
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postModels("{\"frameworkHint\":\"pytorch\"}", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("VALIDATION_ERROR");
  }

  @Test
  void givenMalformedJson_whenCreateModel_thenReturns400() {
    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(postModels("{bad json}", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(400);
    assertThat(resp.getBody()).contains("VALIDATION_ERROR");
  }

  @Test
  void givenOwnerIsParsedFromArn_thenExtractedCorrectly() throws Exception {
    when(mockDao.putIfNotExists(any()))
        .thenAnswer(
            inv -> {
              Model m = inv.getArgument(0);
              assertThat(m.getOwner()).isEqualTo("alice");
              return m;
            });

    handler.handleRequest(
        postModels("{\"modelName\":\"fraud-detector\",\"frameworkHint\":\"pytorch\"}", Map.of()),
        null);
  }
}
