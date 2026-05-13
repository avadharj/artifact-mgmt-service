$version: "2.0"
namespace com.anthropic.artifactmgmt

@pattern("^\\d+\\.\\d+$")
string VersionId

@length(min: 36, max: 36)
@pattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
string IdempotencyKey

@sensitive
string PresignedUrl

enum VersionStatus {
    PENDING
    READY
    DELETED
    FAILED
}

structure DepSnapshot {
    @required
    pythonVersion: String

    @required
    framework: FrameworkInfo

    @required
    packages: PackageMap

    cudaVersion: String

    @required
    os: String

    @required
    capturedAt: Timestamp
}

structure FrameworkInfo {
    @required
    name: String

    @required
    version: String
}

map PackageMap {
    key: String
    value: String
}

structure TrainingMetadata {
    gitRepo: String

    gitCommit: String

    datasetUri: String

    datasetChecksum: String

    hyperparameters: Document

    metrics: Document

    trainedAt: Timestamp
}

resource ModelVersion {
    identifiers: {
        modelName: ModelName
        version: VersionId
    }
    create: CreateVersion
    read: GetVersion
    list: ListVersions
    delete: DeleteVersion
    operations: [ConfirmVersion]
    collectionOperations: [GetLatestVersion]
}

// ── CreateVersion ─────────────────────────────────────────────────────────────

@http(method: "POST", uri: "/models/{modelName}/versions", code: 201)
operation CreateVersion {
    input: CreateVersionInput
    output: CreateVersionOutput
    errors: [
        ModelNotFoundException
        InvalidMajorVersionException
        VersionConflictException
        IdempotencyMismatchException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure CreateVersionInput {
    @required
    @httpLabel
    modelName: ModelName

    major: Integer

    @required
    idempotencyKey: IdempotencyKey

    @required
    depSnapshot: DepSnapshot

    @required
    trainingMetadata: TrainingMetadata
}

structure CreateVersionOutput {
    @required
    version: VersionId

    @required
    status: VersionStatus

    @required
    uploadUrl: PresignedUrl

    @required
    uploadUrlExpiresAt: Timestamp
}

// ── ConfirmVersion ────────────────────────────────────────────────────────────

@idempotent
@http(method: "PUT", uri: "/models/{modelName}/versions/{version}/confirm", code: 200)
operation ConfirmVersion {
    input: ConfirmVersionInput
    output: ConfirmVersionOutput
    errors: [
        ModelNotFoundException
        VersionNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure ConfirmVersionInput {
    @required
    @httpLabel
    modelName: ModelName

    @required
    @httpLabel
    version: VersionId
}

structure ConfirmVersionOutput {
    @required
    version: VersionId

    @required
    status: VersionStatus
}

// ── GetVersion ────────────────────────────────────────────────────────────────

@readonly
@http(method: "GET", uri: "/models/{modelName}/versions/{version}", code: 200)
operation GetVersion {
    input: GetVersionInput
    output: GetVersionOutput
    errors: [
        ModelNotFoundException
        VersionNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure GetVersionInput {
    @required
    @httpLabel
    modelName: ModelName

    @required
    @httpLabel
    version: VersionId
}

structure GetVersionOutput {
    @required
    version: VersionId

    @required
    status: VersionStatus

    @required
    depSnapshot: DepSnapshot

    @required
    trainingMetadata: TrainingMetadata

    @required
    createdAt: Timestamp

    confirmedAt: Timestamp

    /// Presigned S3 GET URL for the artifact bytes. TTL = 1h. Only populated when status = READY.
    downloadUrl: PresignedUrl

    downloadUrlExpiresAt: Timestamp
}

// ── GetLatestVersion ──────────────────────────────────────────────────────────

@readonly
@http(method: "GET", uri: "/models/{modelName}/versions/latest", code: 200)
operation GetLatestVersion {
    input: GetLatestVersionInput
    output: GetLatestVersionOutput
    errors: [
        ModelNotFoundException
        VersionNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure GetLatestVersionInput {
    @required
    @httpLabel
    modelName: ModelName
}

structure GetLatestVersionOutput {
    @required
    version: VersionId

    @required
    status: VersionStatus

    @required
    depSnapshot: DepSnapshot

    @required
    trainingMetadata: TrainingMetadata

    @required
    createdAt: Timestamp

    confirmedAt: Timestamp

    /// Presigned S3 GET URL for the artifact bytes. TTL = 1h. Always populated (findLatestReady).
    @required
    downloadUrl: PresignedUrl

    @required
    downloadUrlExpiresAt: Timestamp
}

// ── ListVersions ──────────────────────────────────────────────────────────────

@readonly
@paginated(inputToken: "pageToken", outputToken: "nextPageToken", pageSize: "maxResults", items: "versions")
@http(method: "GET", uri: "/models/{modelName}/versions", code: 200)
operation ListVersions {
    input: ListVersionsInput
    output: ListVersionsOutput
    errors: [
        ModelNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure ListVersionsInput {
    @required
    @httpLabel
    modelName: ModelName

    @httpQuery("pageToken")
    pageToken: PageToken

    @httpQuery("maxResults")
    @range(min: 1, max: 200)
    maxResults: Integer

    /// When true, PENDING versions are included in the response. Default: false.
    @httpQuery("includePending")
    includePending: Boolean

    /// When true, DELETED versions are included. Requires the caller's IAM identity to match
    /// ADMIN_ROLE_ARN — otherwise the request is rejected with 403.
    @httpQuery("includeDeleted")
    includeDeleted: Boolean
}

structure ListVersionsOutput {
    @required
    versions: VersionSummaryList

    nextPageToken: PageToken
}

list VersionSummaryList {
    member: VersionSummary
}

structure VersionSummary {
    @required
    version: VersionId

    @required
    status: VersionStatus

    @required
    createdAt: Timestamp

    confirmedAt: Timestamp
}

// ── DeleteVersion ─────────────────────────────────────────────────────────────

@idempotent
@http(method: "DELETE", uri: "/models/{modelName}/versions/{version}", code: 204)
operation DeleteVersion {
    input: DeleteVersionInput
    output: DeleteVersionOutput
    errors: [
        ModelNotFoundException
        VersionNotFoundException
        ValidationException
        ThrottlingException
        InternalServerException
    ]
}

structure DeleteVersionInput {
    @required
    @httpLabel
    modelName: ModelName

    @required
    @httpLabel
    version: VersionId
}

structure DeleteVersionOutput {}

// ── Version-specific errors ───────────────────────────────────────────────────

@error("client")
@httpError(400)
structure InvalidMajorVersionException with [ServiceError] {}

@error("client")
@httpError(409)
structure VersionConflictException with [ServiceError] {}

@error("client")
@httpError(409)
structure IdempotencyMismatchException with [ServiceError] {}

@error("client")
@httpError(404)
structure VersionNotFoundException with [ServiceError] {}
