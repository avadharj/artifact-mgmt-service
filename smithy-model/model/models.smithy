$version: "2.0"
namespace com.anthropic.artifactmgmt

@pattern("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
@length(min: 3, max: 64)
string ModelName

enum ModelStatus {
    CREATING
    ACTIVE
    DELETING
    FAILED
}

@mixin
structure ModelDetail {
    @required
    name: ModelName

    @required
    status: ModelStatus

    @required
    createdAt: Timestamp

    updatedAt: Timestamp

    description: String
}

resource Model {
    identifiers: {
        modelName: ModelName
    }
    create: CreateModel
    read: GetModel
    list: ListModels
    delete: DeleteModel
    resources: [ModelVersion]
}

// ── CreateModel ──────────────────────────────────────────────────────────────

@http(method: "POST", uri: "/models", code: 201)
operation CreateModel {
    input: CreateModelInput
    output: CreateModelOutput
    errors: [
        ModelAlreadyExistsException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure CreateModelInput {
    @required
    name: ModelName

    description: String
}

structure CreateModelOutput with [ModelDetail] {}

// ── GetModel ─────────────────────────────────────────────────────────────────

@readonly
@http(method: "GET", uri: "/models/{modelName}", code: 200)
operation GetModel {
    input: GetModelInput
    output: GetModelOutput
    errors: [
        ModelNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure GetModelInput {
    @required
    @httpLabel
    modelName: ModelName
}

structure GetModelOutput with [ModelDetail] {}

// ── ListModels ────────────────────────────────────────────────────────────────

@readonly
@paginated(inputToken: "pageToken", outputToken: "nextPageToken", pageSize: "maxResults", items: "models")
@http(method: "GET", uri: "/models", code: 200)
operation ListModels {
    input: ListModelsInput
    output: ListModelsOutput
    errors: [
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure ListModelsInput {
    @httpQuery("pageToken")
    pageToken: PageToken

    @httpQuery("maxResults")
    @range(min: 1, max: 100)
    maxResults: Integer
}

structure ListModelsOutput {
    @required
    models: ModelSummaryList

    nextPageToken: PageToken
}

list ModelSummaryList {
    member: ModelSummary
}

structure ModelSummary {
    @required
    name: ModelName

    @required
    status: ModelStatus

    @required
    createdAt: Timestamp
}

// ── DeleteModel ───────────────────────────────────────────────────────────────

@idempotent
@http(method: "DELETE", uri: "/models/{modelName}", code: 204)
operation DeleteModel {
    input: DeleteModelInput
    output: DeleteModelOutput
    errors: [
        ModelNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure DeleteModelInput {
    @required
    @httpLabel
    modelName: ModelName
}

structure DeleteModelOutput {}

// ── Model-specific errors ─────────────────────────────────────────────────────

@error("client")
@httpError(409)
structure ModelAlreadyExistsException with [ServiceError] {}

@error("client")
@httpError(404)
structure ModelNotFoundException with [ServiceError] {}
