#!/bin/bash
#
# Renders the deterministic AudioScene default arrangement through the real-time path using
# AudioScenePopulation.generate, driving the AudioSceneSingleVsMultiChannelTest render harness.
#
# By default this renders ONLY the "multi-with-only-the-selected-channel-audible" case (every
# other channel silenced at the source, full multi-channel mixdown) for the full duration. The
# channel, duration, and which modes to render are all configurable.
#
# Usage:
#   scripts/render-single-vs-multi.sh [CHANNEL]
#
# Arguments:
#   CHANNEL   channel to keep audible (default: 2, or $AR_GENERATE_CHANNEL)
#
# Environment overrides:
#   AR_GENERATE_CHANNEL        channel to keep audible (default: 2; the CHANNEL arg wins)
#   AR_GENERATE_SECONDS        seconds of audio per mode (default: 120)
#   AR_GENERATE_MODES          comma-separated subset of: multi, single, silenced, all
#                              (default: silenced)
#   AR_GENERATE_SEED           fixed genome seed (default: 42)
#   AR_RINGS_LIBRARY           curated sample library root (default: /Users/Shared/Music/Samples).
#                              When this and the pattern factory exist, the render is reproducible
#                              across runs; otherwise a synthetic fallback scene is used and the
#                              note content is only approximately repeatable.
#   AR_RINGS_PATTERNS          curated pattern factory JSON (default: /Users/Shared/Music/pattern-factory.json)
#   AR_HARDWARE_MEMORY_SCALE   GPU/native memory scale exponent (default: 6 => ~16GB)
#   AR_TEST_DEPTH              test depth gate (default: 9, matches CI)
#   AR_PDSL_MIXDOWN            set to "enabled" to render through the PDSL mixdown instead of the
#                              default CellList path
#
# Output WAVs (one per rendered mode) are written to:
#   studio/compose/results/single-vs-multi/{multi_all,single_chN,multi_only_chN}.wav
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$COMPOSE_DIR/../.." && pwd)"

CHANNEL="${1:-${AR_GENERATE_CHANNEL:-2}}"
DURATION="${AR_GENERATE_SECONDS:-120}"
MODES="${AR_GENERATE_MODES:-silenced}"
SEED="${AR_GENERATE_SEED:-42}"
RINGS_LIBRARY="${AR_RINGS_LIBRARY:-/Users/Shared/Music/Samples}"
RINGS_PATTERNS="${AR_RINGS_PATTERNS:-/Users/Shared/Music/pattern-factory.json}"
MEMORY_SCALE="${AR_HARDWARE_MEMORY_SCALE:-6}"
DEPTH="${AR_TEST_DEPTH:-9}"

# Forwarded to the forked test JVM. AR_PDSL_MIXDOWN is only added when the caller set it, so the
# default (CellList) path is used otherwise.
ARG_LINE="-DAR_GENERATE_SECONDS=${DURATION} -DAR_GENERATE_CHANNEL=${CHANNEL}"
ARG_LINE="${ARG_LINE} -DAR_GENERATE_MODES=${MODES} -DAR_GENERATE_SEED=${SEED}"
ARG_LINE="${ARG_LINE} -DAR_RINGS_LIBRARY=${RINGS_LIBRARY} -DAR_RINGS_PATTERNS=${RINGS_PATTERNS}"
ARG_LINE="${ARG_LINE} -DAR_HARDWARE_MEMORY_SCALE=${MEMORY_SCALE}"
if [ -n "${AR_PDSL_MIXDOWN:-}" ]; then
    ARG_LINE="${ARG_LINE} -DAR_PDSL_MIXDOWN=${AR_PDSL_MIXDOWN}"
fi

echo "Rendering channel=${CHANNEL} seconds=${DURATION} modes=${MODES} seed=${SEED} memoryScale=${MEMORY_SCALE}"
echo "Library: ${RINGS_LIBRARY}"
echo "Output: ${COMPOSE_DIR}/results/single-vs-multi/"

cd "$REPO_DIR"
exec mvn test -pl studio/compose \
    -Dtest='AudioSceneSingleVsMultiChannelTest#singleVsMultiChannel' \
    -DAR_TEST_DEPTH="${DEPTH}" \
    -DargLine="${ARG_LINE}"
