import * as fs from 'fs';
import * as path from 'path';
import * as cdk from 'aws-cdk-lib';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface ApiStackProps extends cdk.StackProps {
  config: StageConfig;
  modelHandlerArn?: string;
  versionHandlerArn?: string;
  adminHandlerArn?: string;
  /** Override the parsed OpenAPI document. Tests inject a stub so the
   *  snapshot doesn't depend on the Smithy build output being present. */
  openApiSpecOverride?: object;
}

const MODEL_OPERATIONS = new Set([
  'ListModels', 'CreateModel', 'GetModel', 'DeleteModel',
]);
const VERSION_OPERATIONS = new Set([
  'ListVersions', 'CreateVersion', 'GetVersion', 'GetLatestVersion',
  'DeleteVersion', 'ConfirmVersion',
]);

function lambdaIntegrationUri(region: string, functionArn: string): string {
  return `arn:aws:apigateway:${region}:lambda:path/2015-03-31/functions/${functionArn}/invocations`;
}

export class ApiStack extends cdk.Stack {
  readonly apiId: string;
  readonly apiUrl: string;

  constructor(scope: Construct, id: string, props: ApiStackProps) {
    super(scope, id, props);

    const cfg = props.config;
    const region = props.env?.region ?? 'us-east-1';

    const modelArn =
      props.modelHandlerArn ?? `arn:aws:lambda:${region}:123456789012:function:placeholder-model`;
    const versionArn =
      props.versionHandlerArn ?? `arn:aws:lambda:${region}:123456789012:function:placeholder-version`;
    const adminArn =
      props.adminHandlerArn ?? `arn:aws:lambda:${region}:123456789012:function:placeholder-admin`;

    // ── OpenAPI document: inject AWS_IAM auth + Lambda integrations ───────────

    const openApiDoc: any = props.openApiSpecOverride ?? JSON.parse(
      fs.readFileSync(
        path.join(__dirname, '../../smithy-model/build/smithy/openapi/openapi/ArtifactMgmt.openapi.json'),
        'utf8',
      ),
    );

    // Add sigv4 security scheme
    openApiDoc.components = openApiDoc.components ?? {};
    openApiDoc.components.securitySchemes = {
      sigv4: {
        type: 'apiKey',
        name: 'Authorization',
        in: 'header',
        'x-amazon-apigateway-authtype': 'awsSigv4',
      },
    };

    // Inject security + Lambda integration on every operation
    for (const pathItem of Object.values(openApiDoc.paths) as any[]) {
      for (const operation of Object.values(pathItem) as any[]) {
        if (typeof operation !== 'object' || !operation.operationId) continue;

        const operationId: string = operation.operationId;
        let handlerArn = adminArn;
        if (MODEL_OPERATIONS.has(operationId)) handlerArn = modelArn;
        else if (VERSION_OPERATIONS.has(operationId)) handlerArn = versionArn;

        operation.security = [{ sigv4: [] }];
        operation['x-amazon-apigateway-integration'] = {
          type: 'aws_proxy',
          httpMethod: 'POST',
          uri: lambdaIntegrationUri(region, handlerArn),
          passthroughBehavior: 'when_no_match',
        };
      }
    }

    // ── CloudWatch logs role (account-level, required for execution logging) ──

    const cloudWatchRole = new iam.Role(this, 'ApiGatewayCloudWatchRole', {
      assumedBy: new iam.ServicePrincipal('apigateway.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName(
          'service-role/AmazonAPIGatewayPushToCloudWatchLogs',
        ),
      ],
    });
    new apigateway.CfnAccount(this, 'ApiGatewayAccount', {
      cloudWatchRoleArn: cloudWatchRole.roleArn,
    });

    // ── Access log group ──────────────────────────────────────────────────────

    const accessLogGroup = new logs.LogGroup(this, 'ApiAccessLogs', {
      logGroupName: `/artifact-mgmt/api-access-${cfg.stage}`,
      retention: logs.RetentionDays.ONE_MONTH,
      removalPolicy: cfg.removalPolicy,
    });

    // ── REST API from spec ────────────────────────────────────────────────────

    const throttleBurst = cfg.stage === 'prod' ? 500 : 100;
    const throttleRate = cfg.stage === 'prod' ? 200 : 50;

    const api = new apigateway.SpecRestApi(this, 'Api', {
      restApiName: `artifact-mgmt-api-${cfg.stage}`,
      apiDefinition: apigateway.ApiDefinition.fromInline(openApiDoc),
      deployOptions: {
        stageName: cfg.stage,
        tracingEnabled: cfg.enableXRay,
        metricsEnabled: true,
        loggingLevel: apigateway.MethodLoggingLevel.INFO,
        dataTraceEnabled: false, // NEVER true — logs full request bodies including dep_snapshot
        accessLogDestination: new apigateway.LogGroupLogDestination(accessLogGroup),
        accessLogFormat: apigateway.AccessLogFormat.jsonWithStandardFields(),
        throttlingBurstLimit: throttleBurst,
        throttlingRateLimit: throttleRate,
      },
    });

    // ── Lambda invoke permissions (resource-based policy) ────────────────────

    const addInvokePermission = (fnArn: string, id: string) => {
      new lambda.CfnPermission(this, `${id}InvokePermission`, {
        action: 'lambda:InvokeFunction',
        functionName: fnArn,
        principal: 'apigateway.amazonaws.com',
        sourceArn: api.arnForExecuteApi(),
      });
    };

    if (props.modelHandlerArn) addInvokePermission(modelArn, 'ModelHandler');
    if (props.versionHandlerArn) addInvokePermission(versionArn, 'VersionHandler');
    if (props.adminHandlerArn) addInvokePermission(adminArn, 'AdminHandler');

    this.apiId = api.restApiId;
    this.apiUrl = api.url;

    new cdk.CfnOutput(this, 'ApiId', { value: api.restApiId });
    new cdk.CfnOutput(this, 'ApiUrl', { value: api.url });
  }
}
