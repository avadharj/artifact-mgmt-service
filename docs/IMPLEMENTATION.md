# Artifact Management Service — Implementation Reference

> **Engineer's reference.** Per-story implementation notes for every epic. Pairs with `artifact_mgmt_implementation_plan.docx` (high-level plan). When the two disagree, the high-level doc wins on intent; this doc wins on mechanics.

**Tech stack:** Smithy → OpenAPI → REST · API Gateway (AWS_IAM auth) · Java 21 Lambda (SnapStart) · DynamoDB · S3 · CDK TypeScript · CodePipeline (deploy) · GitHub Actions (build/test).

**Sizing:** S = a few hours, M = 1–2 days, L = multi-day.

**Glossary:**
- `model_name` — logical model identifier, e.g. `fraud-detector`.
- `version` — string `"M.N"` (e.g. `"3.5"`); persisted as zero-padded `version_key` `"0003.0005"`.
- `dep_snapshot` — captured environment at save time (Python version, framework, packages, CUDA, OS).
- PENDING/READY/DELETED/FAILED — artifact status states.

---

## Stages

Four stages following Amazon-internal shape:

| Stage | Purpose | AWS account | Auto-deploy | Bake time | Approval |
|---|---|---|---|---|---|
| **alpha** | Per-developer sandbox | shared dev account, suffixed (`-alpha-arjun`) | On push to feature branch | none | none |
| **beta** | Integration testing | shared `beta` account | On merge to `main` | 30 min | automated (integration tests pass) |
| **gamma** | Pre-prod, prod-shaped | dedicated `gamma` account | After beta bakes clean | 2 hours | automated (synthetic canaries pass) |
| **prod** | Customer-facing | dedicated `prod` account | After gamma bakes clean | 1 hour | manual approval (one-time per release) |

Each stage has its own DDB tables, S3 bucket, Lambda functions, alarms, and dashboards. No cross-stage data sharing. Rollback is a CodePipeline rollback to the previous CloudFormation template version, triggered automatically if any stage's CloudWatch alarms breach during the bake window.

---

# Epic 1 — Project bootstrap & Smithy contract

**Goal:** Lock the API contract before any handler code. Stand up the dev loop and CI.

## Story 1.1 — Initialize monorepo with Smithy, CDK, Java packages [M]

**Description:** Create the repository skeleton with three top-level packages and the bootstrap script.

**Layout:**
```
artifact-mgmt/
├── smithy-model/              # Smithy IDL files
│   ├── smithy-build.json
│   └── model/
│       └── artifact-mgmt.smithy
├── infra-cdk/                 # CDK TypeScript
│   ├── package.json
│   ├── cdk.json
│   ├── bin/app.ts
│   └── lib/
│       ├── stage-config.ts
│       ├── api-stack.ts
│       ├── data-stack.ts
│       └── compute-stack.ts
├── lambda-handlers/           # Java 21 Lambda code
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── src/main/java/com/anthropic/artifactmgmt/
├── pipeline/                  # CodePipeline CDK stack (separate)
│   └── lib/pipeline-stack.ts
├── scripts/
│   ├── build.sh
│   └── ops/
├── docs/
│   ├── IMPLEMENTATION.md      # this file
│   └── runbooks/
├── .github/workflows/
└── README.md
```

**Implementation notes:**
- Top-level `build.sh`: runs `smithy build` → `gradle build` (in `lambda-handlers/`) → `npm ci && npx cdk synth` (in `infra-cdk/`). Exits non-zero on any step failure.
- `smithy-model/smithy-build.json` declares the `openapi` and `node` (TypeScript) projections plus dependencies on `software.amazon.smithy:smithy-aws-apigateway-openapi`.
- Java toolchain pinned via `gradle.properties` → `org.gradle.java.installations.fromEnv=JAVA_HOME`. Use Gradle wrapper (`./gradlew`) checked in.
- TypeScript: `package-lock.json` committed; `npm ci` (not `npm install`) in CI for reproducibility.
- Pre-commit hooks: `husky` + `lint-staged` running `smithy validate` on `*.smithy` and `gradle spotlessCheck` on `*.java`.

**Acceptance criteria:**
- `./scripts/build.sh` from a clean checkout succeeds with all three packages building.
- `README.md` documents prereqs: Node 20, Java 21 (Corretto), AWS CLI v2, Smithy CLI 1.50+.
- Pre-commit hook blocks a commit that fails `smithy validate` or `spotlessCheck`.
- `.gitignore` excludes `build/`, `cdk.out/`, `node_modules/`, `*.class`, `.gradle/`.

---

## Story 1.2 — Smithy model: Models resource [M]

**Description:** Define the `Model` resource with all CRUD operations and shared shapes (errors, pagination, identifiers).

**Files:**
- `smithy-model/model/artifact-mgmt.smithy` — service shape
- `smithy-model/model/models.smithy` — Model resource
- `smithy-model/model/common.smithy` — shared shapes (errors, pagination)

**Operations:** `CreateModel`, `GetModel`, `ListModels`, `DeleteModel`.

**Implementation notes:**

```smithy
$version: "2.0"
namespace com.anthropic.artifactmgmt

@title("ArtifactManagementService")
@aws.protocols#restJson1
service ArtifactMgmt {
    version: "2026-05-04"
    resources: [Model]
    errors: [ValidationException, InternalServerException, ThrottlingException]
}

resource Model {
    identifiers: { modelName: ModelName }
    create: CreateModel
    read: GetModel
    list: ListModels
    delete: DeleteModel
    resources: [ModelVersion]   // declared in story 1.3
}

@pattern("^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$")
@length(min: 3, max: 64)
string ModelName

@idempotent
@http(method: "POST", uri: "/models", code: 201)
operation CreateModel {
    input: CreateModelInput
    output: CreateModelOutput
    errors: [ModelAlreadyExistsException, ValidationException]
}
```

- All operations use `@http` traits matching the API surface in the high-level doc §2.5.
- `@idempotent` on `CreateModel`; `@readonly` on `GetModel`/`ListModels`.
- Shared `ServiceError` mixin for error envelope (`code`, `message`, `requestId`, `details`).
- Pagination shapes: `@paginated(inputToken: "pageToken", outputToken: "nextPageToken", pageSize: "limit")` on `ListModels`.
- `ModelName` constrained at the type level; rejects uppercase, underscores, leading/trailing hyphens.

**Acceptance criteria:**
- `smithy validate` passes with zero warnings.
- All four operations have matching `@http` traits.
- The error envelope shape is reused across operations via mixin (no duplication).
- Negative test: a malformed `ModelName` (e.g. `Fraud_Detector`) fails Smithy lint.

> **Handoff note (implemented):** `ServiceError` mixin lives in `common.smithy`. The `@sensitive` trait must be applied to a standalone type, not to a member — Smithy 2.0 rejects `@sensitive` on struct members directly. All error shapes use `with [ServiceError]` mixin syntax. `smithy validate` (not `smithy build`) is used in CI to avoid downloading the Maven plugin JARs — the `smithy-build.json` has a `repositories` block pointing at Maven Central so the validate step can resolve its own deps.

---

## Story 1.3 — Smithy model: ModelVersion resource [M]

**Description:** Define `ModelVersion` as a sub-resource of `Model`, including `DepSnapshot` and `TrainingMetadata` shapes that are the cross-service contract.

**File:** `smithy-model/model/versions.smithy`

**Operations:** `CreateVersion`, `ConfirmVersion`, `GetVersion`, `GetLatestVersion`, `ListVersions`, `DeleteVersion`.

**Implementation notes:**

