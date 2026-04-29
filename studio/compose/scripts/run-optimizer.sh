#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
WORKING_DIR="/Users/Shared/Music"

cd "$WORKING_DIR"
mkdir -p results/logs health

CLASSPATH_CACHE="$COMPOSE_DIR/.classpath-cache"

if [ ! -f "$CLASSPATH_CACHE" ] || [ "$COMPOSE_DIR/pom.xml" -nt "$CLASSPATH_CACHE" ]; then
    echo "Resolving classpath..."
    mvn -f "$COMPOSE_DIR/pom.xml" dependency:build-classpath \
        -Dmdep.outputFile="$CLASSPATH_CACHE" -q
fi

CP="$COMPOSE_DIR/target/classes:$(cat "$CLASSPATH_CACHE")"

exec java -cp "$CP" -Xmx2g \
    -DAR_HARDWARE_MEMORY_SCALE=6 \
    -DAR_PROFILE_MULTIPLE_SOURCES=enabled \
    -DAR_HARDWARE_METADATA=disabled \
    -DAR_INSTRUCTION_SET_MONITORING=failed \
    -DAR_PATTERN_WARNINGS=disabled \
    -DAR_RINGS_LIBRARY="/Users/Shared/Music/Samples" \
    org.almostrealism.studio.optimize.AudioSceneOptimizer "$@"
