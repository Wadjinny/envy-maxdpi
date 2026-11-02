#!/usr/bin/env bash
# Builds the desktop test tool: build/envy-maxdpi.jar  (run: ./run.sh <command> ...)
set -euo pipefail
cd "$(dirname "$0")"
TC="$HOME/.local/toolchains"
export JAVA_HOME="$(ls -d "$TC"/jdk-21* | head -1)"
export PATH="$JAVA_HOME/bin:$TC/kotlinc/bin:$PATH"
mkdir -p build
rm -f build/envy-maxdpi.jar  # so a failed compile can't leave a stale jar behind
kotlinc core/src desktop/src -include-runtime -jvm-target 21 -d build/envy-maxdpi.jar 2>&1 | grep -v '^warning: unable to find kotlin-stdlib' || true
test -f build/envy-maxdpi.jar
