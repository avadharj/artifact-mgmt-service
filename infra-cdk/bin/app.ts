#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { ApiStack } from '../lib/api-stack';
import { ComputeStack } from '../lib/compute-stack';
import { DataStack } from '../lib/data-stack';
import { STAGES } from '../lib/stage-config';

const app = new cdk.App();

const stageName = app.node.tryGetContext('stage') as string;
if (!stageName) {
  throw new Error('Stage context required. Pass -c stage=<alpha|beta|gamma|prod>');
}

const config = STAGES[stageName];
if (!config) {
  throw new Error(
    `Unknown stage: "${stageName}". Valid stages: ${Object.keys(STAGES).join(', ')}`
  );
}

const env = { account: config.account, region: config.region };

const dataStack = new DataStack(app, `ArtifactMgmt-Data-${stageName}`, { config, env });
const computeStack = new ComputeStack(app, `ArtifactMgmt-Compute-${stageName}`, { config, env });
const apiStack = new ApiStack(app, `ArtifactMgmt-Api-${stageName}`, { config, env });

[dataStack, computeStack, apiStack].forEach((stack) => {
  cdk.Tags.of(stack).add('Stage', stageName);
  cdk.Tags.of(stack).add('Service', 'ArtifactMgmt');
  cdk.Tags.of(stack).add('Owner', 'arjun');
});