```smithy
resource ModelVersion {
    identifiers: { modelName: ModelName, version: VersionId }
    create: CreateVersion
    read: GetVersion
    list: ListVersions
    delete: DeleteVersion
    operations: [ConfirmVersion, GetLatestVersion]
}

@pattern("^\\d+\\.\\d+$")
string VersionId   // "3.5", "10.0", etc. — server-side stored as "0003.0005"

@http(method: "POST", uri: "/models/{modelName}/versions", code: 201)
operation CreateVersion {
    input: CreateVersionInput
    output: CreateVersionOutput
    errors: [
        ModelNotFoundException,
        InvalidMajorVersionException,
        VersionConflictException,
        IdempotencyMismatchException,
        ValidationException,
    ]
}

structure CreateVersionInput {
    @required @httpLabel modelName: ModelName
    major: Integer                           // optional; absent = minor bump
    @required idempotencyKey: IdempotencyKey
    @required depSnapshot: DepSnapshot
    @required trainingMetadata: TrainingMetadata
}

structure CreateVersionOutput {
    @required version: VersionId
    @required status: VersionStatus
    @required @sensitive uploadUrl: String
    @required uploadUrlExpiresAt: Timestamp
}

structure DepSnapshot {
    @required pythonVersion: String          // "3.11.7"
    @required framework: FrameworkInfo
    @required packages: PackageMap           // map<String, String>
    cudaVersion: String                      // nullable
    @required os: String                     // "linux-x86_64"
    @required capturedAt: Timestamp
}

structure FrameworkInfo {
    @required name: String                   // "pytorch" | "tensorflow" | "sklearn"
    @required version: String
}

map PackageMap { key: String, value: String }

structure TrainingMetadata {
    gitRepo: String
    gitCommit: String
    datasetUri: String
    datasetChecksum: String
    hyperparameters: Document                // arbitrary JSON
    metrics: Document
    trainedAt: Timestamp
}

@length(min: 36, max: 36)
@pattern("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
string IdempotencyKey

enum VersionStatus { PENDING, READY, DELETED, FAILED }
```

- `@sensitive` on `uploadUrl` so generated SDKs redact it from logs.
- `Document` type used for `hyperparameters`/`metrics` to preserve schema flexibility — additive policy documented in `dep_snapshot.md`.
- `VersionId` is a string at the API layer; the `MMMM.NNNN` encoding is a server-side detail (Story 4.1).

**Acceptance criteria:**
- `DepSnapshot` and `TrainingMetadata` schemas exactly match high-level doc §2.3.
- `VersionId` rejects `"3"`, `"3.5.1"`, `"v3.5"`.
- `idempotencyKey` rejects non-UUID input at Smithy validation time.
- `uploadUrl` carries `@sensitive`.

> **Handoff note (implemented):** `GetLatestVersion` is in `collectionOperations` (not `operations`) on `ModelVersion` because it only takes `modelName` and has no `version` identifier in the path — Smithy requires collection operations for operations that don't address a specific resource instance. `ConfirmVersion` is in `operations` (has both identifiers). `@sensitive` is on the `PresignedUrl` string type, not the member — Smithy 2.0 does not allow `@sensitive` on struct members.

---

## Story 1.4 — Smithy build pipeline: OpenAPI + Python SDK + Java SDK [M]

**Description:** Wire the Smithy build to emit OpenAPI 3.0 plus generated Python and Java SDK packages.

**Implementation notes:**
- `smithy-build.json` declares two projections: `openapi` (uses `aws-apigateway-openapi` plugin) and `node` (TypeScript model for the CDK stack).
- A `Makefile` target `make sdk` runs:
  1. `smithy build` → emits `build/smithyprojections/openapi/artifact-mgmt.openapi.json`.
  2. `openapi-generator-cli generate -g python -i ...openapi.json -o build/python-sdk`.
  3. `openapi-generator-cli generate -g java -i ...openapi.json -o build/java-sdk`.
  4. Builds the Python wheel (`python -m build`) and Java JAR (`./gradlew :java-sdk:build`).
- Python generator config: `--additional-properties=packageName=artifact_mgmt_client,projectName=artifact-mgmt-client`. Pin `openapi-generator-cli` to a specific version in `package.json`.
- The OpenAPI output is also consumed by `infra-cdk/lib/api-stack.ts` (Story 2.5) — single source of truth.

**Acceptance criteria:**
- `make sdk` from a clean checkout produces a `.whl` and a `.jar`.
- `pip install build/python-sdk/dist/*.whl` into a fresh venv succeeds; `python -c "import artifact_mgmt_client"` works.
- OpenAPI output validates against the OpenAPI 3.0 schema (`openapi-generator-cli validate`).
- The Java SDK JAR has a `module-info.class` and exports `com.anthropic.artifactmgmt.client.*`.

> **Handoff note (implemented):** The npm wrapper (`@openapitools/openapi-generator-cli`) is NOT used to run the generator. The `Makefile` downloads `openapi-generator-cli-7.9.0.jar` directly from Maven Central to `build/openapi-generator-cli.jar` and invokes `java -jar` directly. This bypasses the wrapper's hidden preflight `exec()` call that was intercepting stderr and crashing. The generator version is 7.9.0 (not 7.4.0 — AdaCodegen was broken in 7.4.0). No `--add-modules` JVM flags are needed. The JAR path is the Make variable `$(OPENAPI_GENERATOR_JAR)`.

---

## Story 1.5 — GitHub Actions: PR validation [S]

**Description:** Single workflow that runs on every PR; failure blocks merge.

**File:** `.github/workflows/pr.yml`

**Steps:**
1. Checkout, setup Node 20, setup Java 21 (Corretto), setup Python 3.11.
2. Cache: `~/.gradle/caches`, `~/.npm`, `~/.smithy`.
3. `./scripts/build.sh` (Smithy validate + Gradle build + CDK synth).
4. `./gradlew test --info` — Java unit tests.
5. `cd infra-cdk && npm test` — CDK unit tests (Jest snapshots).
6. `make sdk` — verify SDK generation works.
7. Upload Gradle test reports as PR artifact.

**Implementation notes:**
- Trigger on `pull_request` to `main`.
- Use `actions/cache@v4` with cache keys including lockfile hashes for invalidation.
- `permissions: contents: read, pull-requests: write` (for posting test summaries via `dorny/test-reporter`).
- Concurrency group: `pr-${{ github.head_ref }}` with `cancel-in-progress: true`.

**Acceptance criteria:**
- Workflow runs on every PR.
- Failure on any step fails the workflow (no `continue-on-error`).
- Test reports show in PR summary.
- Re-running the workflow on an unchanged PR completes <2 min from cache.

> **Handoff note (implemented):** Java 21 (Corretto) is used for Gradle / Lambda handler builds. A separate `Set up Java 11 (Corretto)` step runs immediately before `make sdk` and overrides `JAVA_HOME`/`PATH` inline for that step only. This is required because the Caffeine version bundled in openapi-generator 7.9.0 needs `sun.misc.Unsafe`, which is absent from stripped Corretto 21 CI images but accessible in Java 11 classpath mode with no flags. Test results are published via `mikepenz/action-junit-report@v4` (not dorny — dorny requires artifacts to be uploaded first). Smithy Maven deps are pre-downloaded into `~/.m2` during the install step so `smithy validate` never hits the network mid-build.

---

# Epic 2 — CDK infrastructure & deployment pipeline

**Goal:** Provision all AWS resources via CDK; stand up the four-stage deployment pipeline.

## Story 2.1 — Stack scaffold with stage config [M]

**Description:** Per-stage configuration object and one stack instance per stage.

**Files:** `infra-cdk/lib/stage-config.ts`, `infra-cdk/bin/app.ts`.

**Implementation notes:**

```typescript
export interface StageConfig {
  stage: 'alpha' | 'beta' | 'gamma' | 'prod';
  account: string;
  region: string;
  removalPolicy: cdk.RemovalPolicy;
  ddbBilling: dynamodb.BillingMode;
  ddbProvisionedCapacity?: { read: number; write: number };
  lambdaSnapStart: boolean;
  lambdaProvisionedConcurrency?: number;
  alarmEmail: string;
  enableXRay: boolean;
}

export const STAGES: Record<string, StageConfig> = {
  alpha: { stage: 'alpha', account: '...', region: 'us-east-1',
           removalPolicy: cdk.RemovalPolicy.DESTROY,
           ddbBilling: dynamodb.BillingMode.PAY_PER_REQUEST,
           lambdaSnapStart: false, alarmEmail: 'arjun@...',
           enableXRay: true },
  // beta, gamma, prod...
};
```

