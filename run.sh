#!/usr/bin/env bash
set -euo pipefail
JAVA_HOME="$(ls -d "$HOME"/.local/toolchains/jdk-21* | head -1)"
exec "$JAVA_HOME/bin/java" -Xmx3g -jar "$(dirname "$0")/build/envy-maxdpi.jar" "$@"
