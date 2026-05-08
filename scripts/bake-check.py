#!/usr/bin/env python3
"""
Bake-check: poll CloudWatch alarms for a stage during its bake window.
Exits non-zero immediately if any alarm enters ALARM state, triggering
CodePipeline to roll back to the previous revision.

Usage:
    python scripts/bake-check.py --stage beta --duration 1800
"""
import argparse
import sys
import time

import boto3


POLL_INTERVAL_SECONDS = 60


def alarms_in_alarm(cw_client, stage: str) -> list[str]:
    paginator = cw_client.get_paginator('describe_alarms')
    breached = []
    for page in paginator.paginate(AlarmNamePrefix=f'artifact-mgmt-', StateValue='ALARM'):
        for alarm in page['MetricAlarms']:
            if stage in alarm['AlarmName']:
                breached.append(alarm['AlarmName'])
    return breached


def main() -> None:
    parser = argparse.ArgumentParser(description='Bake-window alarm poller')
    parser.add_argument('--stage', required=True, help='Deployment stage (beta/gamma/prod)')
    parser.add_argument('--duration', type=int, required=True,
                        help='Bake window length in seconds')
    args = parser.parse_args()

    cw = boto3.client('cloudwatch')
    deadline = time.monotonic() + args.duration

    print(f'[bake-check] stage={args.stage} window={args.duration}s '
          f'poll_interval={POLL_INTERVAL_SECONDS}s')

    while True:
        breached = alarms_in_alarm(cw, args.stage)
        if breached:
            print(f'[bake-check] FAIL — alarms in ALARM state: {breached}', file=sys.stderr)
            sys.exit(1)

        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break

        sleep = min(POLL_INTERVAL_SECONDS, remaining)
        print(f'[bake-check] OK — {remaining:.0f}s remaining, sleeping {sleep:.0f}s')
        time.sleep(sleep)

    print(f'[bake-check] PASS — stage={args.stage} baked clean for {args.duration}s')


if __name__ == '__main__':
    main()
