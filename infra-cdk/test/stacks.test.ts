import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { ApiStack } from '../lib/api-stack';
import { ComputeStack } from '../lib/compute-stack';
import { DataStack } from '../lib/data-stack';
import { STAGES } from '../lib/stage-config';

const config = STAGES.alpha;
const env = { account: config.account, region: config.region };

describe('DataStack alpha', () => {
  const app = new cdk.App();
  const stack = new DataStack(app, 'TestDataStack', { config, env });

  it('synthesizes', () => {
    expect(Template.fromStack(stack).toJSON()).toBeDefined();
  });
});

describe('ComputeStack alpha', () => {
  const app = new cdk.App();
  const stack = new ComputeStack(app, 'TestComputeStack', { config, env });

  it('synthesizes', () => {
    expect(Template.fromStack(stack).toJSON()).toBeDefined();
  });
});

describe('ApiStack alpha', () => {
  const app = new cdk.App();
  const stack = new ApiStack(app, 'TestApiStack', { config, env });

  it('synthesizes', () => {
    expect(Template.fromStack(stack).toJSON()).toBeDefined();
  });
});
