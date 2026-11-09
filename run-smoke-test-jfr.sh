#!/usr/bin/env bash
#
# Build the local Spring Boot plugin/loader, package the web-secure-jdbc
# smoke test as an executable jar, and run it with JFR.
#
# Prerequisites (one-time setup):
#   * mavenLocal() is present in the pluginManagement repositories in
#     settings.gradle.
#   * smoke-test/spring-boot-smoke-test-web-secure-jdbc/build.gradle applies
#     id 'org.springframework.boot' with the local project version.
#
# Usage:
#   ./run-smoke-test-jfr.sh [options] [-- <application arguments>]
#
# Options:
#   -o, --output FILE   JFR output file (default: recording-<timestamp>.jfr)
#   -n, --no-publish    Skip publishing the local plugin and its modules
#   -b, --no-build      Skip the clean + bootJar step
#   -h, --help          Show this help
#
# Environment:
#   JAVA                Java executable (default: java)
#   JAVA_OPTS           Extra JVM options (space separated)
#   JFR_SETTINGS        JFR settings name (default: profile)
#   VERIFY_JAR          Set to 0 to skip the loader/jar sanity checks
#
# Examples:
#   ./run-smoke-test-jfr.sh
#   ./run-smoke-test-jfr.sh -o recording.jfr
#   ./run-smoke-test-jfr.sh --no-publish -- --server.port=8097
#
# Note: the JFR recording is written when the app exits. Press Ctrl+C to stop
# the app and flush the recording (SIGKILL will not produce a recording).
#
# For a JDK 25 AOT-cache run (much faster startup), the application must be run
# from its extracted form; that is a separate workflow:
#   java -Djarmode=tools -jar <jar> extract --destination /tmp/aotapp
#   cd /tmp/aotapp
#   java -XX:AOTCacheOutput=/tmp/app.aot -Dspring.context.exit=onRefresh -jar <jar>
#   java -XX:AOTCache=/tmp/app.aot -jar <jar>

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

PROJECT_VERSION="$(sed -n 's/^version=//p' "$ROOT/gradle.properties" | head -1)"
MODULE=":smoke-test:spring-boot-smoke-test-web-secure-jdbc"
MODULE_DIR="$ROOT/smoke-test/spring-boot-smoke-test-web-secure-jdbc"
JAR="$MODULE_DIR/build/libs/spring-boot-smoke-test-web-secure-jdbc-${PROJECT_VERSION}.jar"

PUBLISH=1
BUILD=1
OUT=""
VERIFY_JAR="${VERIFY_JAR:-1}"
APP_ARGS=()

usage() {
	cat <<'EOF'
Build the local Spring Boot plugin/loader, package the web-secure-jdbc
smoke test as an executable jar, and run it with JFR.

Prerequisites (one-time setup):
  * mavenLocal() is present in the pluginManagement repositories in
    settings.gradle.
  * smoke-test/spring-boot-smoke-test-web-secure-jdbc/build.gradle applies
    id 'org.springframework.boot' with the local project version.

Usage:
  ./run-smoke-test-jfr.sh [options] [-- <application arguments>]

Options:
  -o, --output FILE   JFR output file (default: recording-<timestamp>.jfr)
  -n, --no-publish    Skip publishing the local plugin and its modules
  -b, --no-build      Skip the clean + bootJar step
  -h, --help          Show this help

Environment:
  JAVA                Java executable (default: java)
  JAVA_OPTS           Extra JVM options (space separated)
  JFR_SETTINGS        JFR settings name (default: profile)
  VERIFY_JAR          Set to 0 to skip the loader/jar sanity checks

Examples:
  ./run-smoke-test-jfr.sh
  ./run-smoke-test-jfr.sh -o recording.jfr
  ./run-smoke-test-jfr.sh --no-publish -- --server.port=8097

The JFR recording is written when the app exits. Press Ctrl+C to stop the
app and flush the recording (SIGKILL will not produce a recording).

For a JDK 25 AOT-cache run (much faster startup), run the extracted form:
  java -Djarmode=tools -jar <jar> extract --destination /tmp/aotapp
  cd /tmp/aotapp
  java -XX:AOTCacheOutput=/tmp/app.aot -Dspring.context.exit=onRefresh -jar <jar>
  java -XX:AOTCache=/tmp/app.aot -jar <jar>
EOF
}

die() {
	echo "ERROR: $*" >&2
	exit 1
}

while [[ $# -gt 0 ]]; do
	case "$1" in
		-o | --output)
			[[ $# -ge 2 ]] || die "missing value for $1"
			OUT="$2"
			shift 2
			;;
		--output=*)
			OUT="${1#*=}"
			shift
			;;
		-n | --no-publish)
			PUBLISH=0
			shift
			;;
		-b | --no-build)
			BUILD=0
			shift
			;;
		-h | --help)
			usage
			exit 0
			;;
		--)
			shift
			APP_ARGS=("$@")
			break
			;;
		*)
			die "unknown option '$1' (try --help)"
			;;
	esac
done

if [[ -z "$OUT" ]]; then
	OUT="$ROOT/recording-$(date +%Y%m%d-%H%M%S).jfr"
