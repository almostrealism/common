#!/usr/bin/env bash
# check-direct-logging.sh
#
# Enforces that test source files do not use System.out, System.err, or
# e.printStackTrace() to bypass the project's Console / ConsoleFeatures
# logging infrastructure.
#
# Main sources are covered by the DirectSystemOut Checkstyle rule in
# checkstyle.xml.  Test sources are excluded from the standard Checkstyle run
# (includeTestSourceDirectory=false avoids requiring Javadoc on tests), so
# this script provides equivalent coverage for the logging prohibition.
#
# Correct alternatives:
#   System.out.println(x)  →  log(x)  (implement ConsoleFeatures)
#   System.err.println(x)  →  warn(x) or Console.root().alert(msg, e)
#   e.printStackTrace()    →  Console.root().alert("message", e)
#
# Suppressions: test classes that were already violating when the rule was
# introduced are listed in EXEMPT_TEST_CLASSES below.  Remove a class from
# the list when its file is migrated to Console / ConsoleFeatures.
# DO NOT add new entries.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ---------------------------------------------------------------------------
# Baseline exemptions — test classes violating the rule at time of introduction.
# Remove entries as files are migrated.  Never add new ones.
# ---------------------------------------------------------------------------
EXEMPT_TEST_CLASSES=(
AbsoluteExpressionTest
AcceleratedComputationEvaluableTests
AcceleratedConditionalStatementTests
AcceleratedConjunctionTests
AcceleratedTimeSeriesOperationsTest
AdjustableDelayCellTest
AttentionTests
AudioCellTest
AudioLibraryStartupTest
AudioSceneOptimizerTest
AudioScenePopulationTest
AudioSceneRealTimeTest
BackPropagationTests
BasicIntersectionTest
BufferedAudioPlayerTest
ByteLevelEncoderDebugTest
CausalMaskIsolationTest
ChordProgressionManagerTest
CodeFeaturesTests
CodePolicyEnforcementTest
CollectionComputationTests
CollectionEnumerateTests
CollectionKernelTests
CollectionMathTests
CollectionPadTests
ConditionalTest
Conv1dCorrectnessTest
Conv1dLayerTests
ConvolutionModelTrainingTest
DefaultChannelSectionTest
DefaultProducer
DelayCellTest
DiffusionTransformerComparisonTests
EmbeddedCollectionMapTests
ExpressionDelegationTest
ExpressionSimplificationTests
GrainTest
GranularSynthesizer
InterpolateTest
IsolationTargetTest
KernelAssertions
KernelOperationTests
KernelSeriesTests
LargeOutputDiagnosticTest
LayersTests
Llama2InferenceTest
LoopedSumDiagnosticTest
LoopedSumPerformanceTest
ManualPlaybackTest
MatrixDeltaComputationTests
MatrixMathTests
MemoryAllocationTest
MeshIntersectionTest
MetalJNI
MidiSynthesizerManualTest
MinMaxTests
MixerTests
MyNativeEnabledApplication
NodeGroupTaskRemovalTest
OnnxPrototypeDiscoveryTest
OobleckLayerValidationTest
OobleckValidationTest
OperationOptimizationTests
OperationSemaphoreTests
PackedCollectionMapTests
PackedCollectionRepeatTests
PackedCollectionSubsetTests
PassThroughProducerCompactionTest
PatternFactoryTest
PlaneTest
PrototypeDiscoveryPersistenceTest
PrototypeDiscoveryTest
Qwen3EmbeddingTest
Qwen3GenerationDemo
Qwen3SyntheticTest
Qwen3TokenizerTest
RankedChoiceEvaluableTest
RayBatchTest
RayMarchingTest
RayTest
ReproduceRefreshBug
ResidualBlockSubComponentTest
RotationTests
SequenceTest
ServerTest
SimpleRenderTest
SineWaveCellTest
SoftmaxTests
SourceDataOutputLineTest
SpatialDrawingTest
SphereTest
StableDurationHealthComputationTest
StandardMathTests
SwitchTest
TestJobFactory
TraversableDeltaComputationTests
TriangleTest
UrlProfilingJob
VectorConcatTest
VectorMathTest
WaveCellTest
WeightedSumIsolationRuntimeTest
WeightedSumIsolationThresholdTest
)

# Build a lookup set from the exemption list
declare -A EXEMPT_SET
for cls in "${EXEMPT_TEST_CLASSES[@]}"; do
    EXEMPT_SET["$cls"]=1
done

# ---------------------------------------------------------------------------
# Patterns that indicate direct logging bypass
# ---------------------------------------------------------------------------
PATTERN='System\.(out|err)\.|\.printStackTrace\(\)'

FAILED=0

while IFS= read -r -d '' file; do
    classname=$(basename "$file" .java)
    if [[ -n "${EXEMPT_SET[$classname]+_}" ]]; then
        continue
    fi

    # Grep for violations, ignoring comment-only lines
    matches=$(grep -nE "$PATTERN" "$file" \
        | grep -vE '^\s*[0-9]+:\s*(//|\*)' || true)

    if [[ -n "$matches" ]]; then
        echo "FAIL: $file"
        echo "$matches" | while IFS= read -r line; do
            echo "    $line"
        done
        FAILED=1
    fi
done < <(find "$REPO_ROOT" -path "*/src/test/java/*.java" \
    -not -path "*/target/*" \
    -print0)

if [[ "$FAILED" -ne 0 ]]; then
    echo ""
    echo "Direct System.out/err / printStackTrace() usage found in test sources."
    echo "Implement ConsoleFeatures and use log() / warn(), or use Console.root() directly."
    echo "See base/io/README.md for correct logging patterns."
    exit 1
fi

echo "OK: No direct logging bypass found in test sources."
