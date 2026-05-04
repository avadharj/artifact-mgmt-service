import * as cdk from 'aws-cdk-lib';
import * as dynamodb from 'aws-cdk-lib/aws-dynamodb';

export interface StageConfig {
  stage: 'alpha' | 'beta' | 'gamma' | 'prod';
  account: string;
  region: string;
  removalPolicy: cdk.RemovalPolicy;
  ddbBilling: dynamodb.BillingMode;
  ddbProvisionedCapacity?: { read: number; write: number };
  lambdaSnapStart: boolean;
  lambdaMemoryMB?: number;
  lambdaProvisionedConcurrency?: number;
  alarmEmail: string;
  enableXRay: boolean;
}

export const STAGES: Record<string, StageConfig> = {
  alpha: {
    stage: 'alpha',
    account: process.env.CDK_DEFAULT_ACCOUNT ?? '123456789012',
    region: 'us-east-1',
    removalPolicy: cdk.RemovalPolicy.DESTROY,
    ddbBilling: dynamodb.BillingMode.PAY_PER_REQUEST,
    lambdaSnapStart: false,
    lambdaMemoryMB: 256,
    alarmEmail: 'avadhani.a@northeastern.edu',
    enableXRay: true,
  },
  beta: {
    stage: 'beta',
    account: process.env.CDK_DEFAULT_ACCOUNT ?? '234567890123',
    region: 'us-east-1',
    removalPolicy: cdk.RemovalPolicy.DESTROY,
    ddbBilling: dynamodb.BillingMode.PAY_PER_REQUEST,
    lambdaSnapStart: false,
    lambdaMemoryMB: 512,
    alarmEmail: 'avadhani.a@northeastern.edu',
    enableXRay: true,
  },
  gamma: {
    stage: 'gamma',
    account: process.env.CDK_DEFAULT_ACCOUNT ?? '345678901234',
    region: 'us-east-1',
    removalPolicy: cdk.RemovalPolicy.RETAIN,
    ddbBilling: dynamodb.BillingMode.PROVISIONED,
    ddbProvisionedCapacity: { read: 10, write: 10 },
    lambdaSnapStart: true,
    lambdaMemoryMB: 1024,
    alarmEmail: 'avadhani.a@northeastern.edu',
    enableXRay: true,
  },
  prod: {
    stage: 'prod',
    account: process.env.CDK_DEFAULT_ACCOUNT ?? '456789012345',
    region: 'us-east-1',
    removalPolicy: cdk.RemovalPolicy.RETAIN,
    ddbBilling: dynamodb.BillingMode.PROVISIONED,
    ddbProvisionedCapacity: { read: 50, write: 50 },
    lambdaSnapStart: true,
    lambdaMemoryMB: 1024,
    lambdaProvisionedConcurrency: 5,
    alarmEmail: 'avadhani.a@northeastern.edu',
    enableXRay: true,
  },
};
