import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface DataStackProps extends cdk.StackProps {
  config: StageConfig;
}

export class DataStack extends cdk.Stack {
  readonly modelsTableName: string;
  readonly modelsTableArn: string;
  readonly versionsTableName: string;
  readonly versionsTableArn: string;
  readonly idempotencyTableName: string;
  readonly idempotencyTableArn: string;
  readonly artifactsBucketName: string;
  readonly artifactsBucketArn: string;

  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);

    const cfg = props.config;
    const isProvisioned = cfg.ddbBilling === dynamodb.BillingMode.PROVISIONED;

    // ── Models table ─────────────────────────────────────────────────────────

    const modelsTable = new dynamodb.Table(this, 'ModelsTable', {
      tableName: `artifact-mgmt-models-${cfg.stage}`,
      partitionKey: { name: 'model_name', type: dynamodb.AttributeType.STRING },
      billingMode: cfg.ddbBilling,
      readCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.read : undefined,
      writeCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.write : undefined,
      pointInTimeRecovery: true,
      removalPolicy: cfg.removalPolicy,
      encryption: dynamodb.TableEncryption.AWS_MANAGED,
    });

    if (isProvisioned) {
      modelsTable.autoScaleReadCapacity({ minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
      modelsTable.autoScaleWriteCapacity({ minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
    }

    // ── Versions table ───────────────────────────────────────────────────────

    const versionsTable = new dynamodb.Table(this, 'VersionsTable', {
      tableName: `artifact-mgmt-versions-${cfg.stage}`,
      partitionKey: { name: 'model_name', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'version_key', type: dynamodb.AttributeType.STRING },
      billingMode: cfg.ddbBilling,
      readCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.read : undefined,
      writeCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.write : undefined,
      pointInTimeRecovery: true,
      timeToLiveAttribute: 'ttl',
      removalPolicy: cfg.removalPolicy,
      encryption: dynamodb.TableEncryption.AWS_MANAGED,
    });

    versionsTable.addGlobalSecondaryIndex({
      indexName: 'idempotency-gsi',
      partitionKey: { name: 'idempotency_key', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.ALL,
      readCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.read : undefined,
      writeCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.write : undefined,
    });

    versionsTable.addGlobalSecondaryIndex({
      indexName: 'status-created-gsi',
      partitionKey: { name: 'status', type: dynamodb.AttributeType.STRING },
      sortKey: { name: 'created_at', type: dynamodb.AttributeType.STRING },
      projectionType: dynamodb.ProjectionType.INCLUDE,
      nonKeyAttributes: ['s3_key', 'idempotency_key'],
      readCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.read : undefined,
      writeCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.write : undefined,
    });

    if (isProvisioned) {
      versionsTable.autoScaleReadCapacity({ minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
      versionsTable.autoScaleWriteCapacity({ minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });

      versionsTable.autoScaleGlobalSecondaryIndexReadCapacity('idempotency-gsi', { minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
      versionsTable.autoScaleGlobalSecondaryIndexWriteCapacity('idempotency-gsi', { minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });

      versionsTable.autoScaleGlobalSecondaryIndexReadCapacity('status-created-gsi', { minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
      versionsTable.autoScaleGlobalSecondaryIndexWriteCapacity('status-created-gsi', { minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
    }

    // ── Idempotency records table ────────────────────────────────────────────

    const idempotencyTable = new dynamodb.Table(this, 'IdempotencyTable', {
      tableName: `artifact-mgmt-idempotency-${cfg.stage}`,
      partitionKey: { name: 'idempotency_key', type: dynamodb.AttributeType.STRING },
      billingMode: cfg.ddbBilling,
      readCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.read : undefined,
      writeCapacity: isProvisioned ? cfg.ddbProvisionedCapacity?.write : undefined,
      timeToLiveAttribute: 'ttl',
      removalPolicy: cfg.removalPolicy,
      encryption: dynamodb.TableEncryption.AWS_MANAGED,
    });

    if (isProvisioned) {
      idempotencyTable.autoScaleReadCapacity({ minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
      idempotencyTable.autoScaleWriteCapacity({ minCapacity: 5, maxCapacity: 500 })
        .scaleOnUtilization({ targetUtilizationPercent: 70 });
    }

    // ── Stack outputs (consumed by ComputeStack) ──────────────────────────────

    this.modelsTableName = modelsTable.tableName;
    this.modelsTableArn = modelsTable.tableArn;
    this.versionsTableName = versionsTable.tableName;
    this.versionsTableArn = versionsTable.tableArn;
    this.idempotencyTableName = idempotencyTable.tableName;
    this.idempotencyTableArn = idempotencyTable.tableArn;

    new cdk.CfnOutput(this, 'ModelsTableName', { value: modelsTable.tableName });
    new cdk.CfnOutput(this, 'ModelsTableArn', { value: modelsTable.tableArn });
    new cdk.CfnOutput(this, 'VersionsTableName', { value: versionsTable.tableName });
    new cdk.CfnOutput(this, 'VersionsTableArn', { value: versionsTable.tableArn });
    new cdk.CfnOutput(this, 'IdempotencyTableName', { value: idempotencyTable.tableName });
    new cdk.CfnOutput(this, 'IdempotencyTableArn', { value: idempotencyTable.tableArn });

    // ── S3 artifact bucket ───────────────────────────────────────────────────

    const loggingBucket = new s3.Bucket(this, 'ArtifactsLoggingBucket', {
      bucketName: `artifact-mgmt-logs-${cfg.stage}-${cfg.account}`,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      removalPolicy: cfg.removalPolicy,
    });

    const artifactsBucket = new s3.Bucket(this, 'ArtifactsBucket', {
      bucketName: `artifact-mgmt-${cfg.stage}-${cfg.account}`,
      versioned: true,
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      removalPolicy: cfg.removalPolicy,
      serverAccessLogsBucket: loggingBucket,
      serverAccessLogsPrefix: `artifact-mgmt-${cfg.stage}/`,
      lifecycleRules: [
        {
          id: 'abort-incomplete-multipart',
          abortIncompleteMultipartUploadAfter: cdk.Duration.days(7),
        },
        {
          id: 'expire-noncurrent-versions',
          noncurrentVersionExpiration: cdk.Duration.days(90),
        },
      ],
    });

    // enforceSSL: true on the Bucket L2 construct adds the deny-non-TLS policy
    // automatically, but we add an explicit deny to cover both the bucket and
    // all objects, mirroring the spec snippet exactly.
    artifactsBucket.addToResourcePolicy(
      new iam.PolicyStatement({
        sid: 'DenyNonTLS',
        effect: iam.Effect.DENY,
        principals: [new iam.AnyPrincipal()],
        actions: ['s3:*'],
        resources: [artifactsBucket.bucketArn, `${artifactsBucket.bucketArn}/*`],
        conditions: { Bool: { 'aws:SecureTransport': 'false' } },
      }),
    );

    this.artifactsBucketName = artifactsBucket.bucketName;
    this.artifactsBucketArn = artifactsBucket.bucketArn;

    new cdk.CfnOutput(this, 'ArtifactsBucketName', { value: artifactsBucket.bucketName });
    new cdk.CfnOutput(this, 'ArtifactsBucketArn', { value: artifactsBucket.bucketArn });
  }
}
