# ONNX Prototype Discovery - GPU Agent Handoff

## Objective

Test and validate the `PrototypeDiscovery` system with ONNX-based audio feature extraction and protobuf persistence.

### Goals

1. **Run PrototypeDiscovery** against a large audio sample library (~6000 .wav files)
2. **Use ONNX autoencoder** (`encoder.onnx`, `decoder.onnx`) to compute latent feature embeddings for each audio sample
3. **Persist computed features** to a `ProtobufWaveDetailsStore` so subsequent runs load from cache instead of recomputing
4. **Verify persistence speedup** - second run should be significantly faster than first run
5. **Discover prototypes** - find representative samples from the audio library using graph-based clustering

## Current Status: CRASHING

The test crashes with a **SIGSEGV in native JNI code**:

```
SIGSEGV (0xb) at pc=0x00000001328c85f4
Problematic frame:
C  [liborg.almostrealism.generated.GeneratedOperation1.dylib+0x5f4]
   Java_org_almostrealism_generated_GeneratedOperation1_apply+0x254
```

This crash occurs in the hardware acceleration layer during feature computation. The crash happens after processing ~50-60 samples.

## Files Created

### Test Class
`studio/experiments/src/test/java/org/almostrealism/audio/discovery/test/OnnxPrototypeDiscoveryTest.java`

### Run Script
`studio/experiments/run-onnx-discovery.sh`

### Module POM
`studio/experiments/pom.xml` (depends on ar-compose, ar-ml-onnx, ar-ml-djl, ar-ml-script)

## How to Run

```bash
# Set required environment variable
export AR_HARDWARE_LIBS=/tmp/ar_libs/

# Build (already done)
mvn clean install -DskipTests

# Run the test
mvn test -pl studio/experiments \
    -Dtest=OnnxPrototypeDiscoveryTest \
    -Dar.test.samples=/path/to/samples \
    -Dar.test.models=/path/to/models \
    -Dar.test.store=/tmp/onnx-discovery-store \
    -Dar.test.maxPrototypes=10
```

### Required Inputs

- **samples directory**: Directory containing .wav audio files (recursively searched)
- **models directory**: Must contain `encoder.onnx` and `decoder.onnx`
- **store directory**: Where protobuf persistence files are written (created automatically)

## Architecture

```
AudioLibrary
    |
    +-- FileWaveDataProviderNode (scans samples directory)
    |
    +-- ProtobufWaveDetailsStore (persists WaveDetails to disk)
    |
    +-- WaveDetailsFactory
            |
            +-- AutoEncoderFeatureProvider
                    |
                    +-- OnnxAutoEncoder (encoder.onnx, decoder.onnx)
```

### Data Flow

1. `AudioLibrary.refresh()` scans all .wav files
2. For each file, `WaveDetailsFactory` creates `WaveDetails`
3. `AutoEncoderFeatureProvider.computeFeatures()` runs the audio through ONNX encoder to get latent embedding
4. `WaveDetails` (including features) are persisted to `ProtobufWaveDetailsStore`
5. `PrototypeDiscovery.discoverPrototypes()` builds similarity graph and finds cluster centroids

### Key Classes

- `PrototypeDiscovery` - Graph-based prototype discovery using Louvain community detection
- `AudioLibrary` - Manages audio sample collection with persistence
- `ProtobufWaveDetailsStore` - Protobuf-based persistence for WaveDetails
- `OnnxAutoEncoder` - ONNX Runtime wrapper for encoder/decoder models
- `AutoEncoderFeatureProvider` - Adapts OnnxAutoEncoder for WaveDetailsFactory

## Expected Output

The test should print:
1. Store statistics (size on disk, entry counts)
2. Refresh timing (total time, avg time per sample)
3. List of discovered prototypes with:
   - Identifier (file path)
   - Community size (number of similar samples)
   - Centrality score

## Known Issues

1. **SIGSEGV crash** in native JNI code after ~50-60 samples - needs GPU environment to debug
2. **No OpenCL on ARM64 container** - falls back to JNI backend which is slow (~28s/sample)
3. **Large sample count** (6000+) would take many hours on CPU-only backend

## What GPU Agent Should Do

1. Run the test in a GPU-enabled environment with proper OpenCL/Metal drivers
2. Debug the SIGSEGV crash - likely a memory issue in the hardware acceleration layer
3. Verify the full pipeline works end-to-end
4. Confirm persistence speedup on second run
5. Report prototype discovery results

## Environment Requirements

- Java 17+
- Maven 3.6+
- `AR_HARDWARE_LIBS=/tmp/ar_libs/` environment variable
- GPU with OpenCL or Metal support (for reasonable performance)
- ONNX models: `encoder.onnx`, `decoder.onnx`
- Audio samples directory with .wav files
