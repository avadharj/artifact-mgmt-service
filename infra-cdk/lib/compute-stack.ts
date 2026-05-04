import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface ComputeStackProps extends cdk.StackProps {
  config: StageConfig;
}

export class ComputeStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: ComputeStackProps) {
    super(scope, id, props);
    // Story 2.4: ModelHandler, VersionHandler, AdminHandler, SweeperHandler Lambdas
    // Story 2.6: EventBridge hourly schedule for SweeperHandler
  }
}
