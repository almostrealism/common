#!/bin/bash
# Moves the freshly produced WAVs/spectrograms out of `health/` into the
# per-leg directory passed as $1, and clears the `health/` directory so the
# next optimizer run starts blank.
#
# Crucially, this does NOT delete `population.json` — the second leg of the
# A/B uses the same genome that produced the first leg.
#
# Usage:
#   scripts/snapshot-experiment-leg.sh health-reverb-off
set -euo pipefail

if [ $# -ne 1 ]; then
    echo "usage: $0 <leg-dir>" >&2
    exit 1
fi

WORKING_DIR="/Users/Shared/Music"
LEG="$WORKING_DIR/$1"

mkdir -p "$LEG"
find "$LEG" -mindepth 1 -delete

if [ -d "$WORKING_DIR/health" ]; then
    find "$WORKING_DIR/health" -mindepth 1 -maxdepth 1 -exec mv -f {} "$LEG/" \;
fi

echo "snapshot stored in $LEG"