elif [[ "$OUT" != /* ]]; then
	OUT="$ROOT/$OUT"
fi
mkdir -p "$(dirname "$OUT")"

# ---------------------------------------------------------------------------
# Prerequisite checks: catch the "released plugin/old loader" mistake early.
# ---------------------------------------------------------------------------
SMOKE_BUILD="$MODULE_DIR/build.gradle"
grep -q "mavenLocal()" "$ROOT/settings.gradle" \
	|| echo "WARNING: mavenLocal() not found in settings.gradle pluginManagement; the local plugin may not resolve" >&2

PLUGIN_VERSION="$(sed -n "s/.*id ['\"]org.springframework.boot['\"] version ['\"]\([^'\"]*\)['\"].*/\1/p" "$SMOKE_BUILD" | head -1)"
if [[ -z "$PLUGIN_VERSION" ]]; then
	die "$SMOKE_BUILD does not apply the Boot Gradle plugin; add: id 'org.springframework.boot' version '$PROJECT_VERSION'"
fi
if [[ "$PLUGIN_VERSION" != "$PROJECT_VERSION" ]]; then
	die "$SMOKE_BUILD uses Boot plugin $PLUGIN_VERSION but the local project version is $PROJECT_VERSION; use '$PROJECT_VERSION' so the local loader is packaged"
fi

JAVA_CMD="${JAVA:-java}"
JAVA_VERSION="$("$JAVA_CMD" -version 2>&1 | head -1)"
echo "==> Java: $JAVA_VERSION"

# ---------------------------------------------------------------------------
# Step 1: publish the local plugin + its project dependencies
# ---------------------------------------------------------------------------
if [[ "$PUBLISH" == 1 ]]; then
	echo "==> Publishing the local Gradle plugin and loader to Maven local"
	"$ROOT/gradlew" --console=plain -x test \
		:buildpack:spring-boot-buildpack-platform:publishToMavenLocal \
		:loader:spring-boot-loader-tools:publishToMavenLocal \
		:build-plugin:spring-boot-gradle-plugin:publishToMavenLocal
else
	echo "==> Skipping publish (--no-publish)"
fi

# ---------------------------------------------------------------------------
# Step 2: build the executable jar
# ---------------------------------------------------------------------------
if [[ "$BUILD" == 1 ]]; then
	echo "==> Building the executable jar"
	"$ROOT/gradlew" --console=plain \
		"$MODULE:clean" \
		"$MODULE:bootJar"
else
	echo "==> Skipping build (--no-build)"
fi

[[ -f "$JAR" ]] || die "executable jar not found: $JAR (run without --no-build)"

if [[ "$VERIFY_JAR" != 0 ]]; then
	MANIFEST_VERSION="$(unzip -p "$JAR" META-INF/MANIFEST.MF | tr -d '\r' | sed -n 's/^Spring-Boot-Version: //p')"
	echo "==> Jar: $JAR"
	echo "    Spring-Boot-Version: ${MANIFEST_VERSION:-<missing>}"
	if [[ "$MANIFEST_VERSION" != "$PROJECT_VERSION" ]]; then
		die "the jar was built with Spring Boot ${MANIFEST_VERSION:-<missing>} but the local version is $PROJECT_VERSION; rebuild so the local loader is packaged"
	fi
	# Capture first: grep -q in a pipeline can trip pipefail via SIGPIPE.
	JAR_ENTRIES="$(unzip -l "$JAR")"
	if ! grep -Fq 'JarUrlClassLoader$NestedOnlyOptimizedLoader.class' <<<"$JAR_ENTRIES"; then
		die "the jar does not contain the local loader (NestedOnlyOptimizedLoader). This is the old-loader situation; set VERIFY_JAR=0 only if intentional."
	fi
	echo "    local loader: present"
fi

# ---------------------------------------------------------------------------
# Step 3: run with JFR
# ---------------------------------------------------------------------------
JFR_ARGS=(
	"-XX:+UnlockDiagnosticVMOptions"
	"-XX:+DebugNonSafepoints"
	"-XX:StartFlightRecording=filename=$OUT,settings=${JFR_SETTINGS:-profile},dumponexit=true"
	"-Xlog:jfr+startup=error"
)
JAVA_EXTRA=()
if [[ -n "${JAVA_OPTS:-}" ]]; then
	# shellcheck disable=SC2206
	JAVA_EXTRA=(${JAVA_OPTS})
fi

echo "==> Starting app; recording will be written to:"
echo "    $OUT"
echo "    Press Ctrl+C to stop the app and flush the recording."

RUN_ARGS=("$JAVA_CMD" "${JFR_ARGS[@]}")
if [[ ${#JAVA_EXTRA[@]} -gt 0 ]]; then
	RUN_ARGS+=("${JAVA_EXTRA[@]}")
fi
RUN_ARGS+=(-jar "$JAR")
if [[ ${#APP_ARGS[@]} -gt 0 ]]; then
	RUN_ARGS+=("${APP_ARGS[@]}")
fi

set +e
"${RUN_ARGS[@]}"
STATUS=$?
set -e

if [[ -s "$OUT" ]]; then
	echo "==> JFR written: $OUT ($(du -h "$OUT" | cut -f1))"
else
	echo "WARNING: no JFR recording was written to $OUT" >&2
fi
exit "$STATUS"
