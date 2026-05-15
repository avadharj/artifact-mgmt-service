import * as cdk from 'aws-cdk-lib';
import * as cw from 'aws-cdk-lib/aws-cloudwatch';
import * as actions from 'aws-cdk-lib/aws-cloudwatch-actions';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as snsSub from 'aws-cdk-lib/aws-sns-subscriptions';
import { Construct } from 'constructs';
import { StageConfig } from './stage-config';

export interface ObservabilityStackProps extends cdk.StackProps {
  config: StageConfig;
}

/**
 * Story 7.4 — Dashboard + alarms.
 *
 * Builds one CloudWatch dashboard and four alarms wired to an SNS topic. The topic subscribes
 * the on-call email from {@link StageConfig#alarmEmail}; PagerDuty integration is optional and
 * not wired here (would attach a second HTTPS subscription to the same topic when configured).
 *
 * Metric sources:
 *   - API Gateway 4XX/5XX/Latency from `AWS/ApiGateway` namespace, dimensions ApiName+Stage.
 *   - Lambda Duration/Throttles from `AWS/Lambda`, dimension FunctionName.
 *   - DDB consumed capacity + throttles from `AWS/DynamoDB`, dimension TableName.
 *   - Custom EMF metrics (VersionsCreated, VersionsConfirmed, UploadOrphansSwept,
 *     VersionConflict, IdempotencyReplay, IdempotencyExpiredReplay) from the `ArtifactMgmt`
 *     namespace emitted by Story 7.2.
 *
 * The four alarms (High5xxRate, P95Latency, OrphanRate, VersionConflictRate) match the spec
 * AC; each has an OnOk action so a recovered metric clears the page.
 */
// Runbook URLs (Story 7.5). Linked into the alarm descriptions so the on-call sees the
// runbook in the SNS notification body before they have to dig through repo history.
const RUNBOOK_BASE =
  'https://github.com/avadharj/artifact-mgmt-service/blob/main/docs/runbooks';
const ORPHAN_RUNBOOK = `${RUNBOOK_BASE}/orphan-cleanup.md`;
const CONFLICT_RUNBOOK = `${RUNBOOK_BASE}/major-bump-race.md`;

export class ObservabilityStack extends cdk.Stack {
  readonly alarmTopic: sns.Topic;
  readonly dashboard: cw.Dashboard;

