import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { ApiStack } from '../lib/api-stack';
import { ComputeStack } from '../lib/compute-stack';
import { DataStack } from '../lib/data-stack';
import { STAGES } from '../lib/stage-config';

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
    const stack = new ComputeStack(app, `TestComputeStack-${stageName}`, { config, env });

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
