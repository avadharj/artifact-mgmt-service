import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface ApiStackProps extends cdk.StackProps {
  config: StageConfig;
}

export class ApiStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);
    // Story 2.5: API Gateway REST API loaded from Smithy-generated OpenAPI spec
    //            AWS_IAM auth on all routes; dataTraceEnabled: false always
  }
}
