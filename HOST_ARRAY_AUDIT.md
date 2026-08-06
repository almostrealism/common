# Host-array and loop audit

Every `for`/`while` loop and every `double[]`/`float[]` occurrence in every file changed on
this branch, with the reason it is not expressed with `PackedCollection` /
`CollectionProducer`. Generated from the branch diff against `origin/master` at the commit being merged;
comments and javadoc are excluded.

**971 items across 74 files.**

No entry below appeals to the prohibition on calling `evaluate()`. That prohibition exists
to move work onto the device; using it to justify leaving work on the host inverts it.

## Categories

### Iteration over non-numeric objects — 11

The loop walks identifiers, files, map entries, AST nodes or other objects. PackedCollection holds numbers, not these; there is no element-wise arithmetic here to express.

### Graph construction — 43

The loop runs once while the computation graph is being assembled, adding a node or layer per iteration. It is not arithmetic over data — it is what produces the arithmetic. Expressing it as a producer would mean a producer that builds producers.

### File-format byte handling — 59

The values are bytes or packed words being converted to or from an on-disk representation. They are not resident in device memory at this point; this loop is the boundary that gives them numeric meaning.

### Data entering from outside the system — 6

The values originate outside the framework — a decoded message, a loaded clip, a tokenizer, a caller-supplied array. Something has to receive them; this is that surface.

### Independent reference implementation — 71

This is the oracle a computation is checked against. Expressing it with the same producers it exists to test would let a fault agree with itself, so the check is written independently.

### Sequential dependence between passes — 24

Each iteration consumes state the previous iteration produced (a ring buffer, a decoder step, a graph walk whose next node is chosen by the current score). There is no batch here: the next input is not known until the current output exists.

### Scalar bookkeeping — 91

The loop computes over a handful of scalars — counts, offsets, sizes, shape arithmetic — not over collection elements.

### Static analysis over source text — 26

The loop scans lines of Java source in the policy detectors. Its data is text.

### MIGRATION CANDIDATE — no defense — 640

This performs element-wise numeric work on data that is, or could be, collection-resident. It should be expressed with CollectionProducer. Listed here without a defense because it does not have one.


## Progress

Three of the largest files are done — each now contains no `double[]` or `float[]` at all:

- `MixdownManagerPdslVerificationTest` (was 63)
- `MoonbeamValueDistributionTest` (was 66)
- `MixdownManagerPdslTest` (was 39)

Work that came out of them and is now available to every consumer, rather than being
repeated per file: `FirFilterTestFeatures` gained `differenceEnergy`, `sumChannels` and
`channelEnergy` alongside the existing `energy` and `peakOf`; `VectorFeatures` gained
`oneHot`. `PackedCollection.clone()` already covered snapshotting a model output, and
`MatrixFeatures.identity` already covered the matrix construction two tests had copied.

Two lessons that apply to the rest of the list, both learned by measurement:

- Loop-carried collections must be allocated once and written through with `setFrom`.
  An operand's offset is part of its signature, so a fresh allocation per iteration makes
  every iteration a distinct graph and recompiles the pipeline once per iteration.
- Aggregates reported together should be concatenated into one computation. Six separate
  evaluations compile six kernels per shape.

## Undefended items by file

640 of 971 items have no defense. Ranked by count:
-   34  `engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmarkAdditional.java`
-   34  `engine/utils/src/test/java/org/almostrealism/collect/computations/test/TraversableDeltaComputationTests.java`
-   33  `studio/music/src/main/java/org/almostrealism/music/pattern/BatchedPatternLayerRenderer.java`
-   23  `engine/utils/src/test/java/org/almostrealism/collect/computations/test/CollectionMathTests.java`
-   22  `engine/utils/src/test/java/org/almostrealism/collect/computations/test/TraversableDeltaComputationTests_Polynomial.java`
-   21  `studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamMidiTest.java`
-   19  `studio/compose/src/test/java/org/almostrealism/studio/ml/test/DelayNetworkBehaviorTest.java`
-   15  `studio/compose/src/test/java/org/almostrealism/studio/ml/test/PdslAudioDspTest.java`
-   14  `engine/audio/src/test/java/org/almostrealism/audio/BatchedSssChainTest.java`
-   14  `engine/utils/src/test/java/org/almostrealism/layers/test/Conv1dLayerTests.java`
-   12  `engine/utils/src/test/java/org/almostrealism/collect/computations/test/CollectionComputationTests.java`
-   12  `studio/compose/src/test/java/org/almostrealism/studio/optimize/test/ProjectedGenomeVariationTest.java`
-   11  `engine/utils/src/main/java/org/almostrealism/util/FirFilterTestFeatures.java`
-   11  `studio/compose/src/main/java/org/almostrealism/studio/arrange/MixdownManagerPdslAdapter.java`
-   10  `studio/compose/src/test/java/org/almostrealism/studio/ml/test/DelayRateModulationTest.java`
-    9  `engine/audio/src/main/java/org/almostrealism/audio/filter/DelayNetwork.java`
-    9  `engine/ml/src/main/java/org/almostrealism/persist/index/SimilarityMetric.java`
-    9  `engine/utils/src/test/java/org/almostrealism/algebra/test/DeltaFeaturesTests.java`
-    9  `engine/utils/src/test/java/org/almostrealism/time/test/WindowComputationTest.java`
-    8  `compute/algebra/src/main/java/org/almostrealism/collect/PackedCollection.java`
-    8  `engine/ml/src/test/java/org/almostrealism/ml/midi/test/GruDecoderPdslInferenceTest.java`
-    8  `studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamComponentTest.java`
-    8  `studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamFineTuningTest.java`
-    7  `base/hardware/src/main/java/org/almostrealism/hardware/MemoryData.java`
-    7  `engine/utils/src/test/java/org/almostrealism/collect/computations/test/ClampBroadcastTests.java`
-    7  `engine/utils/src/test/java/org/almostrealism/layers/test/NormTests.java`
-    7  `engine/utils/src/test/java/org/almostrealism/time/test/MelFilterBankTest.java`
-    6  `studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownLayerPerformanceTest.java`
-    5  `base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryDataAdapter.java`
-    5  `engine/audio/src/main/java/org/almostrealism/audio/WaveOutput.java`
-    5  `engine/audio/src/main/java/org/almostrealism/audio/line/BufferOutputLine.java`
-    5  `engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java`
-    5  `engine/utils/src/test/java/org/almostrealism/MyNativeEnabledApplication.java`
-    5  `studio/compose/src/test/java/org/almostrealism/studio/midi/test/MidiTrainingTest.java`
-    4  `engine/ml/src/test/java/org/almostrealism/graph/model/test/SyntheticConvolutionTrainingTest.java`
-    4  `engine/utils/src/test/java/org/almostrealism/time/test/TemporalFeaturesTest.java`
-    4  `studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamInferenceTest.java`
-    3  `engine/audio/src/test/java/org/almostrealism/audio/data/test/FrequencyToAudioConverterTest.java`
-    3  `engine/ml/src/test/java/org/almostrealism/ml/audio/test/DiffusionNoiseSchedulerTests.java`
-    3  `engine/utils/src/test/java/org/almostrealism/hardware/mem/MemoryDataViewWriteTest.java`
-    3  `engine/utils/src/test/java/org/almostrealism/time/computations/test/ConjugateSymmetryTests.java`
-    3  `studio/compose/src/main/java/org/almostrealism/studio/midi/SkyTntMidi.java`
-    2  `compute/time/src/main/java/org/almostrealism/time/TemporalFeatures.java`
-    2  `engine/audio/src/test/java/org/almostrealism/audio/BatchedSssPlaybackTest.java`
-    2  `engine/ml/src/main/java/org/almostrealism/ml/audio/DiffusionSampler.java`
-    2  `engine/ml/src/main/java/org/almostrealism/ml/midi/MidiCompoundToken.java`
-    2  `engine/ml/src/test/java/org/almostrealism/ml/AttentionTests.java`
-    2  `engine/ml/src/test/java/org/almostrealism/ml/audio/BottleneckInterfaceTest.java`
-    2  `engine/utils/src/test/java/org/almostrealism/algebra/computations/test/MatrixDeltaComputationTests.java`
-    2  `engine/utils/src/test/java/org/almostrealism/time/test/STFTComputationTest.java`
-    2  `studio/compose/src/main/java/org/almostrealism/studio/PatternRenderBuffers.java`
-    2  `studio/compose/src/test/java/org/almostrealism/studio/arrange/test/MixdownManagerFilterAutomationTest.java`
-    2  `studio/compose/src/test/java/org/almostrealism/studio/optimize/test/DefaultBreederTest.java`
-    1  `engine/audio/src/main/java/org/almostrealism/audio/data/FrequencyToAudioConverter.java`
-    1  `engine/audio/src/main/java/org/almostrealism/audio/synth/AudioSynthesizer.java`
-    1  `engine/audio/src/test/java/org/almostrealism/audio/BatchedSssFromScalarsTest.java`
-    1  `engine/utils/src/test/java/org/almostrealism/layers/test/Pool2dShapeInvestigationTest.java`
-    1  `studio/compose/src/main/java/org/almostrealism/studio/arrange/DefaultChannelSectionFactory.java`
-    1  `studio/spatial/src/main/java/org/almostrealism/spatial/EditableSpatialWaveDetails.java`

## Items


### base/hardware/src/main/java/org/almostrealism/hardware/MemoryData.java

- `536` (array) — **MIGRATION CANDIDATE — no defense**  
  `default double[] toArray(int offset, int length) {`
- `553` (array) — **MIGRATION CANDIDATE — no defense**  
  `default double[] toArray() {`
- `567` (array) — **MIGRATION CANDIDATE — no defense**  
  `default float[] toFloatArray(int offset, int length) {`
