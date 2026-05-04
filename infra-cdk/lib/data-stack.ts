import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface DataStackProps extends cdk.StackProps {
  config: StageConfig;
}

export class DataStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: DataStackProps) {
    super(scope, id, props);
    // Story 2.2: DynamoDB Models and Versions tables with GSIs, PITR, TTL
    // Story 2.3: S3 artifact bucket with versioning, encryption, lifecycle rules
  }
}
