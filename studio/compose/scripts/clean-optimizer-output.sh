#!/bin/bash
set -euo pipefail

WORKING_DIR="/Users/Shared/Music"

rm -f "$WORKING_DIR/results/logs/audio-scene.out"
rm -f "$WORKING_DIR/population.json"

if [ -d "$WORKING_DIR/health" ]; then
    find "$WORKING_DIR/health" -mindepth 1 -delete
fi