- `579` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < raw.length; i++) {`
- `592` (array) — **MIGRATION CANDIDATE — no defense**  
  `default float[] toFloatArray() {`
- `739` (array) — **MIGRATION CANDIDATE — no defense**  
  `static void setMem(Memory mem, int offset, float[] source, int srcOffset, int length) {`
- `755` (array) — **MIGRATION CANDIDATE — no defense**  
  `static void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {`

### base/hardware/src/main/java/org/almostrealism/hardware/mem/MemoryDataAdapter.java

- `266` (array) — **MIGRATION CANDIDATE — no defense**  
  `MemoryData.setMem(getMem(), getOffset() + offset, new double[] { value }, 0, 1);`
- `279` (array) — **MIGRATION CANDIDATE — no defense**  
  `MemoryData.setMem(getMem(), getOffset() + offset, new float[] { value }, 0, 1);`
- `296` (array) — **MIGRATION CANDIDATE — no defense**  
  `protected void setMem(double[] source) {`
- `317` (array) — **MIGRATION CANDIDATE — no defense**  
  `protected void setMem(int offset, double[] source) {`
- `338` (array) — **MIGRATION CANDIDATE — no defense**  
  `protected void setMem(float[] source) {`
- `399` (loop) — **Iteration over non-numeric objects**  
  `for (Memory cached : versions.values()) {`

### compute/algebra/src/main/java/org/almostrealism/collect/PackedCollection.java

- `490` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = IntStream.range(0, getMemLength()).mapToDouble(i -> value[i % value.length]).toArray();`
- `502` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = IntStream.range(0, getMemLength()).mapToDouble(i -> values.getAsDouble()).toArray();`
- `515` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = new double[getMemLength()];`
- `528` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] in = toArray(0, getMemLength());`
- `529` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = IntStream.range(0, getMemLength()).mapToDouble(i -> f.applyAsDouble(in[i])).toArray();`
- `542` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < pos.length; i++) {`
- `618` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = toArray();`
- `761` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = toArray(0, getMemLength());`

### compute/time/src/main/java/org/almostrealism/time/TemporalFeatures.java

- `493` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dimensions; i++) {`
- `1106` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < numFrames; i++) {`
- `1487` (loop) — **Scalar bookkeeping**  
  `while (power < n) {`

### domain/heredity/src/main/java/org/almostrealism/heredity/ProjectedGenome.java

- `145` (loop) — **Scalar bookkeeping**  
  `for (ProjectedChromosome chromosome : chromosomes) {`
- `155` (loop) — **Scalar bookkeeping**  
  `for (ProjectedChromosome chromosome : chromosomes) {`
- `177` (loop) — **Scalar bookkeeping**  
  `for (ProjectedChromosome chromosome : chromosomes) {`

### engine/audio/src/main/java/org/almostrealism/audio/WavFile.java

- `334` (loop) — **File-format byte handling**  
  `while (true) {`
- `445` (loop) — **File-format byte handling**  
  `for (int b = 0; b < numBytes; b++) val = (val << 8) + (buffer[--pos] & 0xFF);`
- `459` (loop) — **File-format byte handling**  
  `for (int b = 0; b < numBytes; b++) {`
- `473` (loop) — **File-format byte handling**  
  `for (int b = 0; b < bytesPerSample; b++) {`
- `494` (loop) — **File-format byte handling**  
  `for (int b = 0; b < bytesPerSample; b++) {`
- `536` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `539` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `574` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `577` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) sampleBuffer[c][offset] = (int) readSample();`
- `610` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToWrite; f++) {`
- `613` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `648` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToWrite; f++) {`
- `651` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) writeSample(sampleBuffer[c][offset]);`
- `684` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `687` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `722` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `725` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) sampleBuffer[c][offset] = readSample();`
- `758` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToWrite; f++) {`
- `761` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `796` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToWrite; f++) {`
- `799` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) writeSample(sampleBuffer[c][offset]);`
- `816` (array) — **File-format byte handling**  
  `public int readFrames(double[] sampleBuffer, int numFramesToRead) throws IOException {`
- `829` (array) — **File-format byte handling**  
  `public int readFrames(double[] sampleBuffer, int offset, int numFramesToRead) throws IOException {`
- `832` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `835` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `862` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `865` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `891` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `894` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `913` (array) — **File-format byte handling**  
  `public int readFrames(double[][] sampleBuffer, int numFramesToRead) throws IOException {`
- `927` (array) — **File-format byte handling**  
  `public int readFrames(double[][] sampleBuffer, int offset, int numFramesToRead) throws IOException {`
- `930` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToRead; f++) {`
- `933` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `952` (array) — **File-format byte handling**  
  `public int writeFrames(double[] sampleBuffer, int numFramesToWrite) throws IOException {`
- `965` (array) — **File-format byte handling**  
  `public int writeFrames(double[] sampleBuffer, int offset, int numFramesToWrite) throws IOException {`
- `968` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToWrite; f++) {`
- `971` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`
- `989` (array) — **File-format byte handling**  
  `public int writeFrames(double[][] sampleBuffer) throws IOException {`
- `1001` (array) — **File-format byte handling**  
  `public int writeFrames(double[][] sampleBuffer, int numFramesToWrite) throws IOException {`
- `1014` (array) — **File-format byte handling**  
  `public int writeFrames(double[][] sampleBuffer, int offset, int numFramesToWrite) throws IOException {`
- `1017` (loop) — **File-format byte handling**  
  `for (int f = 0; f < numFramesToWrite; f++) {`
- `1020` (loop) — **File-format byte handling**  
  `for (int c = 0; c < numChannels; c++) {`

### engine/audio/src/main/java/org/almostrealism/audio/WaveOutput.java

- `411` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int offset = 0; offset < frames; offset += writeBatchFrames) {`
- `413` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] framesLeft = l.toArray(offset, count);`
- `414` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] framesRight = r == null ? framesLeft : r.toArray(offset, count);`
- `417` (array) — **MIGRATION CANDIDATE — no defense**  
  `wav.writeFrames(new double[][] { framesLeft, framesRight }, count);`
- `456` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < frames; i++) {`

### engine/audio/src/main/java/org/almostrealism/audio/data/FrequencyToAudioConverter.java

- `120` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int frame = 0; frame < freqFrames; frame++) {`

### engine/audio/src/main/java/org/almostrealism/audio/filter/DelayNetwork.java

- `258` (array) — **MIGRATION CANDIDATE — no defense**  
  `public static double[][] transpose(double[][] matrix) {`
- `261` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] transpose = new double[columns][rows];`
- `263` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < rows; i++) {`
- `264` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < columns; j++) {`
- `279` (array) — **MIGRATION CANDIDATE — no defense**  
  `public static double[][] multiplyMatrices(double[][] firstMatrix, double[][] secondMatrix) {`
- `283` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] product = new double[r1][c2];`
- `285` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < r1; i++) {`
- `286` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < c2; j++) {`
- `287` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < c1; k++) {`

### engine/audio/src/main/java/org/almostrealism/audio/line/BufferOutputLine.java

- `99` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < frameCount; i++) {`
- `269` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < framesToCheck; i++) {`
- `289` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < framesToCheck; i++) {`
- `304` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < framesToCheck; i++) {`
- `325` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < framesToCheck; i++) {`

### engine/audio/src/main/java/org/almostrealism/audio/synth/AudioSynthesizer.java

- `203` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < count; i++) {`
- `294` (loop) — **Scalar bookkeeping**  
  `for (CollectionTemporalCellAdapter cell : cells) {`
- `422` (loop) — **Scalar bookkeeping**  
  `for (CollectionTemporalCellAdapter cell : cells) {`
- `465` (loop) — **Scalar bookkeeping**  
  `for (Frequency r : tones.getFrequencies(f)) {`
- `521` (loop) — **Scalar bookkeeping**  
  `for (CollectionTemporalCellAdapter cell : cells) {`
- `576` (loop) — **Scalar bookkeeping**  
  `for (CollectionTemporalCellAdapter cell : cells) {`
- `628` (loop) — **Scalar bookkeeping**  
  `for (CollectionTemporalCellAdapter cell : cells) {`
- `654` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (Frequency f : tones) {`

### engine/audio/src/test/java/org/almostrealism/audio/BatchedEnvelopeTest.java

- `66` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < N; n++) {`
- `104` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < N; n++) {`

### engine/audio/src/test/java/org/almostrealism/audio/BatchedSssChainTest.java

- `72` (array) — **Independent reference implementation**  
  `private final double[][] voiced = new double[N][TARGET_LENGTH];`
- `85` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] ratioValues = new double[LAYERS][N];`
- `88` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < LAYERS; l++) {`
- `92` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < N; n++) {`
- `113` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < N; n++) {`
- `125` (loop) — **Graph construction**  
  `for (int n = 0; n < N; n++) {`
- `127` (loop) — **Graph construction**  
  `for (int l = 0; l < LAYERS; l++) {`
- `145` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < TARGET_LENGTH; i++) {`
- `154` (array) — **MIGRATION CANDIDATE — no defense**  
  `private void assertRmsEquivalent(String label, double[] expected, PackedCollection actual) {`
- `157` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < expected.length; i++) {`
- `184` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] expected = new double[TARGET_LENGTH];`
- `185` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < N; n++) {`
- `186` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < TARGET_LENGTH; i++) {`
- `212` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] destOffsetValues = { 0, 256, 512, 700 };`
- `215` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] expected = new double[windowWidth];`
- `216` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < N; n++) {`
- `218` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < TARGET_LENGTH; k++) {`

### engine/audio/src/test/java/org/almostrealism/audio/BatchedSssFromScalarsTest.java

- `59` (loop) — **Graph construction**  
  `for (int l = 0; l < LAYERS; l++) {`
- `95` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < WINDOW_WIDTH; i++) {`

### engine/audio/src/test/java/org/almostrealism/audio/BatchedSssPlaybackTest.java

- `66` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] ratioValues = new double[LAYERS][N];`
- `68` (loop) — **Independent reference implementation**  
  `for (int l = 0; l < LAYERS; l++) {`
- `73` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < N; n++) {`
- `109` (array) — **Independent reference implementation**  
  `double[] expected = new double[WINDOW_WIDTH];`
- `110` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < N; n++) {`
- `112` (loop) — **Independent reference implementation**  
  `for (int l = 0; l < LAYERS; l++) {`
- `135` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < TARGET_LENGTH; k++) {`
- `145` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < WINDOW_WIDTH; i++) {`

### engine/audio/src/test/java/org/almostrealism/audio/benchmark/PatternRenderingFloorBenchmarkAdditional.java

- `77` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] pitchRatio;`
- `79` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] volAttack;`
- `81` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] volDecay;`
- `83` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] volSustain;`
- `85` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] volRelease;`
- `87` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] filterAttack;`
- `89` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] filterDecay;`
- `91` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] filterSustain;`
- `93` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] filterRelease;`
- `95` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] automationLevel;`
- `97` (array) — **MIGRATION CANDIDATE — no defense**  
  `final double[] tickStartOffset;`
- `171` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];`
- `172` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {`
- `182` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {`
- `199` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();`
- `264` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];`
- `265` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {`
- `278` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {`
- `312` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();`
- `374` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] seqPerNoteMs = new double[NOTES_PER_MEASURE_VALUES.length];`
- `375` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {`
- `386` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int d = 0; d < NOTES_PER_MEASURE_VALUES.length; d++) {`
- `425` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int w = 0; w < WARMUP_RUNS; w++) batched.evaluate();`
- `447` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < batchSize; n++) {`
- `465` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < batchSize; n++) {`
- `498` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < batchSize; n++) {`
- `554` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int s = 0; s < B1_SOURCE_POOL_SIZE; s++) {`
- `563` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int npm : NOTES_PER_MEASURE_VALUES) {`
- `570` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int w = 0; w < WARMUP_RUNS; w++) {`
- `575` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int r = 0; r < TIMED_RUNS; r++) {`
- `589` (loop) — **Scalar bookkeeping**  
  `for (int npm : NOTES_PER_MEASURE_VALUES) {`
- `608` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int w = 0; w < WARMUP_RUNS; w++) {`
- `614` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int r = 0; r < TIMED_RUNS; r++) {`
- `644` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < totalNotes; n++) {`
- `672` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < totalNotes; n++) {`

### engine/audio/src/test/java/org/almostrealism/audio/data/test/FrequencyToAudioConverterTest.java

- `61` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int f = 0; f < FRAMES; f++) {`
- `92` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : values) {`
- `114` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : details.getData().toArray()) {`

### engine/ml/src/main/java/org/almostrealism/ml/AttentionFeatures.java

- `307` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int kv = 0; kv < kvHeads; kv++) {`
- `320` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < headsPerKvGroup; g++) {`
- `466` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int kv = 0; kv < kvHeads; kv++) {`
- `485` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < headsPerKvGroup; g++) {`
- `814` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < numGroups; g++) {`

### engine/ml/src/main/java/org/almostrealism/ml/audio/DiffusionSampler.java

- `196` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] timesteps = strategy.getTimesteps(numSteps, numInferenceSteps);`
- `234` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] timesteps = strategy.getTimesteps(numSteps, numInferenceSteps);`
- `247` (loop) — **Sequential dependence between passes**  
  `for (int step = startStep; step < numInferenceSteps; step++) {`
- `304` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < startStep * latentSize; i++) {`

### engine/ml/src/main/java/org/almostrealism/ml/midi/MidiCompoundToken.java

- `133` (array) — **MIGRATION CANDIDATE — no defense**  
  `public double[] toDoubleArray() {`
- `134` (array) — **MIGRATION CANDIDATE — no defense**  
  `return new double[]{onset, duration, octave, pitchClass, instrument, velocity};`

### engine/ml/src/main/java/org/almostrealism/ml/midi/MoonbeamMidi.java

- `185` (loop) — **Graph construction**  
  `for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {`
- `193` (loop) — **Graph construction**  
  `for (int i = 0; i < config.numLayers; i++) {`
- `347` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`

### engine/ml/src/main/java/org/almostrealism/persist/assets/CollectionEncoder.java

- `175` (loop) — **File-format byte handling**  
  `for (int i = 0; i < data.getData32Count(); i++) {`

### engine/ml/src/main/java/org/almostrealism/persist/index/SimilarityMetric.java

- `52` (array) — **MIGRATION CANDIDATE — no defense**  
  `float similarityCached(double[] a, double[] b);`
- `62` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] normalizeToArray(PackedCollection vector);`
- `91` (array) — **MIGRATION CANDIDATE — no defense**  
  `public float similarityCached(double[] a, double[] b) {`
- `93` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < a.length; i++) {`
- `100` (array) — **MIGRATION CANDIDATE — no defense**  
  `public double[] normalizeToArray(PackedCollection vector) {`
- `101` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = toDoubleArray(vector);`
- `103` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : data) {`
- `109` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < data.length; i++) {`
- `125` (array) — **MIGRATION CANDIDATE — no defense**  
  `static double[] toDoubleArray(PackedCollection collection) {`

### engine/ml/src/test/java/org/almostrealism/graph/model/test/SyntheticConvolutionTrainingTest.java

- `117` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < samplesPerClass; i++) {`
- `137` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < samplesPerClass; i++) {`
- `343` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < output.getMemLength(); i++) {`
- `350` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < output.getMemLength(); i++) {`

### engine/ml/src/test/java/org/almostrealism/ml/AttentionTests.java

- `81` (loop) — **Scalar bookkeeping**  
  `for (int h = 0; h < heads; h++) {`
- `82` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t <= p; t++) {`
- `85` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < headSize; i++) {`
- `140` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int h = 0; h < heads; h++) {`
- `141` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < headSize; i++) {`
- `144` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t <= p; t++) {`
- `215` (loop) — **Scalar bookkeeping**  
  `for (int b = 0; b < batchSize; b++) {`
- `216` (loop) — **Scalar bookkeeping**  
  `for (int s = 0; s < seqLen; s++) {`
- `217` (loop) — **Scalar bookkeeping**  
  `for (int d = 0; d < embedDim; d++) {`

### engine/ml/src/test/java/org/almostrealism/ml/DynamicTanhTest.java

- `138` (loop) — **Scalar bookkeeping**  
  `for (int b = 0; b < batch; b++) {`
- `139` (loop) — **Independent reference implementation**  
  `for (int f = 0; f < features; f++) {`

### engine/ml/src/test/java/org/almostrealism/ml/audio/BottleneckInterfaceTest.java

- `117` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int b = 0; b < batch; b++) {`
- `118` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int c = 0; c < VAE_OUTPUT_DIM; c++) {`
- `119` (loop) — **Scalar bookkeeping**  
  `for (int l = 0; l < length; l++) {`

### engine/ml/src/test/java/org/almostrealism/ml/audio/SoftNormBottleneckTest.java

- `157` (loop) — **Scalar bookkeeping**  
  `for (int b = 0; b < batch; b++) {`
- `158` (loop) — **Scalar bookkeeping**  
  `for (int c = 0; c < dim; c++) {`
- `159` (loop) — **Scalar bookkeeping**  
  `for (int l = 0; l < length; l++) {`

### engine/ml/src/test/java/org/almostrealism/ml/audio/test/DiffusionNoiseSchedulerTests.java

- `44` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] expected = new double[steps];`
- `46` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int t = 0; t < steps; t++) {`
- `55` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int t = 0; t < steps; t++) {`

### engine/ml/src/test/java/org/almostrealism/ml/midi/test/GruDecoderPdslInferenceTest.java

- `97` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < n; l++) {`
- `107` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < numAttrs; i++) vocabSizes[i] = perAttrVocab;`
- `129` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < tokens.length; i++) {`
- `165` (loop) — **Graph construction**  
  `for (int l = 0; l < numLayers; l++) {`
- `191` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < tokens.length; i++) {`
- `226` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] fmeBases = new double[MoonbeamConfig.NUM_ATTRIBUTES];`
- `227` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < fmeBases.length; i++) fmeBases[i] = 1000.0;`
- `228` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] ropeThetas = new double[MoonbeamConfig.NUM_ATTRIBUTES];`
- `229` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < ropeThetas.length; i++) ropeThetas[i] = 10000.0;`

### engine/ml/src/test/java/org/almostrealism/ml/qwen3/Qwen3VocabProjectionTest.java

- `151` (loop) — **Graph construction**  
  `for (int vocabSize : vocabSizes) {`

### engine/utils/src/main/java/org/almostrealism/util/FirFilterTestFeatures.java

- `54` (array) — **Independent reference implementation**  
  `double[] coefficients = new double[filterOrder + 1];`
- `57` (loop) — **Independent reference implementation**  
  `for (int i = 0; i <= filterOrder; i++) {`
- `79` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < length; i++) {`
- `99` (array) — **Independent reference implementation**  
  `double[] in = signal.toArray(0, length);`
- `100` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] taps = coefficients.toArray(0, order + 1);`
- `101` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] output = new double[length];`
- `103` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < length; n++) {`
- `105` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k <= order; k++) {`
- `125` (array) — **MIGRATION CANDIDATE — no defense**  
  `default double energy(double[] signal, int skip) {`
- `127` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = skip; i < signal.length - skip; i++) {`
- `139` (array) — **MIGRATION CANDIDATE — no defense**  
  `default double peakOf(double[] samples) {`
- `141` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : samples) {`
- `154` (array) — **MIGRATION CANDIDATE — no defense**  
  `default double[] floatToDouble(float[] input) {`
- `155` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] output = new double[input.length];`
- `156` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < input.length; i++) {`

### engine/utils/src/main/java/org/almostrealism/util/PackedCollectionDetector.java

- `161` (loop) — **Static analysis over source text**  
  `for (int i = 0; i < lines.size(); i++) {`
- `195` (loop) — **Static analysis over source text**  
  `for (int i = 0; i < lines.size(); i++) {`
- `272` (loop) — **Static analysis over source text**  
  `while (toArraySetMem.find()) {`
- `303` (loop) — **Static analysis over source text**  
  `for (String domain : LEGITIMATE_CPU_DOMAINS) {`

### engine/utils/src/main/java/org/almostrealism/util/SetMemLiteralsDetector.java

- `97` (array) — **Static analysis over source text**  
  `+ "or a producer assignment. A host double[]/float[] must never be uploaded via setMem.";`
- `107` (array) — **Static analysis over source text**  
  `+ "computed values in a double[] and shipping them in one transfer is the same "`
- `322` (loop) — **Static analysis over source text**  
  `for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {`
- `345` (loop) — **Static analysis over source text**  
  `for (Map.Entry<String, Integer> entry : baseline.entrySet()) {`
- `418` (loop) — **Static analysis over source text**  
  `for (Violation v : detector.getViolations()) {`
- `425` (loop) — **Static analysis over source text**  
  `for (Map.Entry<String, Integer> entry : counts.entrySet()) {`
- `457` (loop) — **Static analysis over source text**  
  `while (m.find()) {`
- `494` (loop) — **Static analysis over source text**  
  `for (String arg : args) {`
- `520` (loop) — **Static analysis over source text**  
  `for (String fragment : SANCTIONED_WRITE_SURFACE) {`
- `537` (loop) — **Static analysis over source text**  
  `for (String[] entry : KNOWN_EXCLUSIONS) {`
- `565` (loop) — **Static analysis over source text**  
  `for (Map.Entry<String, Integer> entry : baseline.entrySet()) {`
- `623` (loop) — **Static analysis over source text**  
  `for (String arg : args) {`
- `643` (loop) — **Static analysis over source text**  
  `for (int i = 1; i < args.size(); i++) {`
- `679` (loop) — **Static analysis over source text**  
  `for (String element : splitTopLevel(body)) {`
- `714` (loop) — **Static analysis over source text**  
  `for (int i = arg.indexOf('('); i >= 0; i = arg.indexOf('(', i + 1)) {`
- `716` (loop) — **Static analysis over source text**  
  `while (p >= 0 && arg.charAt(p) == ' ') p--;`
- `759` (loop) — **Static analysis over source text**  
  `for (int i = start; i < text.length(); i++) {`
- `781` (loop) — **Static analysis over source text**  
  `for (int i = 0; i < argString.length(); i++) {`
- `820` (loop) — **Static analysis over source text**  
  `while (i < n) {`
- `823` (loop) — **Static analysis over source text**  
  `while (i < n && text.charAt(i) != '\n') out[i++] = ' ';`
- `827` (loop) — **Static analysis over source text**  
  `while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {`
- `836` (loop) — **Static analysis over source text**  
  `while (i < n && text.charAt(i) != quote) {`

### engine/utils/src/test/java/io/almostrealism/compute/test/ReplicationMismatchOptimizationTest.java

- `79` (loop) — **Scalar bookkeeping**  
  `for (Process<?, ?> child : children) {`
- `125` (loop) — **Scalar bookkeeping**  
  `for (Process<?, ?> child : children) {`

### engine/utils/src/test/java/org/almostrealism/MyNativeEnabledApplication.java

- `124` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 3; i++) {`
- `271` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < w; i++) {`
- `272` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < h; j++) {`
- `273` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < d; k++) {`
- `368` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`

### engine/utils/src/test/java/org/almostrealism/algebra/computations/test/MatrixDeltaComputationTests.java

- `210` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < rows; i++) {`
- `211` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < rows; j++) {`
- `212` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < cols; k++) {`
- `243` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < nodes; i++) {`
- `244` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < nodes; j++) {`
- `245` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < size; k++) {`

### engine/utils/src/test/java/org/almostrealism/algebra/test/DeltaFeaturesTests.java

- `63` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `64` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `65` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`
- `101` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `102` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `103` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < dim; k++) {`
- `140` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `141` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`
- `175` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `176` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`

### engine/utils/src/test/java/org/almostrealism/collect/computations/test/ClampBroadcastTests.java

- `51` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIZE; i++) {`
- `63` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIZE; i++) {`
- `75` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIZE; i++) {`
- `87` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIZE; i++) {`
- `124` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int r = 0; r < rows; r++) {`
- `125` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < cols; i++) {`
- `148` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIZE; i++) {`

### engine/utils/src/test/java/org/almostrealism/collect/computations/test/CollectionComputationTests.java

- `168` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `169` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < size; j++) {`
- `237` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `238` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < size; j++) {`
- `290` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 3; i++) {`
- `291` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 5; j++) {`
- `324` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < n; i++) {`
- `325` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `360` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < n; i++) {`
- `361` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `390` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < n; i++) {`
- `392` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < dim; j++) {`
- `640` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < shape.getSize(); i++) {`

### engine/utils/src/test/java/org/almostrealism/collect/computations/test/CollectionMathTests.java

- `44` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 10; i++) {`
- `67` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 10; i++) {`
- `86` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 10; i++) {`
- `104` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 2; i++) {`
- `105` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 5; j++) {`
- `125` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < r; i++) {`
- `126` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < c; j++) {`
- `152` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < r; i++) {`
- `153` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < c; j++) {`
- `173` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < size; j++) {`
- `211` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] in = products.evaluate().toArray();`
- `212` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] ad = sqrt(products).evaluate().toArray();`
- `213` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bd = sqrt(c(1.0).subtract(products)).evaluate().toArray();`
- `218` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < in.length; i++) {`
- `246` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < size; j++) {`
- `277` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < size; j++) {`
- `313` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < size; j++) {`
- `320` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < size; j++) {`
- `342` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `343` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < g; j++) {`
- `345` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `370` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `371` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < g; j++) {`
- `373` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `379` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `403` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `404` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < g; j++) {`
- `406` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `412` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `452` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `453` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < g; j++) {`
- `455` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `462` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `500` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < n; i++) {`
- `501` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < g; j++) {`
- `503` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `510` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `517` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `543` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < size; i++) {`

### engine/utils/src/test/java/org/almostrealism/collect/computations/test/TraversableDeltaComputationTests.java

- `152` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < 2; i++) {`
- `153` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < 2; j++) {`
- `301` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < 2; i++) {`
- `302` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < 2; j++) {`
- `336` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < 2; i++) {`
- `337` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < 2; j++) {`
- `407` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 6; i++) {`
- `593` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `644` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `700` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < c; i++) {`
- `729` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `730` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 4; j++) {`
- `764` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `765` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0 ; j < dim; j++) {`
- `766` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`
- `798` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `870` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < (dim * dim); i++) {`
- `871` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < (dim * dim); j++) {`
- `897` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `898` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `925` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `926` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `953` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `954` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `982` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < dim; n++) {`
- `1006` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < 4; n++) {`
- `1009` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 4; j++) {`
- `1039` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < 4; n++) {`
- `1042` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 4; j++) {`
- `1079` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `1083` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `1084` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 4; j++) {`
- `1129` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `1133` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `1134` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 4; j++) {`
- `1187` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `1188` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `1312` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 3; i++) {`
- `1333` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 3; i++) {`
- `1334` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < 3; j++) {`

### engine/utils/src/test/java/org/almostrealism/collect/computations/test/TraversableDeltaComputationTests_Polynomial.java

- `48` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 5; i++) {`
- `64` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 5; i++) {`
- `72` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 5; i++) {`
- `98` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] d = dout.toArray(0, count * dim * dim);`
- `101` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `102` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0 ; j < dim; j++) {`
- `103` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`
- `131` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] l = out.toArray(0, count * dim);`
- `138` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] d = dout.toArray(0, count * dim * dim);`
- `141` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < count; i++) {`
- `142` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0 ; j < dim; j++) {`
- `143` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`
- `177` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `178` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `179` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < dim; k++) {`
- `212` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `213` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `214` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < dim; k++) {`
- `248` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `249` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < dim; k++) {`
- `277` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `278` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dim; j++) {`
- `309` (loop) — **Scalar bookkeeping**  
  `for (int n = 0; n < in.getCount(); n++) {`
- `310` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < dim; i++) {`
- `311` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < dim; j++) {`
- `345` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < in.getCount(); n++) {`
- `346` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`

### engine/utils/src/test/java/org/almostrealism/hardware/mem/MemoryDataViewWriteTest.java

- `46` (array) — **MIGRATION CANDIDATE — no defense**  
  `root.setMem(new double[] { 1.0, 2.0, 3.0 });`
- `64` (array) — **MIGRATION CANDIDATE — no defense**  
  `view.setMem(new double[] { 5.0, 6.0 });`
- `95` (array) — **MIGRATION CANDIDATE — no defense**  
  `view.setMem(new double[] { 1.0, 2.0, 3.0 });`

### engine/utils/src/test/java/org/almostrealism/layers/test/Conv1dLayerTests.java

- `64` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] inputArray = input.toArray(0, size);`
- `65` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] expectedOutput = new double[size];`
- `67` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size; i++) {`
- `74` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] actualArray = actualOutput.toArray(0, size);`
- `75` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size; i++) {`
- `104` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] inputArray = input.toArray(0, size);`
- `105` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] actualArray = actualOutput.toArray(0, size);`
- `107` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size; i++) {`
- `160` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] actualArray = actualOutput.toArray(0, batchSize * outputChannels * seqLength);`
- `162` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < actualArray.length; i++) {`
- `379` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] actualArray = actualOutput.toArray(0, batchSize * outputChannels * seqLength);`
- `381` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < actualArray.length; i++) {`
- `515` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] actual = actualOutput.toArray(0, batchSize * outputChannels * outLength);`
- `517` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < actual.length; i++) {`
- `523` (loop) — **Scalar bookkeeping**  
  `for (double v : actual) {`

### engine/utils/src/test/java/org/almostrealism/layers/test/NormTests.java

- `57` (array) — **MIGRATION CANDIDATE — no defense**  
  `protected static double[] values = {0.5, 1.5, 2.0};`
- `104` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < groups; i++) {`
- `107` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < groupSize; j++) {`
- `108` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `116` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < groupSize; j++) {`
- `117` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `125` (loop) — **Scalar bookkeeping**  
  `for (int j = 0; j < groupSize; j++) {`
- `126` (loop) — **Scalar bookkeeping**  
  `for (int k = 0; k < v; k++) {`
- `308` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < groups; g++) {`
- `330` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < groupSize; i++) {`
- `373` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < 3; i++) {`
- `451` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < n; i++) {`
- `501` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < groups; g++) {`
- `511` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < groupSize; i++) {`
- `617` (loop) — **Scalar bookkeeping**  
  `while (c < 1400) {`
- `736` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < groups; g++) {`
- `757` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < groupSize; i++) {`

### engine/utils/src/test/java/org/almostrealism/layers/test/Pool2dShapeInvestigationTest.java

- `186` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int[] tc : testCases) {`

### engine/utils/src/test/java/org/almostrealism/time/computations/test/ConjugateSymmetryTests.java

- `69` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < expected.length; i++) {`
- `93` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int f = 0; f < 3; f++) {`
- `132` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 2 * bins; i++) {`

### engine/utils/src/test/java/org/almostrealism/time/test/MelFilterBankTest.java

- `46` (loop) — **Independent reference implementation**  
  `for (int hz = 100; hz <= 8000; hz += 100) {`
- `59` (loop) — **Scalar bookkeeping**  
  `for (double hz = 100; hz <= 8000; hz += 100) {`
- `87` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < numMelBands; i++) {`
- `110` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < filterbankMatrix.getShape().getTotalSize(); i++) {`
- `115` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int m = 0; m < numMelBands; m++) {`
- `117` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < numFreqBins; k++) {`
- `168` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < numMelBands; i++) {`
- `202` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < numMfccCoeffs; i++) {`
- `229` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < numMfccCoeffs; i++) {`

### engine/utils/src/test/java/org/almostrealism/time/test/STFTComputationTest.java

- `71` (loop) — **Iteration over non-numeric objects**  
  `for (WindowComputation.Type windowType : WindowComputation.Type.values()) {`
- `118` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < spectrogram.getShape().getTotalSize(); i++) {`
- `146` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int frame = 0; frame < numFrames; frame++) {`

### engine/utils/src/test/java/org/almostrealism/time/test/TemporalFeaturesTest.java

- `41` (array) — **Independent reference implementation**  
  `double[] highPassCoefficients = new double[filterOrder + 1];`
- `42` (loop) — **Independent reference implementation**  
  `for (int i = 0; i <= filterOrder; i++) {`
- `59` (array) — **Independent reference implementation**  
  `double[] result = lowPassCoefficients(c(cutoff), sampleRate, filterOrder).get().evaluate().toArray();`
- `61` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < filterOrder + 1; i++) {`
- `79` (loop) — **Independent reference implementation**  
  `for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {`
- `81` (array) — **Independent reference implementation**  
  `double[] resultCoefficients = result.range(shape(len), c * len).toArray();`
- `83` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < filterOrder + 1; i++) {`
- `102` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {`
- `104` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] resultCoefficients = result.range(shape(len), c * len).toArray();`
- `106` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < filterOrder + 1; i++) {`
- `133` (loop) — **Independent reference implementation**  
  `for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {`
- `135` (array) — **Independent reference implementation**  
  `double[] resultCoefficients = result.range(shape(len), c * len).toArray();`
- `137` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < filterOrder + 1; i++) {`
- `185` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < filterOrder + 1; i++) {`

### engine/utils/src/test/java/org/almostrealism/time/test/WindowComputationTest.java

- `35` (array) — **Independent reference implementation**  
  `protected double[] referenceHann(int size) {`
- `36` (array) — **Independent reference implementation**  
  `double[] window = new double[size];`
- `37` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < size; n++) {`
- `46` (array) — **Independent reference implementation**  
  `protected double[] referenceHamming(int size) {`
- `47` (array) — **Independent reference implementation**  
  `double[] window = new double[size];`
- `48` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < size; n++) {`
- `57` (array) — **Independent reference implementation**  
  `protected double[] referenceBlackman(int size) {`
- `58` (array) — **Independent reference implementation**  
  `double[] window = new double[size];`
- `59` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < size; n++) {`
- `69` (array) — **Independent reference implementation**  
  `protected double[] referenceBartlett(int size) {`
- `70` (array) — **Independent reference implementation**  
  `double[] window = new double[size];`
- `71` (loop) — **Independent reference implementation**  
  `for (int n = 0; n < size; n++) {`
- `80` (array) — **Independent reference implementation**  
  `protected double[] referenceFlattop(int size) {`
- `87` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] window = new double[size];`
- `88` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < size; n++) {`
- `107` (array) — **Independent reference implementation**  
  `double[] expected = referenceHann(size);`
- `110` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `121` (array) — **Independent reference implementation**  
  `double[] expected = referenceHann(size);`
- `124` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `135` (array) — **Independent reference implementation**  
  `double[] expected = referenceHann(size);`
- `138` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `161` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size / 2; i++) {`
- `174` (array) — **Independent reference implementation**  
  `double[] expected = referenceHamming(size);`
- `177` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `188` (array) — **Independent reference implementation**  
  `double[] expected = referenceHamming(size);`
- `191` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `213` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size / 2; i++) {`
- `226` (array) — **Independent reference implementation**  
  `double[] expected = referenceBlackman(size);`
- `229` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `240` (array) — **Independent reference implementation**  
  `double[] expected = referenceBlackman(size);`
- `243` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `265` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size / 2; i++) {`
- `278` (array) — **Independent reference implementation**  
  `double[] expected = referenceBartlett(size);`
- `281` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `292` (array) — **Independent reference implementation**  
  `double[] expected = referenceBartlett(size);`
- `295` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `317` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size / 2; i++) {`
- `324` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i <= size / 2; i++) {`
- `339` (array) — **Independent reference implementation**  
  `double[] expected = referenceFlattop(size);`
- `342` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `353` (array) — **Independent reference implementation**  
  `double[] expected = referenceFlattop(size);`
- `356` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`
- `370` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size / 2; i++) {`
- `393` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size; i++) {`
- `415` (array) — **Independent reference implementation**  
  `double[] expected = referenceHann(size);`
- `416` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < size; i++) {`

### studio/compose/src/main/java/org/almostrealism/studio/AudioScene.java

- `1265` (loop) — **Scalar bookkeeping**  
  `for (int b = 0; b < bufferCount; b++) {`
- `1365` (loop) — **Scalar bookkeeping**  
  `for (AudioScene<?> scene : scenes) {`

### studio/compose/src/main/java/org/almostrealism/studio/PatternRenderBuffers.java

- `132` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < idx.length; i++) {`
- `139` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < idx.length; i++) {`

### studio/compose/src/main/java/org/almostrealism/studio/arrange/DefaultChannelSectionFactory.java

- `64` (array) — **MIGRATION CANDIDATE — no defense**  
  `public static final double[] repeatChoices = new double[] { 8, 16, 32 };`

### studio/compose/src/main/java/org/almostrealism/studio/arrange/MixdownManagerPdslAdapter.java

- `379` (loop) — **Iteration over non-numeric objects**  
  `for (String key : STATE_SLOTS) {`
- `547` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < config.channels; ch++) {`
- `568` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < config.channels; ch++) {`
- `579` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < config.channels; ch++) {`
- `591` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < config.channels; ch++) {`
- `620` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < layers; j++) {`
- `1045` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < config.channels; ch++) {`
- `1133` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < channels; ch++) {`
- `1246` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < config.channels; ch++) {`
- `1342` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < count; n++) {`
- `1343` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int m = 0; m < count; m++) {`
- `1473` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : evaluated.toArray(0, count)) {`

### studio/compose/src/main/java/org/almostrealism/studio/midi/SkyTntMidi.java

- `331` (loop) — **Iteration over non-numeric objects**  
  `for (int pos = 0; pos < sequence.size(); pos++) {`
- `337` (loop) — **Scalar bookkeeping**  
  `for (int gen = 0; gen < maxNewEvents; gen++) {`
- `348` (loop) — **Sequential dependence between passes**  
  `for (int step = 0; step < config.maxTokenSeq; step++) {`
- `563` (loop) — **Graph construction**  
  `for (int i = 0; i < numLayers; i++) {`
- `633` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 1; j < config.maxTokenSeq; j++) {`
- `686` (loop) — **Data entering from outside the system**  
  `for (int i = 0; i < allowedTrackIds.length; i++) {`
- `712` (loop) — **Scalar bookkeeping**  
  `for (int id : validIds) {`
- `732` (loop) — **Scalar bookkeeping**  
  `for (int id : validIds) {`
- `760` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int id : validIds) {`
- `789` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] sorted = maskedLogits.toArray(0, config.vocabSize);`

### studio/compose/src/test/java/org/almostrealism/studio/arrange/test/MixdownManagerFilterAutomationTest.java

- `323` (loop) — **Scalar bookkeeping**  
  `for (int b = 0; b < batches; b++) batchTick.run();`
- `359` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < AutomationManager.GENE_LENGTH; i++) {`
- `379` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < n; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/midi/test/MidiTrainingTest.java

- `126` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (ValueTarget<PackedCollection> pair : dataset) {`
- `129` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < input.getShape().getTotalSize(); i++) {`
- `148` (loop) — **Graph construction**  
  `for (int s = 0; s < 5; s++) {`
- `150` (loop) — **Graph construction**  
  `for (int n = 0; n < 3; n++) {`
- `160` (loop) — **Iteration over non-numeric objects**  
  `for (List<MidiCompoundToken> seq : sequences) {`
- `164` (loop) — **Iteration over non-numeric objects**  
  `for (List<MidiCompoundToken> seq : packed) {`
- `171` (loop) — **Iteration over non-numeric objects**  
  `for (List<MidiCompoundToken> seq : packed) {`
- `189` (loop) — **Graph construction**  
  `for (int n = 0; n < 20; n++) {`
- `417` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < minLen; i++) {`
- `450` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int trial = 0; trial < 100; trial++) {`
- `483` (loop) — **Iteration over non-numeric objects**  
  `for (int i = 0; i < output1.size(); i++) {`
- `513` (loop) — **Iteration over non-numeric objects**  
  `for (int i = 0; i < output1.size(); i++) {`
- `606` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (ValueTarget<PackedCollection> ignored : dataset) {`
- `637` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < config.numLayers; i++) {`
- `675` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`
- `708` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`
- `752` (loop) — **Graph construction**  
  `for (int f = 0; f < 3; f++) {`
- `754` (loop) — **Graph construction**  
  `for (int n = 0; n < 4; n++) {`

### studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamComponentTest.java

- `92` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dim; i++) {`
- `127` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < REAL_CONFIG.hiddenSize; i++) {`
- `154` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int g = 0; g < REAL_CONFIG.ropeThetas.length; g++) {`
- `205` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < tokens.length; i++) {`
- `244` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < tokens.length; i++) {`
- `365` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < prompt.length; i++) {`
- `485` (array) — **MIGRATION CANDIDATE — no defense**  
  `new double[]{199999, 1031, 19, 20, 199999, 131},`
- `488` (array) — **MIGRATION CANDIDATE — no defense**  
  `new double[]{199999, 1031, 19, 20, 199999, 131},`
- `503` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < config.numLayers; i++) {`
- `543` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`

### studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamFineTuningTest.java

- `172` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (ValueTarget<PackedCollection> pair : dataset) {`
- `183` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < config.decodeVocabSize; i++) {`
- `211` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (ValueTarget<PackedCollection> pair : dataset) {`
- `253` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 10; i++) {`
- `333` (array) — **MIGRATION CANDIDATE — no defense**  
  `log("Limitation: GRUDecoder.linearForwardCached() uses cached double[] arrays");`
- `382` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (ValueTarget<PackedCollection> ignored : dataset) {`
- `461` (loop) — **Graph construction**  
  `for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {`
- `467` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < MoonbeamConfig.NUM_ATTRIBUTES; i++) {`
- `475` (loop) — **Graph construction**  
  `for (int i = 0; i < config.numLayers; i++) {`
- `511` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < config.numLayers; i++) {`
- `549` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`

### studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamInferenceTest.java

- `87` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < config.hiddenSize; i++) {`
- `120` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < decodeTokens.length; i++) {`
- `130` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < decodeTokens.length; i++) {`
- `174` (loop) — **Iteration over non-numeric objects**  
  `for (int i = 0; i < generated.size(); i++) {`
- `184` (loop) — **Data entering from outside the system**  
  `for (MidiCompoundToken token : generated) {`
- `194` (loop) — **Data entering from outside the system**  
  `for (MidiNoteEvent event : events) {`
- `213` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`
- `235` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < size; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamMidiTest.java

- `128` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < prompt.length; i++) {`
- `295` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < config.numLayers; i++) {`
- `336` (loop) — **Graph construction**  
  `for (int l = 0; l < n; l++) {`
- `451` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `459` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `498` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] xArr = x.toArray(0, inputSize);`
- `499` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] hArr = h.toArray(0, dh);`
- `500` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wIr = weightIh.toArray(0, dh * inputSize);`
- `501` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bIr = biasIh.toArray(0, dh);`
- `502` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wHr = weightHh.toArray(0, dh * dh);`
- `503` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bHr = biasHh.toArray(0, dh);`
- `504` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wIz = weightIh.toArray(dh * inputSize, dh * inputSize);`
- `505` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bIz = biasIh.toArray(dh, dh);`
- `506` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wHz = weightHh.toArray(dh * dh, dh * dh);`
- `507` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bHz = biasHh.toArray(dh, dh);`
- `508` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wIn = weightIh.toArray(2 * dh * inputSize, dh * inputSize);`
- `509` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bIn = biasIh.toArray(2 * dh, dh);`
- `510` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wHn = weightHh.toArray(2 * dh * dh, dh * dh);`
- `511` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bHn = biasHh.toArray(2 * dh, dh);`
- `512` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] hNew = new double[dh];`
- `513` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dh; i++) {`
- `518` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < inputSize; j++) {`
- `523` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dh; j++) {`

### studio/compose/src/test/java/org/almostrealism/studio/midi/test/MoonbeamValueDistributionTest.java

- `113` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `134` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `147` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `193` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int trial = 0; trial < numTrials; trial++) {`
- `202` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `247` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < numSamples; i++) {`
- `256` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int b = 0; b < 10; b++) {`
- `271` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < numSamples; i++) {`
- `294` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int trial = 0; trial < 10; trial++) {`
- `338` (loop) — **Scalar bookkeeping**  
  `for (int l = 0; l < decoderLayers; l++) {`
- `360` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < tokens.length; i++) {`
- `410` (loop) — **Graph construction**  
  `for (int l = 0; l < nl; l++) {`
- `420` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] inputArr = inputHidden.toArray(0, hidden);`
- `426` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] projArr = projected.toArray(0, decoderHidden);`
- `431` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < nl; l++) {`
- `436` (array) — **Graph construction**  
  `double[] sosArr = sosEmb.toArray(0, decoderHidden);`
- `440` (loop) — **Graph construction**  
  `for (int step = 0; step < GRUDecoder.TOKENS_PER_NOTE; step++) {`
- `442` (loop) — **Graph construction**  
  `for (int l = 0; l < nl; l++) {`
- `447` (array) — **Graph construction**  
  `double[] gruOut = h[nl - 1].toArray(0, decoderHidden);`
- `454` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] logitArr = logits.toArray(0, vocabSize);`
- `536` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `547` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `589` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int trial = 0; trial < numTrials; trial++) {`
- `600` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int trial = 0; trial < numTrials; trial++) {`
- `612` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int trial = 0; trial < numTrials; trial++) {`
- `667` (loop) — **Graph construction**  
  `for (int l = 0; l < nc; l++) {`
- `697` (loop) — **Graph construction**  
  `for (int l = 0; l < nr; l++) {`
- `730` (loop) — **Graph construction**  
  `for (int l = 0; l < na; l++) {`
- `745` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < na; l++) {`
- `751` (loop) — **Graph construction**  
  `for (int step = 0; step < GRUDecoder.TOKENS_PER_NOTE; step++) {`
- `753` (loop) — **Graph construction**  
  `for (int l = 0; l < na; l++) {`
- `762` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] logitArr = logits.toArray(0, vocabSize);`
- `771` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < 5; k++) {`
- `789` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int k = 0; k < 5; k++) {`
- `807` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < GRUDecoder.TOKENS_PER_NOTE; i++) {`
- `901` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] xArr = x.toArray(0, inputSize);`
- `902` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] hArr = h.toArray(0, dh);`
- `904` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wIr = weightIh.toArray(0, dh * inputSize);`
- `905` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bIr = biasIh.toArray(0, dh);`
- `906` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wHr = weightHh.toArray(0, dh * dh);`
- `907` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bHr = biasHh.toArray(0, dh);`
- `909` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wIz = weightIh.toArray(dh * inputSize, dh * inputSize);`
- `910` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bIz = biasIh.toArray(dh, dh);`
- `911` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wHz = weightHh.toArray(dh * dh, dh * dh);`
- `912` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bHz = biasHh.toArray(dh, dh);`
- `914` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wIn = weightIh.toArray(2 * dh * inputSize, dh * inputSize);`
- `915` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bIn = biasIh.toArray(2 * dh, dh);`
- `916` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wHn = weightHh.toArray(2 * dh * dh, dh * dh);`
- `917` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] bHn = biasHh.toArray(2 * dh, dh);`
- `919` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] r = new double[dh];`
- `920` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] z = new double[dh];`
- `921` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] n = new double[dh];`
- `922` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] hNew = new double[dh];`
- `924` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < dh; i++) {`
- `929` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < inputSize; j++) {`
- `934` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < dh; j++) {`
- `965` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] inputArr = input.toArray();`
- `966` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] weightArr = weight.toArray();`
- `967` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] biasArr = bias.toArray();`
- `968` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] resultArr = new double[outputSize];`
- `969` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < outputSize; i++) {`
- `972` (loop) — **File-format byte handling**  
  `for (int j = 0; j < inputSize; j++) {`
- `994` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] data = collection.toArray(0, size);`
- `997` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < size; i++) {`
- `1009` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static int[] topKIndices(double[] arr, int k) {`
- `1011` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] values = new double[k];`
- `1014` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < arr.length; i++) {`
- `1016` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 1; j < k; j++) {`
- `1026` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < k - 1; i++) {`
- `1027` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = i + 1; j < k; j++) {`
- `1038` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static void printStats(String label, double[] arr) {`
- `1046` (loop) — **Scalar bookkeeping**  
  `for (double v : arr) {`
- `1061` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static double arrayMin(double[] arr) {`
- `1063` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < arr.length; i++) if (arr[i] < min) min = arr[i];`
- `1073` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static double arrayMax(double[] arr) {`
- `1075` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < arr.length; i++) if (arr[i] > max) max = arr[i];`
- `1085` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static double arrayMean(double[] arr) {`
- `1087` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : arr) sum += v;`
- `1097` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static double arrayStd(double[] arr) {`
- `1100` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : arr) sumSq += (v - mean) * (v - mean);`

### studio/compose/src/test/java/org/almostrealism/studio/ml/test/DelayNetworkBehaviorTest.java

- `435` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int c = 0; c < channels; c++) {`
- `436` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `493` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `569` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < 8; pass++) {`
- `607` (array) — **Graph construction**  
  `double[] gains = {2.0, 3.0, 5.0};`
- `609` (loop) — **Graph construction**  
  `for (double g : gains) {`
- `622` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = compiled.forward(input).toArray(0, channels * signalSize);`
- `624` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < channels; ch++) {`
- `625` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `650` (array) — **Graph construction**  
  `double[] gains = {2.0, 3.0, 5.0};`
- `652` (loop) — **Graph construction**  
  `for (double g : gains) {`
- `667` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = compiled.forward(input).toArray(0, channels * signalSize);`
- `669` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < channels; ch++) {`
- `670` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `696` (array) — **Graph construction**  
  `double[] gains = {2.0, 3.0, 5.0};`
- `698` (loop) — **Graph construction**  
  `for (double g : gains) {`
- `714` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = compiled.forward(input).toArray(0, channels * signalSize);`
- `716` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < channels; ch++) {`
- `717` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `752` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int passIdx = 0; passIdx < 50; passIdx++) {`
- `789` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < 3; n++) {`
- `801` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int passIdx = 0; passIdx < 20; passIdx++) {`
- `835` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < 8; pass++) {`
- `836` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < idOut.getMemLength(); i++) {`
- `871` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < 6; pass++) {`
- `872` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `904` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 4; i++) {`
- `936` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 1; p < 4; p++) {`
- `940` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < 16; t++) {`

### studio/compose/src/test/java/org/almostrealism/studio/ml/test/DelayRateModulationTest.java

- `95` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] forward(CompiledModel model, int signalSize, int firstIndex) {`
- `132` (loop) — **Graph construction**  
  `for (int pass = 0; pass < 6; pass++) {`
- `142` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = model.forward(input).toArray(0, channels * signalSize);`
- `144` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < channels; ch++) {`
- `145` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `170` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < 6; pass++) {`
- `172` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = forward(model, signalSize, first);`
- `174` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `204` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < warmPasses; pass++) {`
- `205` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = forward(model, signalSize, pass * signalSize);`
- `212` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = forward(model, signalSize, first);`
- `214` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < signalSize; i++) {`
- `227` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 1; i < signalSize; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownLayerPerformanceTest.java

- `107` (loop) — **Scalar bookkeeping**  
  `for (int frames : new int[] { 1, 2, 4 }) {`
- `195` (loop) — **Graph construction**  
  `for (boolean vectorized : new boolean[] { false, true }) {`
- `220` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : square.forward(input).toArray(0, signal)) {`
- `243` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < 4; pass++) {`
- `244` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = compiled.forward(input).toArray(0, signal);`
- `245` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : out) energy += v * v;`
- `306` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < 2; i++) {`
- `310` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] forwardMs = new double[5];`
- `311` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < forwardMs.length; i++) {`
- `344` (loop) — **Scalar bookkeeping**  
  `for (int i = 0; i < forwards; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownManagerPdslTest.java

- `131` (loop) — **Iteration over non-numeric objects**  
  `for (PdslNode.Definition def : defs) {`
- `234` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] sparseOut = compiled.forward(sparse).toArray(0, SIGNAL_SIZE);`
- `260` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = compiled.forward(input).toArray(0, SIGNAL_SIZE);`
- `293` (array) — **MIGRATION CANDIDATE — no defense**  
  `float[] dryMono = new float[totalSamples];`
- `294` (array) — **MIGRATION CANDIDATE — no defense**  
  `float[] masterMono = new float[totalSamples];`
- `299` (loop) — **Independent reference implementation**  
  `for (int pass = 0; pass < numPasses; pass++) {`
- `302` (array) — **Independent reference implementation**  
  `double[] inArr = input.toArray(0, CHANNELS * SIGNAL_SIZE);`
- `303` (array) — **Independent reference implementation**  
  `double[] outArr = compiled.forward(input).toArray(0, SIGNAL_SIZE);`
- `306` (loop) — **Independent reference implementation**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `308` (loop) — **File-format byte handling**  
  `for (int c = 0; c < CHANNELS; c++) {`
- `329` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < totalSamples; i++) {`
- `363` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] schedule = sweepSchedule(100.0, 4500.0, NUM_AUTOMATION_PASSES);`
- `397` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] schedule = sweepSchedule(0.0, 1.0, NUM_AUTOMATION_PASSES);`
- `433` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] schedule = sweepSchedule(8000.0, 500.0, NUM_AUTOMATION_PASSES);`
- `477` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] schedule = sweepSchedule(16.0, 192.0, NUM_AUTOMATION_PASSES);`
- `555` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] fullOut = probeCompiled.forward(input).toArray(0, outChannels * SIGNAL_SIZE);`
- `556` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] perOutEnergyFull = new double[outChannels];`
- `557` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int m = 0; m < outChannels; m++) {`
- `559` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < SIGNAL_SIZE; t++) {`
- `573` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] zeroedOut = probeCompiled.forward(input).toArray(0, outChannels * SIGNAL_SIZE);`
- `575` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < SIGNAL_SIZE; t++) {`
- `583` (loop) — **Scalar bookkeeping**  
  `for (int m = 1; m < outChannels; m++) {`
- `584` (loop) — **Scalar bookkeeping**  
  `for (int t = 0; t < SIGNAL_SIZE; t++) {`
- `620` (array) — **MIGRATION CANDIDATE — no defense**  
  `float[] mono = new float[numPasses * SIGNAL_SIZE];`
- `623` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < numPasses; pass++) {`
- `625` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = busCompiled`
- `628` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < SIGNAL_SIZE; t++) {`
- `743` (array) — **MIGRATION CANDIDATE — no defense**  
  `float[] mono = new float[numPasses * SIGNAL_SIZE];`
- `745` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < numPasses; pass++) {`
- `748` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = compiled.forward(in).toArray(0, SIGNAL_SIZE);`
- `749` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < SIGNAL_SIZE; t++) {`
- `775` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] full = compiled.forward(probeIn).toArray(0, SIGNAL_SIZE);`
- `776` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] noReverb = noReverbCompiled.forward(probeIn).toArray(0, SIGNAL_SIZE);`
- `778` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < SIGNAL_SIZE; t++) {`
- `789` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] inputArr = probeIn.toArray(0, SIGNAL_SIZE);`
- `792` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int t = skipEdge; t < SIGNAL_SIZE; t++) {`
- `862` (array) — **Graph construction**  
  `double[] passEnergies = new double[REVERB_PASSES];`
- `863` (array) — **Graph construction**  
  `float[] tail = new float[REVERB_PASSES * REVERB_SIGNAL_SIZE];`
- `864` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] pass2Output = null;`
- `866` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < REVERB_PASSES; pass++) {`
- `870` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] passOut = compiled.forward(input).toArray(0, REVERB_SIGNAL_SIZE);`
- `876` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < REVERB_SIGNAL_SIZE; i++) {`
- `915` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < REVERB_TAPS; n++) {`
- `926` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 3; p < REVERB_PASSES; p++) {`
- `937` (loop) — **Scalar bookkeeping**  
  `for (double e : passEnergies) totalEnergy += e;`
- `964` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] values = taps.toArray(0, count);`
- `966` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : values) {`
- `1184` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] sweepSchedule(double start, double end, int numPasses) {`
- `1185` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] schedule = new double[numPasses];`
- `1190` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < numPasses; i++) {`
- `1198` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double scheduleMean(double[] schedule) {`
- `1200` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double v : schedule) sum += v;`
- `1217` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] schedule,`
- `1239` (array) — **MIGRATION CANDIDATE — no defense**  
  `float[] producerMono = new float[totalSamples];`
- `1240` (array) — **MIGRATION CANDIDATE — no defense**  
  `float[] constMono = new float[totalSamples];`
- `1244` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < numPasses; pass++) {`
- `1249` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] producerOut = producerCompiled`
- `1252` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] constOut = constCompiled`
- `1256` (loop) — **File-format byte handling**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `1268` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < totalSamples; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/ml/test/MixdownManagerPdslVerificationTest.java

- `299` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] grid = evaluateProducer(args.get("efx_fb_transmission"), CHANNELS * CHANNELS);`
- `300` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < CHANNELS; n++) {`
- `301` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int m = 0; m < CHANNELS; m++) {`
- `325` (array) — **Independent reference implementation**  
  `double[] busT = evaluateProducer(args.get("bus_transmission"), BUS_LAYERS * BUS_LAYERS);`
- `326` (array) — **Independent reference implementation**  
  `double[] transmission = evaluateProducer(args.get("transmission"), CHANNELS * CHANNELS);`
- `327` (loop) — **Independent reference implementation**  
  `for (int j = 0; j < BUS_LAYERS; j++) {`
- `328` (loop) — **Independent reference implementation**  
  `for (int m = 0; m < BUS_LAYERS; m++) {`
- `339` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wetOut = evaluateProducer(args.get("bus_wet_out"), BUS_LAYERS * BUS_LAYERS);`
- `340` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int n = 0; n < BUS_LAYERS; n++) {`
- `341` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int m = 0; m < BUS_LAYERS; m++) {`
- `356` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] sendData = send.toArray(0, CHANNELS * BUS_LAYERS);`
- `357` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < CHANNELS; ch++) {`
- `358` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < BUS_LAYERS; j++) {`
- `369` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] busDelays = ((PackedCollection) args.get("bus_delay_samples"))`
- `371` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < BUS_LAYERS; j++) {`
- `381` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wetInData = ((PackedCollection) args.get("wet_in")).toArray(0, CHANNELS);`
- `382` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < CHANNELS; ch++) {`
- `442` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] atZero = slot.toArray(0, layers);`
- `443` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < layers; j++) {`
- `452` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] tightened = slot.toArray(0, layers);`
- `455` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] repeated = slot.toArray(0, layers);`
- `456` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < layers; j++) {`
- `474` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] restored = slot.toArray(0, layers);`
- `475` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int j = 0; j < layers; j++) {`
- `511` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] before = volume.toArray(0, CHANNELS);`
- `512` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wetInBefore = wetIn.toArray(0, CHANNELS);`
- `516` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] prev = volumePrev.toArray(0, CHANNELS);`
- `517` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wetInPrevData = wetInPrev.toArray(0, CHANNELS);`
- `518` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ch = 0; ch < CHANNELS; ch++) {`
- `586` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] frame0 = compiled.forward(impulseFrame).toArray(0, PDSL_SIGNAL_SIZE);`
- `587` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] frame1 = compiled.forward(silentFrame).toArray(0, PDSL_SIGNAL_SIZE);`
- `588` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] frame2 = compiled.forward(silentFrame).toArray(0, PDSL_SIGNAL_SIZE);`
- `611` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] evaluateProducer(Object producer, int size) {`
- `633` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double cutoff : new double[] {50.0, 200.0, 1000.0, 5000.0}) {`
- `633` (array) — **MIGRATION CANDIDATE — no defense**  
  `for (double cutoff : new double[] {50.0, 200.0, 1000.0, 5000.0}) {`
- `638` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (double cutoff : new double[] {20000.0, 12000.0, 5000.0}) {`
- `638` (array) — **MIGRATION CANDIDATE — no defense**  
  `for (double cutoff : new double[] {20000.0, 12000.0, 5000.0}) {`
- `676` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] javaSamples = renderJavaPath(mixdown, automation, time, javaWav);`
- `677` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] pdslSamples = renderPdslPath(mixdown, pdslWav);`
- `749` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] single = renderPdslMaster(mixdown, "mixdown_master", config, CHANNELS,`
- `755` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] wet = renderPdslMaster(mixdown, "mixdown_master_wet", config, 2 * CHANNELS,`
- `763` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < wet.length; i++) {`
- `988` (array) — **MIGRATION CANDIDATE — no defense**  
  `private void writeDiffWav(double[] javaSamples, double[] pdslSamples, File outputFile)`
- `991` (array) — **File-format byte handling**  
  `double[] diff = new double[n];`
- `993` (loop) — **File-format byte handling**  
  `for (int i = 0; i < n; i++) {`
- `999` (array) — **File-format byte handling**  
  `float[] floatSamples = new float[n];`
- `1000` (loop) — **File-format byte handling**  
  `for (int i = 0; i < n; i++) {`
- `1018` (array) — **Data entering from outside the system**  
  `private double[] renderJavaPath(MixdownManager mixdown, AutomationManager automation,`
- `1039` (loop) — **Data entering from outside the system**  
  `for (int b = 0; b < batches; b++) batchTick.run();`
- `1060` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] renderPdslPath(MixdownManager mixdown, File outputFile) throws IOException {`
- `1080` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] renderPdslMaster(MixdownManager mixdown, String layerName,`
- `1104` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] renderPdslMaster(MixdownManager mixdown, String layerName,`
- `1139` (array) — **File-format byte handling**  
  `double[] samples = new double[passes * sig];`
- `1140` (array) — **File-format byte handling**  
  `float[] floatSamples = new float[samples.length];`
- `1143` (array) — **File-format byte handling**  
  `double[] inData = new double[inputChannels * sig];`
- `1151` (loop) — **Sequential dependence between passes**  
  `for (int pass = 0; pass < passes; pass++) {`
- `1153` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < sig; t++) {`
- `1155` (loop) — **File-format byte handling**  
  `for (int c = 0; c < inputChannels; c++) {`
- `1160` (array) — **File-format byte handling**  
  `double[] passOut = compiled.forward(input).toArray(0, sig);`
- `1162` (loop) — **File-format byte handling**  
  `for (int i = 0; i < sig; i++) {`
- `1191` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] source = loadLoopSource();`
- `1202` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = renderPdslMaster(mixdown, "mixdown_master", config, CHANNELS, looped,`
- `1231` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] mono = renderFeedbackCombMono(1, DEMO_SIGNAL_SIZE, COMB_BUFFER_FRAMES,`
- `1284` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] mono = renderFeedbackCombMono(channels, DEMO_SIGNAL_SIZE, COMB_BUFFER_FRAMES,`
- `1286` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] diag = renderFeedbackCombMono(channels, DEMO_SIGNAL_SIZE, COMB_BUFFER_FRAMES,`
- `1295` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < mono.length; i++) {`
- `1322` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] source = loadLoopSource();`
- `1346` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] renderFeedbackCombMono(int channels, int sig, int bufFrames,`
- `1372` (array) — **Graph construction**  
  `double[] mono = new double[passes * sig];`
- `1375` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] frameData = new double[sig];`
- `1377` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int pass = 0; pass < passes; pass++) {`
- `1379` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < sig; t++) {`
- `1386` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] passOut = compiled.forward(input).toArray(0, channels * sig);`
- `1387` (loop) — **Sequential dependence between passes**  
  `for (int t = 0; t < sig; t++) {`
- `1389` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int c = 0; c < channels; c++) {`
- `1399` (array) — **File-format byte handling**  
  `private void writeMonoWav(File f, double[] mono) throws IOException {`
- `1400` (array) — **File-format byte handling**  
  `float[] floats = new float[mono.length];`
- `1401` (loop) — **File-format byte handling**  
  `for (int i = 0; i < mono.length; i++) {`
- `1413` (array) — **MIGRATION CANDIDATE — no defense**  
  `private static int firstNonFinite(double[] x) {`
- `1414` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < x.length; i++) {`
- `1460` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] source = loadLoopSource();`
- `1470` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out = renderPdslMaster(mixdown, "mixdown_main_bus", config, CHANNELS, looped,`
- `1474` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < out.length; i++) {`
- `1490` (array) — **MIGRATION CANDIDATE — no defense**  
  `private double[] loadLoopSource() throws IOException {`
- `1496` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < wavs.length && i < 25; i++) {`
- `1497` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] clip = tryLoadClip(wavs[i]);`
- `1502` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] synthetic = tryLoadClip(getTestWavFile(SOURCE_FREQ_BASE, 0.5));`
- `1524` (array) — **Data entering from outside the system**  
  `private double[] tryLoadClip(File f) {`

### studio/compose/src/test/java/org/almostrealism/studio/ml/test/PdslAudioDspTest.java

- `320` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = skipEdge; i < SIGNAL_SIZE - skipEdge; i++) {`
- `524` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `578` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `630` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `666` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `675` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `719` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `731` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < SIGNAL_SIZE; i++) {`
- `811` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out1 = identityCompiled.forward(input).toArray(0, channels * SIGNAL_SIZE);`
- `812` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int c = 0; c < channels; c++) {`
- `826` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] out2 = identityCompiled.forward(input).toArray(0, channels * SIGNAL_SIZE);`
- `870` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] lpOut = compiled.forward(signal.reshape(compiled.getInputShape())).toArray(0, SIGNAL_SIZE);`
- `872` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = FILTER_ORDER; i < SIGNAL_SIZE; i++) {`
- `882` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] narrowOut = compiled.forward(signal.reshape(compiled.getInputShape())).toArray(0, SIGNAL_SIZE);`
- `884` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = FILTER_ORDER; i < SIGNAL_SIZE; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/optimize/test/DefaultBreederTest.java

- `64` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`
- `89` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`

### studio/compose/src/test/java/org/almostrealism/studio/optimize/test/ProjectedGenomeVariationTest.java

- `68` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] result = genome().variation(0.0, 1.0, 0.0, cp(delta(10.0)))`
- `71` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`
- `80` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] result = genome().variation(0.0, 1.0, 1.0, cp(delta(10.0)))`
- `83` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`
- `92` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] result = genome().variation(0.0, 1.0, 1.0, cp(delta(-10.0)))`
- `95` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`
- `104` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] result = genome().variation(0.0, 1.0, 1.0, cp(delta(0.25)))`
- `107` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`
- `125` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] result = new ProjectedGenome(parameters)`
- `129` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`
- `141` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] parameters = original.getParameters().toArray(0, PARAMETERS);`
- `142` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int i = 0; i < PARAMETERS; i++) {`

### studio/music/src/main/java/org/almostrealism/music/pattern/BatchedPatternLayerRenderer.java

- `208` (loop) — **Scalar bookkeeping**  
  `for (int b : BUCKETS) {`
- `317` (loop) — **Scalar bookkeeping**  
  `for (RenderedNoteAudio note : destinations) {`
- `363` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int ws = 0; ws < frameCount; ws += MAX_WINDOW) {`
- `369` (loop) — **Scalar bookkeeping**  
  `for (RenderedNoteAudio note : notes) {`
- `412` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] ratios = new double[layers][bucketN];`
- `413` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][][] layerParams = new double[layers][8][bucketN];`
- `414` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] filterAdsr = new double[5][bucketN];`
- `415` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] volumeAdsr = new double[5][bucketN];`
- `416` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] destOffsets = new double[bucketN];`
- `417` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] samplingOffsets = new double[bucketN];`
- `441` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] layerDefaults = { PAD_DURATION, 0.3, 0.6, 1.0, 0.5, 0.5, 0.5, 0.5 };`
- `442` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] adsrDefaults = { 0.002, 0.002, 0.5, 0.003, PAD_DURATION };`
- `443` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int row = 0; row < bucketN; row++) {`
- `444` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < layers; l++) {`
- `446` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 8; p++) layerParams[l][p][row] = layerDefaults[p];`
- `448` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 5; p++) {`
- `463` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int row = 0; row < count; row++) {`
- `465` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < layers; l++) {`
- `470` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 8; p++) layerParams[l][p][row] = in.getLayerParams()[l][p];`
- `472` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 5; p++) {`
- `484` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < layers; l++) {`
- `486` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 8; p++) {`
- `492` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 5; p++) {`
- `534` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] ratios = new double[layers][bucketN];`
- `535` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[][] volumeAdsr = wet ? new double[5][bucketN] : null;`
- `536` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] destOffsets = new double[bucketN];`
- `537` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] samplingOffsets = new double[bucketN];`
- `553` (array) — **MIGRATION CANDIDATE — no defense**  
  `double[] adsrDefaults = { 0.002, 0.002, 0.5, 0.003, PAD_DURATION };`
- `554` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int row = 0; row < bucketN; row++) {`
- `555` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < layers; l++) ratios[l][row] = 1.0;`
- `557` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 5; p++) volumeAdsr[p][row] = adsrDefaults[p];`
- `567` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int row = 0; row < count; row++) {`
- `569` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < layers; l++) {`
- `576` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 5; p++) volumeAdsr[p][row] = in.getVolumeAdsr()[p];`
- `585` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int l = 0; l < layers; l++) {`
- `590` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (int p = 0; p < 5; p++) {`
- `626` (loop) — **Scalar bookkeeping**  
  `for (int row = 0; row < count; row++) {`
- `629` (loop) — **Scalar bookkeeping**  
  `for (int l = 0; l < layers; l++) {`

### studio/spatial/src/main/java/org/almostrealism/spatial/EditableSpatialWaveDetails.java

- `130` (loop) — **MIGRATION CANDIDATE — no defense**  
  `for (SpatialValue<?> value : values) {`
