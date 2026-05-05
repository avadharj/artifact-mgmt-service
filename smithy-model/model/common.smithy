$version: "2.0"
namespace com.anthropic.artifactmgmt

/// Mixin providing the standard error envelope shared across all operation errors.
@mixin
structure ServiceError {
    @required
    code: String

    @required
    message: String

    requestId: String

    /// Structured problem details (field violations, etc.).
    details: Document
}

@error("client")
@httpError(400)
structure ValidationException with [ServiceError] {}

@error("server")
@httpError(500)
structure InternalServerException with [ServiceError] {}

@error("client")
@httpError(429)
@retryable
structure ThrottlingException with [ServiceError] {}

/// Opaque pagination continuation token (base64-encoded LastEvaluatedKey).
string PageToken