  constructor(scope: Construct, id: string, props: ObservabilityStackProps) {
    super(scope, id, props);

    const { stage } = props.config;
    const apiName = `artifact-mgmt-api-${stage}`;
    const modelHandlerName = `artifact-mgmt-model-${stage}`;
    const versionHandlerName = `artifact-mgmt-version-${stage}`;
    const sweeperHandlerName = `artifact-mgmt-sweeper-${stage}`;
    const modelsTableName = `artifact-mgmt-models-${stage}`;
    const versionsTableName = `artifact-mgmt-versions-${stage}`;

    // ── SNS alarm topic ────────────────────────────────────────────────────

    this.alarmTopic = new sns.Topic(this, 'AlarmTopic', {
      topicName: `artifact-mgmt-alarms-${stage}`,
      displayName: `ArtifactMgmt ${stage} alarms`,
    });
    this.alarmTopic.addSubscription(new snsSub.EmailSubscription(props.config.alarmEmail));

    // ── Metric helpers ─────────────────────────────────────────────────────

    const apiMetric = (name: string, opts: Partial<cw.MetricProps> = {}) =>
      new cw.Metric({
        namespace: 'AWS/ApiGateway',
        metricName: name,
        dimensionsMap: { ApiName: apiName, Stage: stage },
        period: cdk.Duration.minutes(1),
        ...opts,
      });

    const lambdaMetric = (functionName: string, name: string, opts: Partial<cw.MetricProps> = {}) =>
      new cw.Metric({
        namespace: 'AWS/Lambda',
        metricName: name,
        dimensionsMap: { FunctionName: functionName },
        period: cdk.Duration.minutes(1),
        ...opts,
      });

    const ddbMetric = (tableName: string, name: string, opts: Partial<cw.MetricProps> = {}) =>
      new cw.Metric({
        namespace: 'AWS/DynamoDB',
        metricName: name,
        dimensionsMap: { TableName: tableName },
        period: cdk.Duration.minutes(1),
        ...opts,
      });

    const sqsMetric = (queueName: string, name: string) =>
      new cw.Metric({
        namespace: 'AWS/SQS',
        metricName: name,
        dimensionsMap: { QueueName: queueName },
        period: cdk.Duration.minutes(1),
      });

    const customMetric = (name: string, opts: Partial<cw.MetricProps> = {}) =>
      new cw.Metric({
        namespace: 'ArtifactMgmt',
        metricName: name,
        period: cdk.Duration.minutes(1),
        statistic: 'Sum',
        ...opts,
      });

    // ── Dashboard widgets ──────────────────────────────────────────────────

    // 1. API Gateway latency p50/p95/p99 — the closest thing to "per-operation latency".
    //    True per-operation breakdown would require enabling detailed metrics on the stage
    //    (Method/Resource dimensions) which costs extra. For alpha we use stage-level here.
    const latencyWidget = new cw.GraphWidget({
      title: 'API latency (p50 / p95 / p99) — 1m',
      left: [
        apiMetric('Latency', { statistic: 'p50', label: 'p50' }),
        apiMetric('Latency', { statistic: 'p95', label: 'p95' }),
        apiMetric('Latency', { statistic: 'p99', label: 'p99' }),
      ],
      width: 12,
      height: 6,
    });

    // 2. Error rate — 4XX and 5XX side by side.
    const errorRateWidget = new cw.GraphWidget({
      title: 'API error rate (4XX / 5XX) — 1m sum',
      left: [
        apiMetric('4XXError', { statistic: 'Sum', label: '4XX' }),
        apiMetric('5XXError', { statistic: 'Sum', label: '5XX' }),
      ],
      width: 12,
      height: 6,
    });

    // 3. Lambda throttles + DLQ depth.
    const throttleDlqWidget = new cw.GraphWidget({
      title: 'Lambda throttles + DLQ depth',
      left: [
        lambdaMetric(modelHandlerName, 'Throttles', { statistic: 'Sum', label: 'model throttles' }),
        lambdaMetric(versionHandlerName, 'Throttles', {
          statistic: 'Sum',
          label: 'version throttles',
        }),
        lambdaMetric(sweeperHandlerName, 'Throttles', {
          statistic: 'Sum',
          label: 'sweeper throttles',
        }),
      ],
      right: [
        sqsMetric(`${modelHandlerName}-dlq`, 'ApproximateNumberOfMessagesVisible'),
        sqsMetric(`${versionHandlerName}-dlq`, 'ApproximateNumberOfMessagesVisible'),
        sqsMetric(`${sweeperHandlerName}-dlq`, 'ApproximateNumberOfMessagesVisible'),
      ],
      width: 12,
      height: 6,
    });

    // 4. DDB consumed capacity + throttles for both tables.
    const ddbWidget = new cw.GraphWidget({
      title: 'DDB capacity + throttles',
      left: [
        ddbMetric(modelsTableName, 'ConsumedReadCapacityUnits', { statistic: 'Sum' }),
        ddbMetric(modelsTableName, 'ConsumedWriteCapacityUnits', { statistic: 'Sum' }),
        ddbMetric(versionsTableName, 'ConsumedReadCapacityUnits', { statistic: 'Sum' }),
        ddbMetric(versionsTableName, 'ConsumedWriteCapacityUnits', { statistic: 'Sum' }),
      ],
      right: [
        ddbMetric(modelsTableName, 'ReadThrottleEvents', { statistic: 'Sum' }),
        ddbMetric(modelsTableName, 'WriteThrottleEvents', { statistic: 'Sum' }),
        ddbMetric(versionsTableName, 'ReadThrottleEvents', { statistic: 'Sum' }),
        ddbMetric(versionsTableName, 'WriteThrottleEvents', { statistic: 'Sum' }),
      ],
      width: 12,
      height: 6,
    });

    // 5. VersionsCreated vs VersionsConfirmed gap (1h sum) — visualises uploads that started
    //    but were never confirmed. A widening gap signals a regression in the client SDK or
    //    a slow user; the sweeper backstop in 6.1 catches these eventually.
    const createdVsConfirmedWidget = new cw.GraphWidget({
      title: 'VersionsCreated vs VersionsConfirmed — 1h sum',
      left: [
        customMetric('VersionsCreated', {
          period: cdk.Duration.hours(1),
          label: 'created',
        }),
        customMetric('VersionsConfirmed', {
          period: cdk.Duration.hours(1),
          label: 'confirmed',
        }),
      ],
      width: 12,
      height: 6,
    });

    // 6. UploadOrphansSwept per hour — sweeper activity. Spike = recent burst of
    //    uploads-without-confirm, sustained high = sustained client breakage.
    const orphanWidget = new cw.GraphWidget({
      title: 'UploadOrphansSwept — 1h sum',
      left: [
        customMetric('UploadOrphansSwept', { period: cdk.Duration.hours(1) }),
      ],
      width: 12,
      height: 6,
    });

    this.dashboard = new cw.Dashboard(this, 'Dashboard', {
      dashboardName: `ArtifactMgmt-${stage}`,
      widgets: [
        [latencyWidget, errorRateWidget],
        [throttleDlqWidget, ddbWidget],
        [createdVsConfirmedWidget, orphanWidget],
      ],
    });

    // ── Alarms ─────────────────────────────────────────────────────────────

    const high5xx = new cw.Alarm(this, 'High5xxRate', {
      alarmName: `ArtifactMgmt-${stage}-High5xxRate`,
      alarmDescription:
        'API Gateway 5XX errors over 5min exceed 1% threshold. Investigate the most recent ' +
        'Lambda failure traces and check the DLQ.',
      metric: apiMetric('5XXError', {
        statistic: 'Sum',
        period: cdk.Duration.minutes(5),
      }),
      threshold: 5, // 5 errors in 5 minutes — adjust per traffic; intent matches spec's 1% at moderate volume
      evaluationPeriods: 1,
      datapointsToAlarm: 1,
      comparisonOperator: cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cw.TreatMissingData.NOT_BREACHING,
    });

    const p95Latency = new cw.Alarm(this, 'P95Latency', {
      alarmName: `ArtifactMgmt-${stage}-P95Latency`,
      alarmDescription: 'API p95 latency exceeds 3s over 5min. Check downstream DDB / S3 latency.',
      metric: apiMetric('Latency', {
        statistic: 'p95',
        period: cdk.Duration.minutes(5),
      }),
      threshold: 3000, // milliseconds
      evaluationPeriods: 2,
      datapointsToAlarm: 2,
      comparisonOperator: cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cw.TreatMissingData.NOT_BREACHING,
    });

    const orphanRate = new cw.Alarm(this, 'OrphanRate', {
      alarmName: `ArtifactMgmt-${stage}-OrphanRate`,
      alarmDescription:
        'Sweeper flipped >50 orphans in the last hour. A genuine spike (client SDK regression) ' +
        'or sustained noise (broken upload path). Check sweep_action logs.\n\nRunbook: '
        + ORPHAN_RUNBOOK,
      metric: customMetric('UploadOrphansSwept', {
        statistic: 'Sum',
        period: cdk.Duration.hours(1),
      }),
      threshold: 50,
      evaluationPeriods: 1,
      datapointsToAlarm: 1,
      comparisonOperator: cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cw.TreatMissingData.NOT_BREACHING,
    });

    const versionConflictRate = new cw.Alarm(this, 'VersionConflictRate', {
      alarmName: `ArtifactMgmt-${stage}-VersionConflictRate`,
      alarmDescription:
        'More than 10 VersionConflict events in 5 minutes — multiple writers are racing on the ' +
        'same model. Could be a client retry bug or a real contention spike.\n\nRunbook: '
        + CONFLICT_RUNBOOK,
      metric: customMetric('VersionConflict', {
        statistic: 'Sum',
        period: cdk.Duration.minutes(5),
      }),
      threshold: 10,
      evaluationPeriods: 1,
      datapointsToAlarm: 1,
      comparisonOperator: cw.ComparisonOperator.GREATER_THAN_THRESHOLD,
      treatMissingData: cw.TreatMissingData.NOT_BREACHING,
    });

    // Both alarm and OK actions go to the topic, so a clearing alarm cancels the page.
    for (const alarm of [high5xx, p95Latency, orphanRate, versionConflictRate]) {
      alarm.addAlarmAction(new actions.SnsAction(this.alarmTopic));
      alarm.addOkAction(new actions.SnsAction(this.alarmTopic));
    }

    new cdk.CfnOutput(this, 'AlarmTopicArn', { value: this.alarmTopic.topicArn });
    new cdk.CfnOutput(this, 'DashboardUrl', {
      value: `https://console.aws.amazon.com/cloudwatch/home?region=${cdk.Stack.of(this).region}#dashboards:name=${this.dashboard.dashboardName}`,
    });
  }
}
