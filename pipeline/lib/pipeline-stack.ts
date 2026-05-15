import * as cdk from 'aws-cdk-lib';
import * as codebuild from 'aws-cdk-lib/aws-codebuild';
import * as pipelines from 'aws-cdk-lib/pipelines';
import { Construct } from 'constructs';
import { ApiStack } from '../../infra-cdk/lib/api-stack';
import { ComputeStack } from '../../infra-cdk/lib/compute-stack';
import { DataStack } from '../../infra-cdk/lib/data-stack';
import { STAGES } from '../../infra-cdk/lib/stage-config';

// ── ServiceStage ─────────────────────────────────────────────────────────────
// One CDK Stage per deployment environment; wraps DataStack + ComputeStack + ApiStack.

interface ServiceStageProps extends cdk.StageProps {
  stageName: string;
}

class ServiceStage extends cdk.Stage {
  constructor(scope: Construct, id: string, props: ServiceStageProps) {
    super(scope, id, props);

    const cfg = STAGES[props.stageName];

    const dataStack = new DataStack(this, `ArtifactMgmt-Data-${cfg.stage}`, {
      config: cfg,
    });

    const computeStack = new ComputeStack(this, `ArtifactMgmt-Compute-${cfg.stage}`, {
      config: cfg,
      modelsTableName: dataStack.modelsTableName,
      modelsTableArn: dataStack.modelsTableArn,
      versionsTableName: dataStack.versionsTableName,
      versionsTableArn: dataStack.versionsTableArn,
      artifactsBucketName: dataStack.artifactsBucketName,
      artifactsBucketArn: dataStack.artifactsBucketArn,
    });

    new ApiStack(this, `ArtifactMgmt-Api-${cfg.stage}`, {
      config: cfg,
      modelHandlerArn: computeStack.modelHandlerArn,
      versionHandlerArn: computeStack.versionHandlerArn,
    });
  }
}

// ── PipelineStack ─────────────────────────────────────────────────────────────

