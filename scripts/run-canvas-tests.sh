#!/usr/bin/env bash
# Android-free canvas geometry tests; Gradle remains the normal build path.
set -euo pipefail
: "${KOTLINC_HOME:?Set KOTLINC_HOME to a compatible Kotlin compiler installation}"
: "${TEST_DEPS_DIR:?Set TEST_DEPS_DIR to the directory containing the JVM test jars}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP="$KOTLINC_HOME/lib/kotlin-stdlib.jar:$TEST_DEPS_DIR/kotlinx-collections-immutable-jvm-0.3.8.jar:$TEST_DEPS_DIR/junit-4.13.2.jar:$TEST_DEPS_DIR/hamcrest-core-1.3.jar"
IFS=: read -r -a JARS <<< "$CP"
for jar in "${JARS[@]}"; do [[ -f "$jar" ]] || { echo "Missing jar: $jar" >&2; exit 1; }; done
OUTPUT="$(mktemp -d)"
trap 'rm -rf "$OUTPUT"' EXIT
"$KOTLINC_HOME/bin/kotlinc" \
  "$ROOT/core/model/src/main/kotlin" \
  "$ROOT/feature/canvas/src/main/kotlin/com/kaivoflow/feature/canvas/layout/CanvasGeometry.kt" \
  "$ROOT/feature/canvas/src/test/kotlin/com/kaivoflow/feature/canvas/layout/CanvasGeometryTest.kt" \
  -classpath "$CP" -jvm-target 11 -d "$OUTPUT"
java -cp "$OUTPUT:$CP" org.junit.runner.JUnitCore \
  com.kaivoflow.feature.canvas.layout.CanvasGeometryTest
