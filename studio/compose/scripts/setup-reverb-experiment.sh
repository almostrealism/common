#!/bin/bash
# Resets the Music workspace for the reverb on/off A/B comparison.
#
# Removes the population JSON and per-run output directories so that the
# next optimizer launch starts from a fresh genome, then materialises the
# two destination directories (`health-reverb-off` and `health-reverb-on`)
# that hold WAVs from each leg of the experiment.
#
# Usage:
#   scripts/setup-reverb-experiment.sh
set -euo pipefail

WORKING_DIR="/Users/Shared/Music"

# Standard cleanup (logs + population.json + health/)
"$(dirname "$0")/clean-optimizer-output.sh"

# Per-leg destination directories
mkdir -p "$WORKING_DIR/health-reverb-off" "$WORKING_DIR/health-reverb-on"
find "$WORKING_DIR/health-reverb-off" -mindepth 1 -delete
find "$WORKING_DIR/health-reverb-on" -mindepth 1 -delete

echo "experiment workspace ready"
