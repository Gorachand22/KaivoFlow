#!/usr/bin/env bash
# Android-free smoke suite for the engine and catalog. Gradle remains the normal build path.
set -euo pipefail
: "${KOTLINC_HOME:?Set KOTLINC_HOME to a compatible Kotlin compiler installation}"
: "${TEST_DEPS_DIR:?Set TEST_DEPS_DIR to the directory containing the four JVM test jars}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP="$KOTLINC_HOME/lib/kotlin-stdlib.jar:$TEST_DEPS_DIR/kotlinx-coroutines-core-jvm-1.10.2.jar:$TEST_DEPS_DIR/kotlinx-collections-immutable-jvm-0.3.8.jar:$TEST_DEPS_DIR/junit-4.13.2.jar:$TEST_DEPS_DIR/hamcrest-core-1.3.jar"
IFS=: read -r -a JARS <<< "$CP"
for jar in "${JARS[@]}"; do [[ -f "$jar" ]] || { echo "Missing jar: $jar" >&2; exit 1; }; done
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
"$KOTLINC_HOME/bin/kotlinc" \
  "$ROOT/core/model/src/main/kotlin" "$ROOT/core/engine/src/main/kotlin" \
  "$ROOT/core/nodes/src/main/kotlin" "$ROOT/core/nodes/src/test/kotlin" \
  "$ROOT/core/engine/src/test/kotlin" -classpath "$CP" -jvm-target 11 -d "$OUTPUT"
java -cp "$OUTPUT:$CP" org.junit.runner.JUnitCore \
  com.kaivoflow.core.nodes.NodeExecutorTest \
  com.kaivoflow.core.nodes.NodeCatalogTest \
  com.kaivoflow.core.nodes.IntegrationFlowTest \
  com.kaivoflow.core.nodes.TriggerReliabilityTest \
  com.kaivoflow.core.nodes.AiSafetyTest \
  com.kaivoflow.core.nodes.MobileTriggerTest \
  com.kaivoflow.core.nodes.ExtendedNodeTest \
  com.kaivoflow.core.engine.OAuth2TokenManagerTest \
  com.kaivoflow.core.engine.FlowRunnerTest \
  com.kaivoflow.core.engine.FlowValidatorTest \
  com.kaivoflow.core.engine.ValuesTest \
  com.kaivoflow.core.engine.ActiveRunRegistryTest
