import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import { Template } from 'aws-cdk-lib/assertions';
import { ApiStack } from '../lib/api-stack';
import { ComputeStack } from '../lib/compute-stack';
import { DataStack } from '../lib/data-stack';
import { STAGES } from '../lib/stage-config';

// Stable stub asset factory: hash never varies across build environments.
// A fresh Code instance is required per stack (CDK enforces one stack per asset).
const stubCode = () => lambda.Code.fromAsset(path.join(__dirname, 'fixtures/stub-handler.zip'));

const stageNames = ['alpha', 'beta', 'gamma', 'prod'] as const;

for (const stageName of stageNames) {
  const config = STAGES[stageName];
  const env = { account: config.account, region: config.region };

  describe(`DataStack ${stageName}`, () => {
    const app = new cdk.App();
    const stack = new DataStack(app, `TestDataStack-${stageName}`, { config, env });

    it('matches snapshot', () => {
      expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
    });
  });

  describe(`ComputeStack ${stageName}`, () => {
    const app = new cdk.App();
    const stack = new ComputeStack(app, `TestComputeStack-${stageName}`, {
      config,
      env,
      lambdaCode: stubCode(),
    });

    it('matches snapshot', () => {
      expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
    });
  });

  describe(`ApiStack ${stageName}`, () => {
    const app = new cdk.App();
    const stack = new ApiStack(app, `TestApiStack-${stageName}`, { config, env });

    it('matches snapshot', () => {
      expect(Template.fromStack(stack).toJSON()).toMatchSnapshot();
    });
  });
}
