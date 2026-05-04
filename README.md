# artifact-mgmt-service

The backend of a three-component ML model lifecycle platform. Scientists save trained models with metadata + a captured dependency snapshot; this service stores the bytes, owns the version counter, and exposes a REST API.

## Prerequisites

| Tool | Version | Install |
|---|---|---|
| Node.js | 20 | [nodejs.org](https://nodejs.org) or `nvm install 20` |
| Java (Corretto) | 21 | [aws.amazon.com/corretto](https://aws.amazon.com/corretto/) |
| AWS CLI | v2 | [docs.aws.amazon.com/cli](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html) |
| Smithy CLI | 1.50+ | `brew install smithy-lang/tap/smithy` or [smithy.io](https://smithy.io/2.0/guides/smithy-cli/cli_installation.html) |
| Gradle | 8.7 (first run only) | Used to generate the Gradle wrapper; subsequent builds use `./gradlew` |

## First-time setup

```bash
# Install root dev tools (husky pre-commit hooks)
npm install

# Generate the Gradle wrapper jar (only needed once per machine)
cd lambda-handlers && gradle wrapper --gradle-version 8.7 && cd ..

# Install CDK dependencies
cd infra-cdk && npm install && cd ..
```

## Build

```bash
./scripts/build.sh
```

Runs: `smithy build` → `./gradlew build` → `npm ci && cdk synth -c stage=alpha`.

## Common commands

| Task | Command |
|---|---|
| Smithy validate only | `cd smithy-model && smithy build` |
| Java unit tests | `cd lambda-handlers && ./gradlew test` |
| Java format (check) | `cd lambda-handlers && ./gradlew spotlessCheck` |
| Java format (fix) | `cd lambda-handlers && ./gradlew spotlessApply` |
| CDK synth (alpha) | `cd infra-cdk && npx cdk synth -c stage=alpha` |
| CDK deploy (alpha only) | `cd infra-cdk && npx cdk deploy -c stage=alpha` |

**Never** use `cdk deploy` for beta/gamma/prod — those stages go through CodePipeline.

## Before committing

```bash
cd lambda-handlers && ./gradlew spotlessApply spotlessCheck test
cd infra-cdk && npm test && npx cdk synth -c stage=alpha
```
