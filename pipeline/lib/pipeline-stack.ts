import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';

export class PipelineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);
    // Story 2.7: Self-mutating CodePipeline — alpha → beta → gamma → prod
    // Uses pipelines.CodePipeline (L3); each stage gated on tests + bake check.
    // Direct cdk deploy is ONLY for alpha. Beta+ goes through this pipeline.
  }
}
