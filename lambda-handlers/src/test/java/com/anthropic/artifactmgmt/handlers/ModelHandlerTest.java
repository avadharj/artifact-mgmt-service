package com.anthropic.artifactmgmt.handlers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.ProxyRequestContext;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent.RequestIdentity;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.anthropic.artifactmgmt.dao.ModelDao;
import com.anthropic.artifactmgmt.exception.AccessDeniedException;
import com.anthropic.artifactmgmt.exception.ModelAlreadyExistsException;
import com.anthropic.artifactmgmt.exception.ModelNotFoundException;
import com.anthropic.artifactmgmt.model.Model;
import com.anthropic.artifactmgmt.model.PaginatedResult;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ModelHandlerTest {

  private static final String ADMIN_ARN = "arn:aws:iam::123:role/admin";

  private ModelDao mockDao;
  private ModelHandler handler;

  @BeforeEach
  void setUp() {
    mockDao = mock(ModelDao.class);
    handler = new ModelHandler(mockDao, ADMIN_ARN);
  }

  // ── Helper builders ───────────────────────────────────────────────────────

  private APIGatewayProxyRequestEvent event(
      String resource,
      String method,
      String callerArn,
      String body,
      Map<String, String> headers,
      Map<String, String> pathParams,
      Map<String, String> queryParams) {
    RequestIdentity identity = new RequestIdentity();
    identity.setCaller(callerArn);
    ProxyRequestContext ctx = new ProxyRequestContext();
    ctx.setIdentity(identity);

    APIGatewayProxyRequestEvent e = new APIGatewayProxyRequestEvent();
    e.setResource(resource);
    e.setHttpMethod(method);
    e.setRequestContext(ctx);
    e.setBody(body);
    e.setHeaders(headers);
    e.setPathParameters(pathParams);
    e.setQueryStringParameters(queryParams);
    return e;
  }

  private APIGatewayProxyRequestEvent postModels(String body, Map<String, String> headers) {
    return event(
        "/models", "POST", "arn:aws:iam::123456789012:user/alice", body, headers, null, null);
  }

  private APIGatewayProxyRequestEvent getModel(
      String modelName, String callerArn, Map<String, String> qs) {
    return event(
        "/models/{modelName}",
        "GET",
        callerArn,
        null,
        Map.of(),
        Map.of("modelName", modelName),
        qs);
  }

  private APIGatewayProxyRequestEvent listModels(String callerArn, Map<String, String> qs) {
    return event("/models", "GET", callerArn, null, Map.of(), null, qs);
  }

  private Model stubModel(String name) {
    return Model.builder()
        .modelName(name)
        .owner("alice")
        .frameworkHint("pytorch")
        .description("test model")
        .latestMajor(0)
        .latestMinor(-1)
        .status("ACTIVE")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-01T00:00:00Z")
        .build();
  }

  private Model deletedModel(String name) {
    return Model.builder()
        .modelName(name)
        .owner("alice")
        .frameworkHint("pytorch")
        .description("test model")
        .latestMajor(0)
        .latestMinor(-1)
        .status("DELETED")
        .createdAt("2026-01-01T00:00:00Z")
        .updatedAt("2026-01-02T00:00:00Z")
        .build();
  }

  // ── CreateModel tests (story 3.2) ─────────────────────────────────────────

  @Test
  void givenValidRequest_whenCreateModel_thenReturns201WithModel() throws Exception {
    when(mockDao.putIfNotExists(any())).thenReturn(stubModel("fraud-detector"));

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
    when(mockDao.get("fraud-detector")).thenReturn(Optional.of(stubModel("fraud-detector")));

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

  // ── GetModel tests (story 3.3) ────────────────────────────────────────────

  @Test
  void givenExistingActiveModel_whenGetModel_thenReturns200WithFullRecord() {
    when(mockDao.get("fraud-detector")).thenReturn(Optional.of(stubModel("fraud-detector")));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            getModel("fraud-detector", "arn:aws:iam::123:user/alice", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("fraud-detector");
    assertThat(resp.getBody()).contains("description");
    assertThat(resp.getBody()).contains("createdAt");
    assertThat(resp.getHeaders()).containsEntry("Cache-Control", "max-age=0, must-revalidate");
  }

  @Test
  void givenMissingModel_whenGetModel_thenReturns404() {
    when(mockDao.get("missing")).thenReturn(Optional.empty());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(getModel("missing", "arn:aws:iam::123:user/alice", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("MODEL_NOT_FOUND");
  }

  @Test
  void givenDeletedModel_whenGetModelWithoutIncludeDeleted_thenReturns404() {
    when(mockDao.get("fraud-detector")).thenReturn(Optional.of(deletedModel("fraud-detector")));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            getModel("fraud-detector", "arn:aws:iam::123:user/alice", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("MODEL_NOT_FOUND");
  }

  @Test
  void givenDeletedModel_whenAdminGetModelWithIncludeDeleted_thenReturns200() {
    when(mockDao.get("fraud-detector")).thenReturn(Optional.of(deletedModel("fraud-detector")));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            getModel("fraud-detector", ADMIN_ARN, Map.of("includeDeleted", "true")), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("DELETED");
  }

  @Test
  void givenDeletedModel_whenNonAdminRequestsIncludeDeleted_thenReturns404() {
    when(mockDao.get("fraud-detector")).thenReturn(Optional.of(deletedModel("fraud-detector")));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(
            getModel(
                "fraud-detector", "arn:aws:iam::123:user/alice", Map.of("includeDeleted", "true")),
            null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
  }

  // ── ListModels tests (story 3.3) ──────────────────────────────────────────

  @Test
  void givenModels_whenListModels_thenReturnsSparseView() {
    when(mockDao.list(anyInt(), isNull(), anyBoolean()))
        .thenReturn(new PaginatedResult<>(List.of(stubModel("fraud-detector")), null));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(listModels("arn:aws:iam::123:user/alice", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("fraud-detector");
    // sparse view: must NOT contain description or timestamps
    assertThat(resp.getBody()).doesNotContain("\"description\"");
    assertThat(resp.getBody()).doesNotContain("\"createdAt\"");
    assertThat(resp.getHeaders()).containsEntry("Cache-Control", "max-age=0, must-revalidate");
  }

  @Test
  void givenLimitOver200_whenListModels_thenClampsTo200() {
    when(mockDao.list(anyInt(), isNull(), anyBoolean()))
        .thenReturn(new PaginatedResult<>(List.of(), null));

    handler.handleRequest(listModels("arn:aws:iam::123:user/alice", Map.of("limit", "500")), null);

    // Verify that the DAO was called with limit clamped to 200
    org.mockito.Mockito.verify(mockDao).list(200, null, false);
  }

  @Test
  void givenPageToken_whenListModels_thenPassesTokenToDao() {
    when(mockDao.list(anyInt(), anyString(), anyBoolean()))
        .thenReturn(new PaginatedResult<>(List.of(), null));

    handler.handleRequest(
        listModels("arn:aws:iam::123:user/alice", Map.of("pageToken", "abc123", "limit", "50")),
        null);

    org.mockito.Mockito.verify(mockDao).list(50, "abc123", false);
  }

  @Test
  void givenPaginatedResult_whenListModels_thenReturnsNextPageToken() {
    when(mockDao.list(anyInt(), isNull(), anyBoolean()))
        .thenReturn(new PaginatedResult<>(List.of(stubModel("m1"), stubModel("m2")), "token-xyz"));

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(listModels("arn:aws:iam::123:user/alice", Map.of()), null);

    assertThat(resp.getStatusCode()).isEqualTo(200);
    assertThat(resp.getBody()).contains("token-xyz");
  }

  @Test
  void givenDefaultLimit_whenListModels_thenUses50() {
    when(mockDao.list(anyInt(), isNull(), anyBoolean()))
        .thenReturn(new PaginatedResult<>(List.of(), null));

    handler.handleRequest(listModels("arn:aws:iam::123:user/alice", Map.of()), null);

    org.mockito.Mockito.verify(mockDao).list(50, null, false);
  }

  // ── DeleteModel tests (story 3.4) ─────────────────────────────────────────

  private APIGatewayProxyRequestEvent deleteModel(String modelName, String callerArn) {
    return event(
        "/models/{modelName}",
        "DELETE",
        callerArn,
        null,
        Map.of(),
        Map.of("modelName", modelName),
        Map.of());
  }

  @Test
  void givenOwnerCaller_whenDeleteModel_thenReturns204() throws Exception {
    org.mockito.Mockito.doNothing()
        .when(mockDao)
        .softDelete(anyString(), anyString(), anyBoolean());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteModel("fraud-detector", "arn:aws:iam::123:user/alice"), null);

    assertThat(resp.getStatusCode()).isEqualTo(204);
  }

  @Test
  void givenMissingModel_whenDeleteModel_thenReturns404() throws Exception {
    org.mockito.Mockito.doThrow(new ModelNotFoundException("fraud-detector"))
        .when(mockDao)
        .softDelete(anyString(), anyString(), anyBoolean());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteModel("fraud-detector", "arn:aws:iam::123:user/alice"), null);

    assertThat(resp.getStatusCode()).isEqualTo(404);
    assertThat(resp.getBody()).contains("MODEL_NOT_FOUND");
  }

  @Test
  void givenWrongOwner_whenDeleteModel_thenReturns403() throws Exception {
    org.mockito.Mockito.doThrow(new AccessDeniedException("not owner"))
        .when(mockDao)
        .softDelete(anyString(), anyString(), anyBoolean());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteModel("fraud-detector", "arn:aws:iam::123:user/mallory"), null);

    assertThat(resp.getStatusCode()).isEqualTo(403);
    assertThat(resp.getBody()).contains("ACCESS_DENIED");
  }

  @Test
  void givenAlreadyDeletedModel_whenDeleteModel_thenReturns204Idempotent() throws Exception {
    // softDelete is idempotent at the DAO level — no exception means 204
    org.mockito.Mockito.doNothing()
        .when(mockDao)
        .softDelete(anyString(), anyString(), anyBoolean());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteModel("fraud-detector", "arn:aws:iam::123:user/alice"), null);

    assertThat(resp.getStatusCode()).isEqualTo(204);
  }

  @Test
  void givenAdminCaller_whenDeleteModel_thenReturns204() throws Exception {
    org.mockito.Mockito.doNothing()
        .when(mockDao)
        .softDelete(anyString(), anyString(), anyBoolean());

    APIGatewayProxyResponseEvent resp =
        handler.handleRequest(deleteModel("fraud-detector", ADMIN_ARN), null);

    assertThat(resp.getStatusCode()).isEqualTo(204);
    // Verify isAdmin=true was passed to the DAO
    org.mockito.Mockito.verify(mockDao).softDelete("fraud-detector", "admin", true);
  }
}