- `bin/app.ts` reads `--context stage=<name>` and instantiates one `ArtifactMgmtStack` per stage.
- Resource naming: `artifact-mgmt-{resource}-{stage}` (e.g. `artifact-mgmt-models-table-gamma`).
- Removal policies: `DESTROY` for alpha/beta, `RETAIN` for gamma/prod tables and bucket.
- Per-stage Lambda config: alpha uses 256 MB / no SnapStart for fast iteration; prod uses 1024 MB + SnapStart + provisioned concurrency=5.

**Acceptance criteria:**
- `npx cdk synth -c stage=alpha` produces a CloudFormation template with alpha-suffixed resources.
- Synthesizing all four stages produces four distinct templates.
- Snapshot tests in `infra-cdk/test/` pin the synthesized template per stage.
- Tagging: every resource carries `Stage`, `Service=ArtifactMgmt`, `Owner=arjun`.

> **Handoff note (implemented):** Snapshot tests live in `infra-cdk/test/infra-cdk.test.ts` and cover all four stages. They must be updated with `npm test -- -u` whenever CDK resources change intentionally — failing to do so will fail CI. The `StageConfig` interface has `lambdaMemoryMB` and `lambdaProvisionedConcurrency` fields in addition to what's in the spec above.

---

## Story 2.2 — DynamoDB tables and GSIs [M]

**Description:** Provision Models and Versions tables with PITR, TTL, and two GSIs on Versions.

**File:** `infra-cdk/lib/data-stack.ts`.

**Schema:**
- Models: PK `model_name` (string).
- Versions: PK `model_name` (string), SK `version_key` (string, padded `MMMM.NNNN`).
- GSI `idempotency-gsi`: PK `idempotency_key`, projection `ALL`.
- GSI `status-created-gsi`: PK `status`, SK `created_at`, projection `KEYS_ONLY` + `s3_key` + `idempotency_key`.

**Implementation notes:**

```typescript
const versionsTable = new dynamodb.Table(this, 'VersionsTable', {
  tableName: `artifact-mgmt-versions-${cfg.stage}`,
  partitionKey: { name: 'model_name', type: dynamodb.AttributeType.STRING },
  sortKey: { name: 'version_key', type: dynamodb.AttributeType.STRING },
  billingMode: cfg.ddbBilling,
  pointInTimeRecovery: true,
  timeToLiveAttribute: 'ttl',
  removalPolicy: cfg.removalPolicy,
  encryption: dynamodb.TableEncryption.AWS_MANAGED,
});

versionsTable.addGlobalSecondaryIndex({
  indexName: 'idempotency-gsi',
  partitionKey: { name: 'idempotency_key', type: dynamodb.AttributeType.STRING },
  projectionType: dynamodb.ProjectionType.ALL,
});
```

- TTL on `ttl` attribute (epoch seconds); set on PENDING rows, cleared on confirm.
- For provisioned (gamma/prod): wrap with `appautoscaling` constructs targeting 70% utilization.
- Export table names and ARNs as stack outputs for cross-stack references in `compute-stack.ts`.

**Acceptance criteria:**
- PITR enabled on both tables.
- TTL configured on Versions for `ttl` attribute.
- `idempotency-gsi` and `status-created-gsi` both present with correct projections.
- For prod: provisioned capacity with autoscaling 5–500 RCU/WCU targeting 70%.
- CDK snapshot test verifies the structure.

> **Handoff note (implemented):** `status-created-gsi` uses `ProjectionType.INCLUDE` with `nonKeyAttributes: ['s3_key', 'idempotency_key']` (not KEYS_ONLY — the spec description is slightly inconsistent; the note "KEYS_ONLY + s3_key + idempotency_key" means INCLUDE with those two extras). Autoscaling is applied to all 8 targets (table read, table write, idempotency-gsi read, idempotency-gsi write, status-created-gsi read, status-created-gsi write × both tables) for gamma and prod only. Both tables use `TableEncryption.AWS_MANAGED`.

---

## Story 2.3 — S3 artifact bucket [S]

**Description:** Bucket with versioning, encryption, public access block, lifecycle.

**File:** `infra-cdk/lib/data-stack.ts`.

**Implementation notes:**

