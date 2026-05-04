#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Bootstrap Gradle wrapper if the jar is missing (first checkout on a machine with Gradle installed)
if [ ! -f "$ROOT/lambda-handlers/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "==> Gradle wrapper jar not found. Generating with 'gradle wrapper'..."
  if ! command -v gradle &>/dev/null; then
    echo "ERROR: gradle is not installed. Run 'gradle wrapper --gradle-version 8.12'" \
         "inside lambda-handlers/ to generate the wrapper, then re-run this script."
    exit 1
  fi
  (cd "$ROOT/lambda-handlers" && gradle wrapper --gradle-version 8.12)
fi

# Gradle's Kotlin/Groovy DSL requires Java ≤ 21 to run the build script.
# The Java 21 *compilation* toolchain is auto-provisioned by the Foojay plugin.
# If JAVA_HOME is set to Java 25 (or another unsupported version), fall back to Java 11.
if [[ "${JAVA_HOME:-}" == *"jdk-25"* || "$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)" -gt 21 ]]; then
  JAVA_11=$(/usr/libexec/java_home -v 11 2>/dev/null || true)
  if [ -n "$JAVA_11" ]; then
    export JAVA_HOME="$JAVA_11"
    echo "==> Java 25 detected; using Java 11 to run Gradle (Foojay will provision Java 21 for compilation)."
  fi
fi

echo "==> [1/3] Building Smithy model..."
(cd "$ROOT/smithy-model" && smithy build)

echo "==> [2/3] Building Lambda handlers..."
(cd "$ROOT/lambda-handlers" && ./gradlew build)

echo "==> [3/3] Synthesizing CDK stacks (alpha)..."
(cd "$ROOT/infra-cdk" && npm ci && npx cdk synth -c stage=alpha)

echo ""
echo "Build complete."
