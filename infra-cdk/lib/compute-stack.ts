import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface ComputeStackProps extends cdk.StackProps {
  config: StageConfig;
  modelsTableName?: string;
  modelsTableArn?: string;
  versionsTableName?: string;
  versionsTableArn?: string;
  artifactsBucketName?: string;
  artifactsBucketArn?: string;
  /** Override the Lambda code asset. Tests inject a stable stub so snapshot
   *  hashes don't vary across build environments. Defaults to the Gradle zip. */
  lambdaCode?: lambda.Code;
}

export class ComputeStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);

    const cfg = props.config;

    const modelsTable = props.modelsTableArn
      ? dynamodb.Table.fromTableArn(this, 'ImportedModelsTable', props.modelsTableArn)
      : undefined;
    const versionsTable = props.versionsTableArn
      ? dynamodb.Table.fromTableArn(this, 'ImportedVersionsTable', props.versionsTableArn)
      : undefined;
    const artifactsBucket = props.artifactsBucketArn
      ? s3.Bucket.fromBucketArn(this, 'ImportedArtifactsBucket', props.artifactsBucketArn)
      : undefined;

    const commonEnv: Record<string, string> = {
      MODELS_TABLE: props.modelsTableName ?? '',
      VERSIONS_TABLE: props.versionsTableName ?? '',
      ARTIFACTS_BUCKET: props.artifactsBucketName ?? '',
      POWERTOOLS_SERVICE_NAME: 'artifact-mgmt',
      POWERTOOLS_METRICS_NAMESPACE: 'ArtifactMgmt',
      LOG_LEVEL: cfg.stage === 'prod' ? 'INFO' : 'DEBUG',
    };

    const codeAsset =
      props.lambdaCode ??
      lambda.Code.fromAsset('../lambda-handlers/build/distributions/handlers.zip');
    const snapStartConf = cfg.lambdaSnapStart
      ? lambda.SnapStartConf.ON_PUBLISHED_VERSIONS
      : undefined;

    const makeDlq = (id: string) =>
      new sqs.Queue(this, `${id}Dlq`, {
        queueName: `artifact-mgmt-${id.toLowerCase()}-dlq-${cfg.stage}`,
        retentionPeriod: cdk.Duration.days(14),
        encryption: sqs.QueueEncryption.SQS_MANAGED,
      });

    // Creates a versioned alias with provisioned concurrency for prod.
    const maybeProvision = (fn: lambda.Function, id: string) => {
      if (cfg.lambdaProvisionedConcurrency) {
        new lambda.Alias(this, `${id}LiveAlias`, {
          aliasName: 'live',
          version: fn.currentVersion,
          provisionedConcurrentExecutions: cfg.lambdaProvisionedConcurrency,
        });
      }
    };

    // ── ModelHandler — Models CRUD; Models table only ─────────────────────────

    const modelDlq = makeDlq('ModelHandler');
    const modelHandler = new lambda.Function(this, 'ModelHandler', {
      functionName: `artifact-mgmt-model-${cfg.stage}`,
      runtime: lambda.Runtime.JAVA_21,
      architecture: lambda.Architecture.ARM_64,
      handler: 'com.anthropic.artifactmgmt.handlers.ModelHandler::handleRequest',
      code: codeAsset,
      memorySize: cfg.lambdaMemoryMB ?? 512,
      timeout: cdk.Duration.seconds(30),
      tracing: cfg.enableXRay ? lambda.Tracing.ACTIVE : lambda.Tracing.DISABLED,
      snapStart: snapStartConf,
      deadLetterQueue: modelDlq,
      environment: commonEnv,
    });
    modelsTable?.grantReadWriteData(modelHandler);
    maybeProvision(modelHandler, 'ModelHandler');

    // ── VersionHandler — Version CRUD + presign; both tables + S3 PUT/GET ─────

    const versionDlq = makeDlq('VersionHandler');
    const versionHandler = new lambda.Function(this, 'VersionHandler', {
      functionName: `artifact-mgmt-version-${cfg.stage}`,
      runtime: lambda.Runtime.JAVA_21,
      architecture: lambda.Architecture.ARM_64,
      handler: 'com.anthropic.artifactmgmt.handlers.VersionHandler::handleRequest',
      code: codeAsset,
      memorySize: cfg.lambdaMemoryMB ?? 512,
      timeout: cdk.Duration.seconds(30),
      tracing: cfg.enableXRay ? lambda.Tracing.ACTIVE : lambda.Tracing.DISABLED,
      snapStart: snapStartConf,
      deadLetterQueue: versionDlq,
      environment: commonEnv,
    });
    modelsTable?.grantReadData(versionHandler);
    versionsTable?.grantReadWriteData(versionHandler);
    artifactsBucket?.grantPut(versionHandler);
    artifactsBucket?.grantRead(versionHandler);
    maybeProvision(versionHandler, 'VersionHandler');

    // ── AdminHandler — soft delete + orphan sweep; both tables + S3 DELETE ────

    const adminDlq = makeDlq('AdminHandler');
    const adminHandler = new lambda.Function(this, 'AdminHandler', {
      functionName: `artifact-mgmt-admin-${cfg.stage}`,
      runtime: lambda.Runtime.JAVA_21,
      architecture: lambda.Architecture.ARM_64,
      handler: 'com.anthropic.artifactmgmt.handlers.AdminHandler::handleRequest',
      code: codeAsset,
      memorySize: cfg.lambdaMemoryMB ?? 512,
      timeout: cdk.Duration.seconds(30),
      tracing: cfg.enableXRay ? lambda.Tracing.ACTIVE : lambda.Tracing.DISABLED,
      snapStart: snapStartConf,
      deadLetterQueue: adminDlq,
      environment: commonEnv,
    });
    modelsTable?.grantReadWriteData(adminHandler);
    versionsTable?.grantReadWriteData(adminHandler);
    artifactsBucket?.grantRead(adminHandler);
    artifactsBucket?.grantDelete(adminHandler);
    maybeProvision(adminHandler, 'AdminHandler');

    // ── SweeperHandler — hourly orphan reconciler; read-only on Models ────────
    // reservedConcurrentExecutions=1: two sweeps must never overlap.

    const sweeperDlq = makeDlq('SweeperHandler');
    const sweeperHandler = new lambda.Function(this, 'SweeperHandler', {
      functionName: `artifact-mgmt-sweeper-${cfg.stage}`,
      runtime: lambda.Runtime.JAVA_21,
      architecture: lambda.Architecture.ARM_64,
      handler: 'com.anthropic.artifactmgmt.handlers.SweeperHandler::handleRequest',
      code: codeAsset,
      memorySize: cfg.lambdaMemoryMB ?? 512,
      timeout: cdk.Duration.seconds(30),
      tracing: cfg.enableXRay ? lambda.Tracing.ACTIVE : lambda.Tracing.DISABLED,
      snapStart: snapStartConf,
      deadLetterQueue: sweeperDlq,
      reservedConcurrentExecutions: 1,
      environment: {
        ...commonEnv,
        DRY_RUN: 'false',
      },
    });
    modelsTable?.grantReadData(sweeperHandler);
    versionsTable?.grantReadWriteData(sweeperHandler);
    artifactsBucket?.grantRead(sweeperHandler);
    // SweeperHandler does not get provisioned concurrency — reservedConcurrentExecutions=1 is enough.
  }
}