```typescript
const bucket = new s3.Bucket(this, 'ArtifactsBucket', {
  bucketName: `artifact-mgmt-${cfg.stage}-${cfg.account}`,  // unique per account
  versioned: true,
  encryption: s3.BucketEncryption.S3_MANAGED,
  blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
  enforceSSL: true,
  removalPolicy: cfg.removalPolicy,
  lifecycleRules: [
    { id: 'abort-incomplete-multipart', abortIncompleteMultipartUploadAfter: cdk.Duration.days(7) },
    { id: 'expire-noncurrent-versions', noncurrentVersionExpiration: cdk.Duration.days(90) },
  ],
});

bucket.addToResourcePolicy(new iam.PolicyStatement({
  effect: iam.Effect.DENY,
  principals: [new iam.AnyPrincipal()],
  actions: ['s3:*'],
  resources: [bucket.bucketArn, `${bucket.bucketArn}/*`],
  conditions: { Bool: { 'aws:SecureTransport': 'false' } },
}));
```

- `enforceSSL: true` adds the deny-non-TLS policy automatically.
- CORS not needed (presigned URLs are signed; clients PUT directly without browser CORS issues if using SDK clients). Add CORS only if a future browser uploader is added.
- S3 server access logging into a separate logging bucket (`artifact-mgmt-logs-{stage}`).

**Acceptance criteria:**
- All four `BlockPublicAccess` settings on.
- Bucket versioning enabled.
- Incomplete multipart uploads abandoned after 7 days.
- HTTP requests rejected (403 from the bucket policy).
- CDK snapshot stable.

---

## Story 2.4 — Lambda functions and IAM [M]

**Description:** Three handler Lambdas + one sweeper Lambda, each with least-privilege IAM.

**File:** `infra-cdk/lib/compute-stack.ts`.

**Functions:**
- `ModelHandler` — Models CRUD (Stories 3.x). DDB access: Models table only.
- `VersionHandler` — Versions CRUD + create/confirm (Stories 4.x, 5.x). DDB: both tables. S3: PUT/GET/HEAD on artifact bucket.
- `AdminHandler` — soft delete, sweep orphans (Story 6.x). DDB: both tables (delete). S3: DELETE.
- `SweeperHandler` — invoked by EventBridge hourly. Same perms as AdminHandler but read-only on Models.

**Implementation notes:**

```typescript
const versionHandler = new lambda.Function(this, 'VersionHandler', {
  functionName: `artifact-mgmt-version-${cfg.stage}`,
  runtime: lambda.Runtime.JAVA_21,
  architecture: lambda.Architecture.ARM_64,
  handler: 'com.anthropic.artifactmgmt.handlers.VersionHandler::handleRequest',
  code: lambda.Code.fromAsset('../lambda-handlers/build/distributions/handlers.zip'),
  memorySize: cfg.lambdaMemoryMB ?? 512,
  timeout: cdk.Duration.seconds(30),
  tracing: cfg.enableXRay ? lambda.Tracing.ACTIVE : lambda.Tracing.DISABLED,
  snapStart: cfg.lambdaSnapStart ? lambda.SnapStartConf.ON_PUBLISHED_VERSIONS : undefined,
  environment: {
    MODELS_TABLE: modelsTable.tableName,
    VERSIONS_TABLE: versionsTable.tableName,
    ARTIFACTS_BUCKET: bucket.bucketName,
    POWERTOOLS_SERVICE_NAME: 'artifact-mgmt',
    POWERTOOLS_METRICS_NAMESPACE: 'ArtifactMgmt',
    LOG_LEVEL: cfg.stage === 'prod' ? 'INFO' : 'DEBUG',
  },
});

modelsTable.grantReadData(versionHandler);
versionsTable.grantReadWriteData(versionHandler);
bucket.grantPut(versionHandler);
bucket.grantRead(versionHandler);
```

- ARM64 / Graviton2 — ~20% cheaper than x86, fully supported on Java 21.
- SnapStart on gamma/prod for cold-start mitigation.
- Provisioned concurrency on prod only (5 instances baseline).
- DLQ: SQS queue per Lambda for async invocation failures.

**Acceptance criteria:**
- Each Lambda has a distinct execution role.
- IAM policies are least-privilege (e.g. ModelHandler can't read Versions table).
- X-Ray enabled on all four functions in beta+.
- SnapStart enabled on gamma+prod.
- DLQs created and wired.

---

## Story 2.5 — API Gateway from generated OpenAPI [M]

**Description:** REST API loaded from the OpenAPI spec generated by Smithy; all routes wired to Lambda integrations with AWS_IAM auth.

**File:** `infra-cdk/lib/api-stack.ts`.

**Implementation notes:**

```typescript
const openApi = JSON.parse(fs.readFileSync(
  path.join(__dirname, '../../smithy-model/build/smithyprojections/openapi/artifact-mgmt.openapi.json'),
  'utf8'));

// Inject AWS_IAM auth and Lambda integrations into every operation
for (const path of Object.values(openApi.paths)) {
  for (const op of Object.values(path)) {
    op.security = [{ sigv4: [] }];
    op['x-amazon-apigateway-integration'] = {
      type: 'aws_proxy',
      httpMethod: 'POST',
      uri: pickHandlerArn(op),  // ModelHandler vs VersionHandler vs AdminHandler
      passthroughBehavior: 'when_no_match',
    };
  }
}

const api = new apigateway.SpecRestApi(this, 'Api', {
  apiDefinition: apigateway.ApiDefinition.fromInline(openApi),
  deployOptions: {
    stageName: cfg.stage,
    tracingEnabled: cfg.enableXRay,
    metricsEnabled: true,
    loggingLevel: apigateway.MethodLoggingLevel.INFO,
    dataTraceEnabled: false,  // NEVER true in prod (logs request bodies)
  },
});
```

- `pickHandlerArn` is a function mapping `operationId` → handler Lambda ARN. Operations like `CreateModel`, `GetModel` go to ModelHandler; `CreateVersion`, `ConfirmVersion` etc. go to VersionHandler.
- Request validation happens automatically via the OpenAPI schema.
- Throttling: 100 req/s burst, 50 req/s sustained per stage (more on prod).
- CloudWatch logs role configured at the account level (one-time setup).

**Acceptance criteria:**
- Every route enforces AWS_IAM auth (verified via `aws apigateway test-invoke-method` with no creds → 403).
- Malformed bodies rejected before Lambda invocation (verified via integ test).
- `dataTraceEnabled: false` on prod stage.
- API Gateway access logs flow to CloudWatch.

---

## Story 2.6 — EventBridge sweeper schedule [S]

**Description:** Hourly schedule invoking SweeperHandler.

**Implementation notes:**

```typescript
new events.Rule(this, 'SweeperSchedule', {
  schedule: events.Schedule.cron({ minute: '0' }),
  targets: [new targets.LambdaFunction(sweeperHandler, {
    deadLetterQueue: sweeperDlq,
    maxEventAge: cdk.Duration.minutes(30),
    retryAttempts: 2,
  })],
});
```

- Concurrency: configure `reservedConcurrentExecutions: 1` on SweeperHandler so two sweeps can never overlap.
- DLQ alarm: any message in the DLQ triggers a Sev3.

**Acceptance criteria:**
- Rule fires at minute 0 of every hour.
- `reservedConcurrentExecutions=1`.
- DLQ wired with alarm.

---

## Story 2.7 — CodePipeline: alpha → beta → gamma → prod [L]

**Description:** Self-mutating CodePipeline driven by CDK, with the four-stage promotion chain.

**File:** `pipeline/lib/pipeline-stack.ts`.

**Stages:**
1. **Source** — GitHub webhook on `main`.
2. **Build** — CodeBuild project running `./scripts/build.sh` + `make sdk` + Gradle tests. Produces synthesized CDK assemblies for all four stages.
3. **Self-mutate** — pipeline updates itself if `pipeline/` has changed.
4. **Deploy alpha** — auto-deploy. No bake.
5. **Deploy beta** — auto-deploy + run integration tests (Story 3.5, 4.6) against beta. 30-min bake watching alarms.
6. **Deploy gamma** — synthetic canary tests (Story 7.6, deferred). 2-hour bake.
7. **Deploy prod** — manual approval gate. 1-hour bake post-deploy.

**Implementation notes:**
- Use `pipelines.CodePipeline` (the L3 construct), not the L2 `aws-codepipeline`. The L3 is the self-mutating one.
- Each `addStage` returns a `StageDeployment`; chain `addPre()` and `addPost()` for tests and bake checks.
- Bake check = a `ShellStep` that polls CloudWatch alarms for the stage's metric set; non-zero exit if any breach.
- Rollback: configure `executionMode: ExecutionMode.QUEUED` and explicit rollback action on alarm trip.
- Cross-account: each stage's CDK stack assumes a deploy role in the target account. CDK bootstrap with `--trust` flag in each target account, trusting the pipeline account.

```typescript
const pipeline = new pipelines.CodePipeline(this, 'Pipeline', {
  pipelineName: 'artifact-mgmt-pipeline',
  synth: new pipelines.ShellStep('Synth', {
    input: pipelines.CodePipelineSource.gitHub('arjun/artifact-mgmt', 'main'),
    commands: ['./scripts/build.sh', 'make sdk', 'cd infra-cdk && npx cdk synth'],
    primaryOutputDirectory: 'infra-cdk/cdk.out',
  }),
});

pipeline.addStage(new ServiceStage(this, 'Alpha', { stage: 'alpha' }));

const beta = pipeline.addStage(new ServiceStage(this, 'Beta', { stage: 'beta' }));
beta.addPost(
  new pipelines.ShellStep('IntegTests', { commands: ['./scripts/integ-test.sh beta'] }),
  new pipelines.ShellStep('BakeCheck', {
    commands: ['python scripts/bake-check.py --stage beta --duration 1800'],
  }),
);

const gamma = pipeline.addStage(new ServiceStage(this, 'Gamma', { stage: 'gamma' }));
gamma.addPost(
  new pipelines.ShellStep('Canaries', { commands: ['./scripts/run-canaries.sh gamma'] }),
  new pipelines.ShellStep('BakeCheck', {
    commands: ['python scripts/bake-check.py --stage gamma --duration 7200'],
  }),
);

const prod = pipeline.addStage(new ServiceStage(this, 'Prod', { stage: 'prod' }));
prod.addPre(new pipelines.ManualApprovalStep('PromoteToProd'));
prod.addPost(
  new pipelines.ShellStep('BakeCheck', {
    commands: ['python scripts/bake-check.py --stage prod --duration 3600'],
  }),
);
```

**Acceptance criteria:**
- Push to `main` triggers pipeline within 1 min.
- Alpha deploy succeeds without manual intervention.
- Beta deploy is gated on integration tests passing.
- Gamma deploy is gated on canaries passing.
- Prod requires explicit manual approval.
- Each bake check fails if any stage alarm breaches; pipeline auto-rolls-back.
- Pipeline self-mutates: a change to `pipeline/lib/pipeline-stack.ts` is picked up on the next run without manual `cdk deploy`.

---

## Story 2.8 — Brazil-style dependency pinning [S]

**Description:** Lock every dependency version explicitly so builds are reproducible across machines and time.

**Implementation notes:**
- TypeScript: commit `package-lock.json`. CI uses `npm ci`. No `^` or `~` ranges in `package.json` — use exact versions.
- Java: use Gradle's lockfile. Run `./gradlew dependencies --write-locks` once; commit `gradle.lockfile` per subproject. Add `dependencyLocking { lockAllConfigurations() }` in root `build.gradle.kts`.
- Python (SDK + scripts): `pip-tools` with `requirements.in` → `requirements.txt`. Hash-pinned (`pip-compile --generate-hashes`).
- Smithy: pin model dependencies via `smithy-build.json` `dependencies` block to exact versions, not ranges.
- CodeArtifact: internal Java SDK + Python SDK published to a private CodeArtifact repo. Pipeline tags releases with semver matching the Smithy model `version`.

**Acceptance criteria:**
- `git status` is clean after `npm ci && ./gradlew build` (no lockfile drift).
- Adding a new dependency without updating the lockfile fails CI.
- Internal SDKs publish to CodeArtifact on every successful prod deploy, tagged with the service version.

---

# Epic 3 — Models resource handlers

**Goal:** End-to-end CRUD for Model. No version logic yet.

## Story 3.1 — ModelDao [M]

**Description:** DynamoDB access layer for the Models table.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/dao/ModelDao.java`.

**Method shapes:**

```java
public class ModelDao {
    private final DynamoDbEnhancedClient client;
    private final DynamoDbTable<ModelRecord> table;

    public Model putIfNotExists(Model model) throws ModelAlreadyExistsException;
    public Optional<Model> get(String modelName);
    public PaginatedResult<Model> list(int limit, String pageToken, boolean includeDeleted);
    public void softDelete(String modelName, String expectedOwner) throws ModelNotFoundException, AccessDeniedException;
    public void updateLatestVersion(String modelName, int newMajor, int newMinor, int expectedMajor)
        throws VersionConflictException;
}

@DynamoDbBean
public class ModelRecord {
    @DynamoDbPartitionKey public String getModelName() { ... }
    public String getOwner() { ... }
    public String getFrameworkHint() { ... }
    public String getDescription() { ... }
    public Integer getLatestMajor() { ... }
    public Integer getLatestMinor() { ... }
    public String getStatus() { ... }   // ACTIVE | DELETED
    public String getCreatedAt() { ... }
    public String getUpdatedAt() { ... }
}
```

**Implementation notes:**
- Use AWS SDK v2 `DynamoDbEnhancedClient` with `@DynamoDbBean` POJO.
- `putIfNotExists` uses conditional expression `attribute_not_exists(model_name)`. Catch `ConditionalCheckFailedException` → throw `ModelAlreadyExistsException`.
- `list` paginates: encode `LastEvaluatedKey` as base64 JSON in the response token; decode on next request.
- `softDelete` sets `status=DELETED` with conditional expression `owner = :expected_owner OR :is_admin = true`.
- All write operations stamp `updated_at = now()`.
- Unit tests use DynamoDbLocal: spin up via Testcontainers, point the SDK at `http://localhost:<port>`.

**Acceptance criteria:**
- Unit tests cover: put-success, put-duplicate (throws), get-found, get-missing, list-empty, list-paginated (3 pages), soft-delete-success, soft-delete-wrong-owner, soft-delete-already-deleted (idempotent).
- Pagination tokens are opaque base64 strings; no internal DDB structure leaks.
- Coverage ≥ 90% on the DAO class.

---

## Story 3.2 — CreateModel handler [S]

**Description:** Handle `POST /models`. Validate, stamp owner, idempotency-check, persist.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/handlers/ModelHandler.java`.

**Implementation notes:**
- Handler method: `handleRequest(APIGatewayProxyRequestEvent event, Context ctx) → APIGatewayProxyResponseEvent`.
- Routing: dispatch on `event.getResource()` + `event.getHttpMethod()` to private methods (`createModel`, `getModel`, ...).
- Owner extraction: `event.getRequestContext().getIdentity().getCaller()` returns the IAM principal ARN; parse to a stable identifier (e.g. `arn:aws:iam::123:user/arjun` → `arjun`).
- Use `ObjectMapper` (Jackson) configured to fail on unknown properties.
- Wrap the body in the idempotency middleware (Story 4.3) — for Models, idempotency is keyed on `model_name` itself plus the body hash, since `CreateModel` is naturally idempotent at the resource level.
- Powertools Logger: `@Logging(logEvent = false)` (don't log full event — body might contain PII).
- Powertools Metrics: emit `ModelsCreated` counter dimensioned by `framework_hint`.

```java
private CreateModelOutput createModel(CreateModelInput input, RequestContext rc) {
    String owner = extractOwner(rc);
    Model m = Model.builder()
        .modelName(input.getModelName())
        .owner(owner)
        .frameworkHint(input.getFrameworkHint())
        .description(input.getDescription())
        .latestMajor(0).latestMinor(0)
        .status("ACTIVE")
        .createdAt(Instant.now().toString())
        .build();
    Model created = modelDao.putIfNotExists(m);
    metrics.addMetric("ModelsCreated", 1, Unit.COUNT);
    return CreateModelOutput.from(created);
}
```

**Acceptance criteria:**
- 201 with the created Model on happy path.
- 409 ModelAlreadyExists on duplicate.
- 400 on missing required fields (validated by API Gateway before reaching Lambda; handler tested with direct invoke for completeness).
- Idempotency replay returns 200 with the original response.
- Unit tests cover all four paths.

---

## Story 3.3 — GetModel and ListModels [S]

**Description:** Read handlers.

**Implementation notes:**
- `GetModel`: returns 404 with `code: "ModelNotFound"` if missing or `status=DELETED` (unless `?includeDeleted=true` and caller has AdminRole).
- `ListModels`: query parameters `limit` (default 50, max 200), `pageToken`, `includeDeleted` (admin-only).
- Project a sparse view on list (omit description/timestamps) to keep responses small; full record only on get.
- Cache-Control headers: `max-age=0, must-revalidate` (data changes; clients shouldn't cache).

**Acceptance criteria:**
- 404 on missing/deleted (without `includeDeleted`).
- Pagination correctly returns 50 / 50 / N where N < 50.
- `?limit=500` clamped to 200.
- Unit tests cover both branches.

---

## Story 3.4 — DeleteModel (soft delete) [S]

**Description:** Mark model `status=DELETED`. Versions retained but unreachable.

**Implementation notes:**
- Authorization: caller must equal `owner` or have AdminRole. Check IAM principal against AdminRole ARN list (configured via Lambda env var).
- Idempotent: deleting an already-DELETED model returns 204.
- Doesn't cascade to versions — they remain in DDB but `ListVersions` and `GetVersion` will treat the parent model as missing.

**Acceptance criteria:**
- 204 on success.
- 404 if model never existed.
- 403 if caller is neither owner nor admin.
- 204 on re-delete (idempotent).

---

## Story 3.5 — Integration tests: Models end-to-end [M]

**Description:** Pytest suite running against an ephemeral CDK stack deployed in CI.

**File:** `tests/integration/test_models.py`.

**Implementation notes:**
- Use `pytest-aws` or write a fixture that deploys a beta-suffixed stack, runs tests, tears it down.
- Sign requests with SigV4 via `requests-aws4auth`.
- Tests sign as a test user IAM role provisioned in the integ stack.
- Concurrency test: use `concurrent.futures.ThreadPoolExecutor` with 10 workers attempting to create the same model name; assert exactly one succeeds.
- 403 test: sign with a no-permission role; assert 403.
- Teardown: even on test failure, the fixture must run `cdk destroy`; use pytest's `finalizer` pattern.

**Acceptance criteria:**
- Full create → get → list → delete → get(404) flow passes.
- Concurrent-create test: 9× 409, 1× 201.
- 403 returned for unauthenticated SigV4.
- Suite runs in <5 min including stack lifecycle.

---

# Epic 4 — Version creation & atomic increment

**Goal:** The high-risk path. Locked at the start of week 3.

## Story 4.1 — VersionDao [M]

**Description:** Versions table access layer including version-key encoding.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/dao/VersionDao.java`.

**Method shapes:**

```java
public class VersionDao {
    public Version put(Version v);
    public Optional<Version> get(String modelName, int major, int minor);
    public Optional<Version> findByIdempotencyKey(String idempotencyKey);
    public PaginatedResult<Version> list(String modelName, int limit, String pageToken,
                                         boolean includePending, boolean includeDeleted);
    public Optional<Version> findLatestReady(String modelName);
    public void updateStatus(String modelName, int major, int minor,
                             VersionStatus newStatus, VersionStatus expectedStatus);
    public List<Version> findOrphans(int batchSize, Instant olderThan);
}
```

**Encoding:**

```java
public final class VersionKey {
    private static final String FORMAT = "%04d.%04d";

    public static String encode(int major, int minor) {
        if (major < 0 || minor < 0) throw new IllegalArgumentException(...);
        if (major > 9999 || minor > 9999) throw new IllegalArgumentException(...);
        return String.format(FORMAT, major, minor);
    }

    public static int[] decode(String key) {
        // matches "^\\d{4}\\.\\d{4}$"
        // returns [major, minor]
    }
}
```

**Implementation notes:**
- `findByIdempotencyKey` queries `idempotency-gsi`.
- `findLatestReady` queries main table descending (`ScanIndexForward=false`) with `FilterExpression` `status = :ready`, limit 10 (defensive — handles a few PENDING rows at the top); page if needed.
- `findOrphans` queries `status-created-gsi` with `status = "PENDING" AND created_at < :cutoff`.
- Round-trip property test: generate 10,000 random `(major, minor)` pairs, encode/decode, assert equality.

**Acceptance criteria:**
- Encoder rejects negative / overflow / non-integer inputs.
- Round-trip test passes.
- DAO unit tests with DynamoDbLocal cover all methods.
- Coverage ≥ 90%.

---

## Story 4.2 — Version increment service [L]

**Description:** Pure logic, isolated from I/O. Given current `(major, minor)` and an optional requested major, produce the new `(major, minor)` plus the conditional update parameters.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/version/VersionIncrementer.java`.

**Method shape:**

```java
public class VersionIncrementer {
    public IncrementResult next(int currentMajor, int currentMinor, Optional<Integer> requestedMajor) {
        if (requestedMajor.isEmpty() || requestedMajor.get() == currentMajor) {
            return IncrementResult.minorBump(currentMajor, currentMinor + 1, currentMajor);
        }
        if (requestedMajor.get() < currentMajor) {
            throw new InvalidMajorVersionException(requestedMajor.get(), currentMajor);
        }
        return IncrementResult.majorBump(requestedMajor.get(), 0, currentMajor);
    }
}

public record IncrementResult(int newMajor, int newMinor, int expectedCurrentMajor, BumpType type) {
    public static IncrementResult minorBump(int major, int minor, int expectedMajor) { ... }
    public static IncrementResult majorBump(int major, int minor, int expectedMajor) { ... }
}
```

**Implementation notes:**
- Handler later translates `IncrementResult` into a DDB `UpdateItem`:
  - Minor bump: `ADD latest_minor :inc` with condition `latest_major = :expected_major`.
  - Major bump: `SET latest_major = :new_major, latest_minor = :zero` with condition `latest_major < :new_major`.
- First-version case (model exists, `latest_major=0` and `latest_minor=0`): treated as a minor bump → produces `(0, 1)`. Decision point: do we want first version to be `1.0` or `0.1`? **Pick `1.0`.** Implementation: `ModelDao.create` sets initial `latest_major=1`, `latest_minor=-1` (so the first minor bump produces `(1, 0)`). Document this in code comments.
- All branches return immediately; no DDB calls in this class.

> **Handoff note (not yet implemented):** The spec says `ModelDao.create` sets initial `latest_major=1, latest_minor=-1` so the first minor bump produces `(1, 0)`. Do NOT initialize to `(0, 0)` — that would make the first version `(0, 1)` which is wrong. The `CLAUDE.md` "Subtleties" section has detail on this. This is the trickiest invariant in the whole codebase.

**Acceptance criteria:**
- Minor bump: `next(3, 4, empty) = (3, 5)`.
- Equal-major: `next(3, 4, of(3)) = (3, 5)`.
- Major bump: `next(3, 4, of(4)) = (4, 0)`.
- Skip-major: `next(3, 4, of(7)) = (7, 0)`.
- Less-major rejection: `next(5, 2, of(4))` throws `InvalidMajorVersionException`.
- First version: `next(1, -1, empty) = (1, 0)`.
- Coverage 100%.

---

## Story 4.3 — Idempotency middleware [M]

**Description:** Wrap mutating handlers; dedupe on `idempotency_key` for 24h.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/idempotency/IdempotencyMiddleware.java`.

**Implementation notes:**

```java
public class IdempotencyMiddleware {
    public <I, O> O execute(String idempotencyKey, I input, Function<I, O> work) {
        String bodyHash = canonicalSha256(input);
        Optional<IdempotencyRecord> existing = repo.find(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().bodyHash().equals(bodyHash)) {
                throw new IdempotencyMismatchException();
            }
            return deserialize(existing.get().response(), responseType);
        }
        O result = work.apply(input);
        repo.save(idempotencyKey, bodyHash, serialize(result), Instant.now().plus(24, HOURS));
        return result;
    }
}
```

- Canonical body hash: `ObjectMapper` configured with `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` + `WRITE_BIGDECIMAL_AS_PLAIN`. Serialize input → bytes → SHA-256 hex.
- Storage: for `CreateVersion`, the idempotency record IS the Versions row (with `idempotency_key` indexed via GSI). For `CreateModel` and `ConfirmVersion`, store a separate `IdempotencyRecord` in a third DDB table or as a sentinel attribute on the affected row. **Decision: use a small dedicated `IdempotencyRecords` table for `CreateModel` and `ConfirmVersion`** since those rows are smaller and don't naturally hold response data; for `CreateVersion`, store on the Versions row directly.
- Race: two concurrent first-time requests with the same key. Solution: `repo.save` uses conditional `attribute_not_exists`; on collision, re-read and treat as replay.

**Acceptance criteria:**
- Replay-match: 5 sequential calls with same key + body return identical responses.
- Replay-mismatch: same key, different body → 409 IdempotencyMismatch.
- Expired-replay: key from >24h ago treated as new (creates new resource).
- Concurrent first-time: 10 parallel requests with same key produce one resource.
- Unit tests cover all four cases.

---

## Story 4.4 — CreateVersion handler [L]

**Description:** Glue idempotency + increment + DAO + S3 presigner.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/handlers/VersionHandler.java` (createVersion method).

**Flow:**

```java
private CreateVersionOutput createVersion(CreateVersionInput input, RequestContext rc) {
    return idempotency.execute(input.getIdempotencyKey(), input, in -> {
        Model model = modelDao.get(in.getModelName())
            .orElseThrow(() -> new ModelNotFoundException(in.getModelName()));

        IncrementResult incr = incrementer.next(
            model.getLatestMajor(), model.getLatestMinor(),
            Optional.ofNullable(in.getMajor()));

        try {
            modelDao.updateLatestVersion(
                in.getModelName(),
                incr.newMajor(), incr.newMinor(),
                incr.expectedCurrentMajor());
        } catch (ConditionalCheckFailedException e) {
            throw new VersionConflictException(model.getLatestMajor(), model.getLatestMinor());
        }

        String s3Key = String.format("%s/v%d.%d/weights.bin",
            in.getModelName(), incr.newMajor(), incr.newMinor());

        Version v = Version.builder()
            .modelName(in.getModelName())
            .major(incr.newMajor()).minor(incr.newMinor())
            .versionKey(VersionKey.encode(incr.newMajor(), incr.newMinor()))
            .s3Key(s3Key)
            .status(VersionStatus.PENDING)
            .depSnapshot(in.getDepSnapshot())
            .trainingMetadata(in.getTrainingMetadata())
            .idempotencyKey(in.getIdempotencyKey())
            .ttl(Instant.now().plus(24, HOURS).getEpochSecond())
            .createdAt(Instant.now().toString())
            .createdBy(extractOwner(rc))
            .build();
        versionDao.put(v);

        Instant expiresAt = Instant.now().plus(1, HOURS);
        URL uploadUrl = s3Presigner.presignPutObject(b -> b
            .signatureDuration(Duration.ofHours(1))
            .putObjectRequest(req -> req.bucket(bucket).key(s3Key)
                .contentType("application/octet-stream"))
        ).url();

        metrics.addMetric("VersionsCreated", 1, Unit.COUNT,
            "framework", in.getDepSnapshot().getFramework().getName());

        return CreateVersionOutput.builder()
            .version(String.format("%d.%d", incr.newMajor(), incr.newMinor()))
            .status(VersionStatus.PENDING)
            .uploadUrl(uploadUrl.toString())
            .uploadUrlExpiresAt(expiresAt)
            .build();
    });
}
```

**Implementation notes:**
- `S3Presigner` is a singleton field on the handler (created in static init for SnapStart compatibility).
- Translate `ConditionalCheckFailedException` from the DAO to `VersionConflictException` (HTTP 409).
- `InvalidMajorVersionException` from the incrementer surfaces as HTTP 400.
- Powertools metric `VersionsCreated` dimensioned by framework name.
- X-Ray subsegment around the presigner call (cheap but useful).

**Acceptance criteria:**
- Happy path (minor): v1.0 model → create with no major → returns v1.1.
- Happy path (major): v3.5 model → create with `major=4` → returns v4.0.
- Skip-major: v3.5 → `major=7` → returns v7.0.
- Conflict: simulate concurrent counter mutation → 409 with `current_major`/`current_minor` in details.
- Invalid major: v5.2 → `major=4` → 400 InvalidMajorVersion.
- Idempotency replay: identical body returns identical response (same version, same upload URL re-signed? See note below).
- Unknown model name → 404 ModelNotFound.

> **Open question for replay:** the upload URL has a 1-hour expiry. If a client replays after 30 min, do we return the original (now half-expired) URL or re-sign? **Decision: store the response without the URL, re-sign on every replay** so the client always gets a fresh URL. Log this in the design comments.

> **Handoff note (not yet implemented):** The idempotency store for `CreateVersion` is the Versions row itself (idempotency_key GSI), not a separate table. The stored idempotency record must NOT include the upload URL — only the version and status. On replay, look up the Version row and re-sign a fresh presigned PUT URL. This is a hard correctness requirement (expired URLs break replays).

---

## Story 4.5 — ConfirmVersion handler [M]

**Description:** Verify the upload landed in S3, flip status PENDING → READY, write the metadata mirror.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/handlers/VersionHandler.java` (confirmVersion method).

**Flow:**
1. Parse `version` path param → `(major, minor)`.
2. Fetch Version row. If missing → 404. If `status != PENDING` and not idempotent-replay → 412 PreconditionFailed.
3. HEAD the S3 object. If missing → 404 with `code: "UploadNotFound"`.
4. Compare `Content-Length` with `input.size_bytes` (from request body). Mismatch → 409 ChecksumMismatch.
5. Compare ETag (or computed SHA via S3 checksum) with `input.checksum_sha256`. Mismatch → 409.
6. `versionDao.updateStatus(modelName, major, minor, READY, PENDING)`.
7. Write `metadata.json` to S3 mirror (best-effort; failure logs warning but doesn't fail the request).
8. Emit `VersionsConfirmed` metric.

**Implementation notes:**
- S3 supports SHA-256 checksums natively (`x-amz-checksum-sha256`); enable on PUT in client SDK by setting `ChecksumAlgorithm.SHA256`. Compare from HEAD response.
- Idempotent confirm: confirming an already-READY version with matching checksum returns 200; mismatch returns 409.
- Use `ConditionExpression` on the status update to defend against races (`status = :pending`).

**Acceptance criteria:**
- 200 on happy path with updated Version.
- 412 if status is READY/DELETED/FAILED and checksum mismatches.
- 200 (idempotent) if already READY and checksum matches.
- 404 if S3 object absent.
- 409 ChecksumMismatch on bad checksum.
- Mirror file written to `<model>/v<M>.<N>/metadata.json`.

---

## Story 4.6 — Integration tests: version creation end-to-end [L]

**Description:** Pytest suite hitting beta stack with full create → upload → confirm flow.

**File:** `tests/integration/test_versions.py`.

**Test cases:**
- **Happy path**: create model, create version, PUT 10 MB blob to upload URL, confirm with checksum, get returns READY.
- **Major bump**: create v1.0, then create with `major=2`, list returns `[v2.0, v1.0]` newest-first.
- **Skip-major**: create v1.0, create with `major=5`, list returns `[v5.0, v1.0]`.
- **Race**: 10 threads call CreateVersion concurrently on same model; assert 10 distinct sequential versions, no gaps, no duplicates.
- **Confirm without upload**: create version, skip the PUT, call confirm → 404.
- **Confirm with bad checksum**: create version, PUT blob, call confirm with wrong sha → 409.
- **Idempotency**: call CreateVersion twice with same key → identical version returned, only one row in DDB.

**Implementation notes:**
- Use `boto3` directly for the S3 PUT (signed URL is just an HTTP URL — `requests.put(url, data=blob)` works).
- For the race test, use `ThreadPoolExecutor(max_workers=10)`; collect all returned versions; assert `set` has 10 elements and they are sequential (1, 2, 3, ..., 10).
- Generate test blobs with `os.urandom(10 * 1024 * 1024)`.

**Acceptance criteria:**
- All seven test cases pass against beta.
- Race test deterministic across 5 runs.
- Total suite < 10 min.

---

## Story 4.7 — Presigned PUT requests SHA-256 checksum [S]

**Description:** Wire `ChecksumAlgorithm=SHA256` into the presigned PUT URL produced by `CreateVersion` so S3 captures and stores the SHA-256 of the uploaded object. This unblocks the strict checksum verification path in `ConfirmVersion` (Story 4.5), which currently 409s whenever a client supplies `checksumSha256` because `HeadObject` returns null for `checksumSHA256`.

**Background:** Found by smoke-testing Epic 4 against alpha on 2026-05-13. `VersionHandler.presignPutUrl` builds the `PutObjectPresignRequest` without specifying a checksum algorithm, so the client's PUT does not carry `x-amz-checksum-sha256`, and S3 has no SHA-256 to surface on subsequent HeadObject calls. With Story 4.5's strict verification (added at reviewer's request — fail if client provides checksum but S3 returns null), the end-to-end happy path with checksum is broken; clients today must omit `checksumSha256` from the confirm body.

**Files:**
- `lambda-handlers/.../handlers/VersionHandler.java` — `presignPutUrl(...)`

**Implementation notes:**
- Add `.checksumAlgorithm(ChecksumAlgorithm.SHA256)` on the `PutObjectRequest` builder inside `presignPutObject`.
- The presigned URL must also expose `x-amz-sdk-checksum-algorithm` as a signed header so the client knows to compute and send the checksum on PUT. Verify whether the SDK does this automatically when `checksumAlgorithm` is set.
- The Python SDK (out of repo) is the eventual primary client — but `requests.put` with a raw `x-amz-checksum-sha256` header is the integration-test fallback. Document the header(s) the client must send.
- No change to `ConfirmVersion` itself — it already reads `head.checksumSHA256()` correctly; we just need S3 to actually populate it.

**Acceptance criteria:**
- A version uploaded via the presigned URL with the SHA-256 header set returns a non-null `checksumSHA256` on subsequent `HeadObject`.
- `ConfirmVersion` with a matching `checksumSha256` in the body returns 200 (not 409).
- `ConfirmVersion` with a mismatched `checksumSha256` still returns 409 (regression guard on Story 4.5's strict path).
- Add an integration test in `tests/integration/test_versions.py` that uploads with the SHA-256 header set and confirms with a matching checksum — closes a hole in the existing happy-path test (which today only sends size, not checksum, exactly because of this bug).

---

# Epic 5 — Version read paths & deletion

## Story 5.1 — GetVersion and GetLatestVersion [S]

**Implementation notes:**
- Both endpoints return the Version metadata + a presigned GET URL (TTL = 1h).
- `GetLatestVersion` calls `versionDao.findLatestReady(modelName)`.
- 404 if no READY version exists.
- Download URL scoped to `s3:GetObject` only.

**AC:**
- 404 cases covered.
- TTL on download URL = 1h.
- Unit tests for both.

---

## Story 5.2 — ListVersions [S]

**Implementation notes:**
- Default page size 50, max 200.
- `?includePending=true` includes PENDING; default excludes.
- `?includeDeleted=true` requires AdminRole; otherwise rejected with 403.
- Response sorted newest-first via `ScanIndexForward=false`.

**AC:**
- Pagination correct.
- Filtering by status correct.
- Authorization on `includeDeleted` enforced.

---

## Story 5.3 — DeleteVersion (soft delete) [S]

**Implementation notes:**
- Sets status=DELETED. S3 object retained.
- `findLatestReady` skips DELETED rows.
- Authorization: model owner or AdminRole.

**AC:**
- 204 on success.
- Latest skips deleted versions.
- 403 for non-owner non-admin.

---

# Epic 6 — Operations: orphan sweep & admin tools

## Story 6.1 — SweeperHandler.sweepOrphans [M]

**Description:** Hourly Lambda that reconciles PENDING rows older than 24h.

**File:** `lambda-handlers/src/main/java/com/anthropic/artifactmgmt/handlers/SweeperHandler.java`.

**Logic:**

```java
public void handleRequest(ScheduledEvent event, Context ctx) {
    Instant cutoff = Instant.now().minus(24, HOURS);
    boolean dryRun = Boolean.parseBoolean(System.getenv().getOrDefault("DRY_RUN", "false"));
    int processed = 0;

    while (processed < 1000) {
        List<Version> orphans = versionDao.findOrphans(100, cutoff);
        if (orphans.isEmpty()) break;
        for (Version v : orphans) {
            try {
                HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket).key(v.getS3Key()));
                if (dryRun) { logger.info("[DRY] would set READY: {}", v); continue; }
                if (matchesChecksum(head, v)) {
                    versionDao.updateStatus(v.getModelName(), v.getMajor(), v.getMinor(),
                                            VersionStatus.READY, VersionStatus.PENDING);
                } else {
                    versionDao.updateStatus(..., VersionStatus.FAILED, VersionStatus.PENDING);
                }
            } catch (NoSuchKeyException e) {
                if (!dryRun) {
                    versionDao.updateStatus(..., VersionStatus.FAILED, VersionStatus.PENDING);
                }
            }
            metrics.addMetric("UploadOrphansSwept", 1, Unit.COUNT);
            processed++;
        }
    }
}
```

**Implementation notes:**
- Page size 100, max 1000 per invocation (DDB query cost guardrail).
- Dry-run mode controlled by env var; ops can deploy a debug Lambda alias with `DRY_RUN=true`.
- Audit log: every status change emits a structured log line with `operation: "sweep"`, `model_name`, `version`, `from_status`, `to_status`, `reason`.

**AC:**
- Processes up to 1000 orphans/run.
- Dry-run leaves DDB unchanged but logs intended actions.
- Emits `UploadOrphansSwept` correctly.
- Unit tests with DDB-Local + S3-mock (use `s3mock` Testcontainer).

---

## Story 6.2 — Manual recovery CLI [S]

**Description:** Bash + awscli scripts under `scripts/ops/` for operator use.

**Files:** `scripts/ops/show.sh`, `force-ready.sh`, `purge.sh`.

**Implementation notes:**
- Each script takes `--stage` argument; resolves table/bucket names accordingly.
- `force-ready` requires `--checksum` and prints a confirmation prompt.
- `purge` only operates on rows that have been DELETED for >30 days; shorter ages refuse with an error.
- All actions logged to `ops-audit-{stage}` log group via `aws logs put-log-events`.

**AC:**
- Each script has `--help`.
- All actions logged to ops-audit log group.
- Confirmation prompts on destructive actions.

---

# Epic 7 — Observability & runbooks

## Story 7.1 — Structured logging via Powertools [S]

**Implementation notes:**
- `aws-lambda-powertools-java` dependency in `lambda-handlers/build.gradle.kts`.
- Add `@Logging(logEvent = false, correlationIdPath = "requestContext.requestId")` to handler entry points.
- Custom append: every log line gets `model_name`, `version`, `operation`, `outcome` keys via `Logger.appendKey`.
- Redact `uploadUrl` and `idempotencyKey` from any logged event.

**AC:**
- All logs JSON-formatted with required keys.
- Log level driven by `LOG_LEVEL` env var.
- No URLs or idempotency keys in logs.

---

## Story 7.2 — CloudWatch custom metrics [S]

**Implementation notes:**
- Use Powertools Metrics module → emits via Embedded Metric Format (EMF) — zero PutMetricData cost.
- `@Metrics(namespace = "ArtifactMgmt", service = "artifact-mgmt")` on handler classes.
- Metrics list: `VersionsCreated`, `VersionsConfirmed`, `UploadOrphansSwept`, `VersionConflict`, `IdempotencyReplay`, `IdempotencyExpiredReplay`.
- Latency captured automatically by Lambda; expose as derived metric in dashboard.

**AC:**
- All listed metrics emitted.
- Dimensions correct (framework on `VersionsCreated`, operation on `VersionConflict`).
- EMF format verified via CloudWatch Logs Insights query.

---

## Story 7.3 — X-Ray tracing [S]

**Implementation notes:**
- `aws-xray-recorder-sdk-aws-sdk-v2` dependency.
- Wrap `DynamoDbClient`, `S3Client`, `S3Presigner` with `XRayInterceptor`.
- Custom subsegments around the version-incrementer call (annotation: `model_name`, `bump_type`).

**AC:**
- Service map shows API Gateway → Lambda → DDB / S3.
- Subsegments visible per DDB call, per S3 call.
- Trace exemplars link from CloudWatch alarms to slow traces.

---

## Story 7.4 — Dashboard and alarms [M]

**File:** `infra-cdk/lib/observability-stack.ts`.

**Dashboard widgets:**
- Per-operation latency p50/p95/p99 (1-min period).
- Error rate (4xx, 5xx) per operation.
- Lambda throttles, DLQ depth.
- DDB consumed capacity, throttles.
- VersionsCreated vs VersionsConfirmed gap (trailing 1h).
- UploadOrphansSwept (hourly).

**Alarms (CDK construct):**

```typescript
new cloudwatch.Alarm(this, 'High5xxRate', {
  metric: api.metricServerError({ period: cdk.Duration.minutes(5) }),
  threshold: 0.01, evaluationPeriods: 1, datapointsToAlarm: 1,
  comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_THRESHOLD,
  treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
});
```

- Alarms wired to SNS topic; topic subscribes the on-call email (and PagerDuty if configured).
- All alarms have `OK` actions to clear pages.

**AC:**
- Dashboard renders all widgets.
- All four alarms (5xx rate, P95 latency, OrphanRate, VersionConflictRate) wired.
- Alarms have OK actions.

---

## Story 7.5 — Runbook documentation [S]

**Files:** `docs/runbooks/orphan-cleanup.md`, `major-bump-race.md`, `manual-deletion.md`.

**Each runbook contains:**
- Symptom (what alarm fires, what users see).
- Diagnosis (queries to run, log searches, what to check).
- Actions (step-by-step fix).
- Verification (how to confirm fixed).
- Escalation path (who to page if blocked).

**AC:**
- Three files created.
- Each has all five sections.
- Linked from CloudWatch alarm descriptions (alarm description includes runbook URL).

---

# Sequencing

| Phase | Weeks | Epics | Critical path |
|---|---|---|---|
| 1 | 1 | 1 | Smithy contract — blocks everything else |
| 2 | 2–3 | 2 + 3 | Infra in parallel with Models CRUD |
| 3 | 3–4 | 4 | Highest-risk; full focus |
| 4 | 5 | 5 + 6 | Read paths and ops in parallel |
| 5 | 6 | 7 | Observability hardening |

---

# What's deferred

- **Model Interface Library** (Python SDK scientists import). Captures dep_snapshot, dispatches per framework, handles idempotency UUIDs invisibly. Separate doc.
- **Version Recommender Service**. Standalone microservice. Consumes `dep_snapshot`. Separate doc.
- **Cross-account sharing / quotas**. Multi-tenancy concerns out of scope for v1.
- **Web console**. v1 is API-only.
- **Synthetic canaries** (would be Story 7.6). Recommended addition before prod launch.