export class PipelineStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    // GitHub connection ARN must be passed as CDK context (-c githubConnectionArn=...).
    // Create the connection once in the AWS Console (Developer Tools > Connections)
    // then bootstrap all target accounts with: cdk bootstrap --trust <pipeline-account-id>
    const connectionArn = this.node.tryGetContext('githubConnectionArn') as string | undefined;
    if (!connectionArn) {
      throw new Error(
        'Missing CDK context: githubConnectionArn. ' +
        'Pass -c githubConnectionArn=<arn:aws:codestar-connections:...>',
      );
    }

    const source = pipelines.CodePipelineSource.connection(
      'avadharj/artifact-mgmt-service',
      'main',
      { connectionArn },
    );

    // ── Synth step ─────────────────────────────────────────────────────────────
    // 1. Install Smithy CLI + pre-populate Maven cache (mirrors the CI workflow).
    // 2. smithy build → produces ArtifactMgmt.openapi.json consumed by ApiStack.
    // 3. Gradle build → produces handlers.zip consumed by ComputeStack.
    // 4. make sdk → verify SDK generation.
    // 5. Java unit tests (requires Docker/privileged for Testcontainers).
    // 6. Synthesize this pipeline CDK app (contains all ServiceStages).

    const synth = new pipelines.ShellStep('Synth', {
      input: source,
      installCommands: [
        'npm ci',
        'npm ci --prefix infra-cdk',
        'npm ci --prefix pipeline',
        // Install Smithy CLI
        'curl -sL https://github.com/smithy-lang/smithy/releases/download/1.50.0/smithy-cli-linux-x86_64.zip -o /tmp/smithy-cli.zip',
        'unzip -q /tmp/smithy-cli.zip -d /tmp/smithy-cli',
        'export PATH="/tmp/smithy-cli/smithy-cli-linux-x86_64/bin:$PATH"',
      ],
      commands: [
        // Build: Smithy → OpenAPI, Gradle → handlers.zip
        'cd smithy-model && smithy build && cd ..',
        'cd lambda-handlers && ./gradlew build && cd ..',
        // SDK generation
        'make sdk',
        // Unit tests (Testcontainers requires privileged Docker)
        'cd lambda-handlers && ./gradlew test && cd ..',
        // Synthesize the pipeline app (includes all ServiceStages)
        'cd pipeline && npx cdk synth',
      ],
      primaryOutputDirectory: 'pipeline/cdk.out',
    });

    const pipeline = new pipelines.CodePipeline(this, 'Pipeline', {
      pipelineName: 'artifact-mgmt-pipeline',
      selfMutation: true,
      crossAccountKeys: true,
      synth,
      codeBuildDefaults: {
        // Privileged mode enables Docker-in-Docker for Testcontainers (Java unit tests).
        buildEnvironment: {
          buildImage: codebuild.LinuxBuildImage.STANDARD_7_0,
          privileged: true,
          environmentVariables: {
            JAVA_HOME: { value: '/usr/lib/jvm/java-21-amazon-corretto' },
          },
        },
      },
    });

    // ── Alpha ── auto-deploy, no bake ─────────────────────────────────────────

    pipeline.addStage(
      new ServiceStage(this, 'Alpha', {
        stageName: 'alpha',
        env: { account: STAGES.alpha.account, region: STAGES.alpha.region },
      }),
    );

    // ── Beta ── integration tests + 30-min bake ───────────────────────────────
    // BakeCheck explicitly depends on IntegTests so they run sequentially, not
    // in parallel. The bake window should not start until tests have passed.

    const beta = pipeline.addStage(
      new ServiceStage(this, 'Beta', {
        stageName: 'beta',
        env: { account: STAGES.beta.account, region: STAGES.beta.region },
      }),
    );

    const betaIntegTests = new pipelines.ShellStep('IntegTests', {
      commands: ['./scripts/integ-test.sh beta'],
    });
    const betaBakeCheck = new pipelines.ShellStep('BakeCheck', {
      commands: ['python scripts/bake-check.py --stage beta --duration 1800'],
    });
    betaBakeCheck.addStepDependency(betaIntegTests);
    beta.addPost(betaIntegTests, betaBakeCheck);

    // ── Gamma ── canaries (deferred to story 7.6) + 2-hour bake ──────────────

    const gamma = pipeline.addStage(
      new ServiceStage(this, 'Gamma', {
        stageName: 'gamma',
        env: { account: STAGES.gamma.account, region: STAGES.gamma.region },
      }),
    );

    // Canary step defined here; the canary suite itself is story 7.6.
    const gammaCanaries = new pipelines.ShellStep('Canaries', {
      commands: ['./scripts/run-canaries.sh gamma'],
    });
    const gammaBakeCheck = new pipelines.ShellStep('BakeCheck', {
      commands: ['python scripts/bake-check.py --stage gamma --duration 7200'],
    });
    gammaBakeCheck.addStepDependency(gammaCanaries);
    gamma.addPost(gammaCanaries, gammaBakeCheck);

    // ── Prod ── manual approval gate + 1-hour bake ───────────────────────────

    const prod = pipeline.addStage(
      new ServiceStage(this, 'Prod', {
        stageName: 'prod',
        env: { account: STAGES.prod.account, region: STAGES.prod.region },
      }),
    );

    prod.addPre(new pipelines.ManualApprovalStep('PromoteToProd'));

    // BakeCheck then publish: SDK publish only happens after a clean bake.
    const prodBakeCheck = new pipelines.ShellStep('BakeCheck', {
      commands: ['python scripts/bake-check.py --stage prod --duration 3600'],
    });
    // Publish Java + Python SDKs to CodeArtifact, tagged with the Smithy model version.
    // The CodeArtifact domain/repo names are resolved from CDK context at pipeline-deploy time.
    const publishSdks = new pipelines.ShellStep('PublishSdks', {
      commands: [
        'SMITHY_VERSION=$(python3 -c "import json; d=json.load(open(\'smithy-model/smithy-build.json\')); print(d[\'version\'])")',
        // Java SDK → CodeArtifact Maven
        'aws codeartifact login --tool mvn --domain artifact-mgmt --repository artifact-mgmt-sdk',
        'cd java-sdk && ./gradlew publish -Pversion=$SMITHY_VERSION && cd ..',
        // Python SDK → CodeArtifact PyPI
        'aws codeartifact login --tool pip --domain artifact-mgmt --repository artifact-mgmt-sdk',
        'cd build/python-sdk && pip install twine && twine upload --repository codeartifact dist/*.whl && cd ../..',
      ],
    });
    publishSdks.addStepDependency(prodBakeCheck);
    prod.addPost(prodBakeCheck, publishSdks);

    // Queue executions so concurrent pushes don't clobber in-flight deployments.
    // buildPipeline() must be called before accessing pipeline.pipeline (the L2).
    pipeline.buildPipeline();
    const cfnPipeline = pipeline.pipeline.node.defaultChild as cdk.CfnResource;
    cfnPipeline.addPropertyOverride('ExecutionMode', 'QUEUED');
  }
}
