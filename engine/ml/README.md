# Almost Realism ML Module (`ar-ml`)

The ML Module provides a complete framework for loading, configuring, and running large language models (LLMs) with hardware acceleration. It implements transformer-based architectures including Qwen3, with support for multi-head attention, rotary position embeddings, and efficient autoregressive generation.

## Purpose

This module exists to:

1. **Load Transformer Models** - Support for loading weights from HuggingFace/protobuf formats
2. **Implement Attention Mechanisms** - Multi-head, grouped-query, and cross-attention
3. **Enable Text Generation** - Autoregressive token generation with sampling strategies
4. **Provide Tokenization** - Byte-level BPE tokenizers for text encoding/decoding
5. **Support Hardware Acceleration** - GPU/CPU execution via ar-hardware backends

## What It Provides

### 1. Model Loading with StateDictionary

StateDictionary loads model weights from **protobuf format** (`.pb` files), NOT safetensors or PyTorch checkpoints. Weights must be exported to protobuf format first using the provided Python extraction scripts.

**Supported Format:** Protobuf (`CollectionLibraryData`)
**NOT Supported:** safetensors, PyTorch checkpoints (`.pt`/`.bin`), GGUF

```java
import org.almostrealism.ml.StateDictionary;

// Load model weights from directory containing .pb files
StateDictionary stateDict = new StateDictionary("/path/to/weights");

// Access weights by HuggingFace-style keys
PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
PackedCollection<?> wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
PackedCollection<?> wk = stateDict.get("model.layers.0.self_attn.k_proj.weight");
PackedCollection<?> wv = stateDict.get("model.layers.0.self_attn.v_proj.weight");
```

**Loading from different sources:**
```java
// From directory path
StateDictionary stateDict = new StateDictionary("/path/to/weights");

// From AssetGroupInfo (for remote/bundled assets)
StateDictionary stateDict = new StateDictionary(assetGroupInfo);

// From a list of Assets
StateDictionary stateDict = new StateDictionary(assetList);

// For testing: from a pre-built map
Map<String, PackedCollection> weights = new HashMap<>();
weights.put("model.embed_tokens.weight", embeddings);
StateDictionary stateDict = new StateDictionary(weights);
```

**Saving weights:**
```java
// Save to protobuf file
stateDict.save(Path.of("/output/weights.pb"));

// Save with specific precision
stateDict.save(Path.of("/output/weights.pb"), Precision.FP32);
```

**Lazy weight loading (default):** `StateDictionary.enableMaterializeWeights`
controls whether a tensor is decoded into a freshly allocated `PackedCollection`
when the library is opened, or whether it is located on disk and only read through
a `FileMapping` when a kernel first asks for it. The default (`false`) keeps the
file in place: a weight no kernel ever reads costs neither Java heap nor device
memory. Set the flag to `true` for tests that need to inspect a tensor on the
host before any kernel runs.

### 2. Transformer Attention

`attention`, `feedForward`, and `transformer` are default instance methods on
`AttentionFeatures`, so a caller implements the interface (as model classes such
as `Qwen3` do) rather than importing them statically:

```java
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.model.Block;

class TransformerLayerBuilder implements AttentionFeatures {
    Block buildLayer(int nHeads, int kvHeads,
                      PackedCollection rmsAttWeight,
                      PackedCollection wk, PackedCollection wv,
                      PackedCollection wq, PackedCollection wo,
                      PackedCollection bk, PackedCollection bv, PackedCollection bq,
                      PackedCollection qkNormQ, PackedCollection qkNormK,
                      CollectionProducer freqCis,
                      PackedCollection rmsFfnWeight,
                      PackedCollection w1, PackedCollection w2, PackedCollection w3,
                      Producer<PackedCollection> position,
                      ComputeRequirement... requirements) {
        // Multi-Head Attention with GQA and QK-Norm
        Block attnBlock = attention(
            nHeads, kvHeads,          // Query heads, KV heads (GQA when kvHeads < nHeads)
            rmsAttWeight,             // Pre-attention RMSNorm weights
            wk, wv, wq, wo,           // Key/Value/Query/Output projection weights
            bk, bv, bq,               // Optional bias terms (null if unused)
            qkNormQ, qkNormK,         // Optional QK-Norm weights (null to skip)
            freqCis,                  // RoPE frequencies
            position,                 // Current position in sequence
            requirements              // Computation requirements
        );

        // Feed-Forward Network with SwiGLU activation
        Block ffnBlock = feedForward(
            rmsFfnWeight,  // Pre-FFN RMSNorm weights
            w1,            // Gate projection
            w2,            // Down projection
            w3             // Up projection
        );

        // Complete transformer block (attention + feed-forward in one call)
        return transformer(
            nHeads, kvHeads, rmsAttWeight, wk, wv, wq, wo,
            bk, bv, bq, qkNormQ, qkNormK,
            freqCis, rmsFfnWeight, w1, w2, w3, position,
            requirements
        );
    }
}
```

### 3. Qwen3 Model Implementation

```java
import org.almostrealism.ml.qwen3.Qwen3;

// Load and configure model
Qwen3 model = new Qwen3(
    "/path/to/weights",
    "/path/to/tokenizer.bin"
);

// Set generation parameters
model.setTemperature(0.7);  // 0.0 = greedy, >0 = sampling

// Generate text
model.run(
    256,                    // Max tokens to generate
    "Once upon a time",    // Prompt
    token -> System.out.print(token)  // Token callback
);
```

### 4. Autoregressive Generation

```java
import org.almostrealism.ml.AutoregressiveModel;

// Wrap compiled model for token generation
AutoregressiveModel generator = AutoregressiveModel.of(
    compiledModel,
    step -> log("Step: " + step),
    tokenId -> tokenEmbeddings.get(tokenId)
);

// Set sampling temperature
generator.setTemperature(0.0);  // Greedy decoding

// Generate next token
int nextToken = generator.next();
```

### 5. Tokenization

```java
import org.almostrealism.ml.qwen3.Qwen3Tokenizer;

// Load tokenizer
Qwen3Tokenizer tokenizer = new Qwen3Tokenizer("/path/to/tokenizer.bin");

// Encode text to token IDs
String text = "Hello, world!";
int[] tokens = tokenizer.encodeAsInt(text);

// Decode tokens to text
String decoded = tokenizer.decodeAsInt(tokens);

// Special tokens
int bos = tokenizer.getBOSToken();  // 151643
int eos = tokenizer.getEOSToken();  // 151645
```

### 6. Rotary Position Embeddings (RoPE)

```java
import org.almostrealism.layers.RotationFeatures;

// Compute RoPE frequency matrix
PackedCollection<?> freqCis = computeRotaryFreqs(
    dim,          // Model dimension
    seqLen,       // Sequence length
    theta         // Base frequency (10000 for LLaMA, 1000000 for Qwen3)
);

// Apply RoPE to query/key tensors
Producer<PackedCollection<?>> rotatedQ = ropeRotation(
    query,
    freqCis,
    seqLen,
    headDim
);
```

## Key Interfaces and Classes

### StateDictionary

```java
public class StateDictionary implements Destroyable {
    public StateDictionary(String directory);
    public PackedCollection<?> get(String key);

    @Override
    public void destroy();  // Cleanup resources
}
```

### AttentionFeatures

The signatures below are a reference summary, not compilable Java — each `default` method
body is omitted (shown as `;`) since these are the real implementations' parameter shapes,
not a copy-pasteable interface:

```java
public interface AttentionFeatures extends RotationFeatures, FeedForwardFeatures {
    // Multi-head attention with optional GQA, bias terms, and QK-Norm
    default Block attention(int heads, int kvHeads,
                           PackedCollection rmsAttWeight,
                           PackedCollection wk, PackedCollection wv,
                           PackedCollection wq, PackedCollection wo,
                           PackedCollection bk, PackedCollection bv, PackedCollection bq,
                           PackedCollection qkNormQ, PackedCollection qkNormK,
                           CollectionProducer freqCis,
                           Producer<PackedCollection> position,
                           ComputeRequirement... requirements);

    // Feed-forward with SwiGLU activation (from FeedForwardFeatures)
    default Block feedForward(PackedCollection rms,
                             PackedCollection w1, PackedCollection w2, PackedCollection w3,
                             ComputeRequirement... requirements);

    // Complete transformer layer (residual attention + residual feed-forward),
    // built from the transformer.pdsl asset
    default Block transformer(int heads, int kvHeads,
                             PackedCollection rmsAttWeight,
                             PackedCollection wk, PackedCollection wv,
                             PackedCollection wq, PackedCollection wo,
                             PackedCollection bk, PackedCollection bv, PackedCollection bq,
                             PackedCollection qkNormQ, PackedCollection qkNormK,
                             CollectionProducer freqCis,
                             PackedCollection rmsFfnWeight,
                             PackedCollection w1, PackedCollection w2, PackedCollection w3,
                             Producer<PackedCollection> position,
                             ComputeRequirement... requirements);
}
```

### TransformerBlockFeatures

`TransformerBlockFeatures` (extends `AttentionFeatures`) assembles complete pre-norm
transformer blocks — self-attention, optional cross-attention, and a gated feed-forward,
each in a residual branch — for the sequence-based (non-KV-cached) models such as
`DiffusionTransformer` and `T5GemmaEncoder`. Every overload delegates to one fully
specified `transformerBlock(...)` that accepts an `AttentionVariant`, optional adaLN
modulation, optional per-position additive conditioning, and a `NormalizationType`, so
all callers share one block assembly:

```java
Block block = transformerBlock(
    batchSize, dim, seqLen, heads, crossAttend,
    contextSeqLen, context,
    preNormWeight, preNormBias,
    selfQkv, selfWo,
    selfQNormWeight, selfQNormBias, selfKNormWeight, selfKNormBias,
    invFreq,
    crossAttPreNormWeight, crossAttPreNormBias,
    crossWq, crossKv, crossWo,
    crossQNormWeight, crossQNormBias, crossKNormWeight, crossKNormBias,
    ffnNormWeight, ffnNormBias, w1, w2, w1Bias, w2Bias,
    attentionScores,       // optional Receptor to capture cross-attention scores, or null
    projectionFactory,     // ProjectionFactory.dense() or a LoRA-wrapped factory
    AttentionVariant.STANDARD,
    diffLambda,            // learned lambda for variants that need it, or null
    modulation,            // adaLN scale/shift/gate, shape [batch, 6, dim], or null for prepend-style conditioning
    localAddition,         // per-position additive conditioning, shape [batch, seqLen, dim], or null
    NormalizationType.RMS,
    paddingMask            // per-position validity, or null for no masking
);
```

### AutoregressiveModel

```java
public class AutoregressiveModel {
    public static AutoregressiveModel of(CompiledModel model,
                                         IntConsumer stepConsumer,
                                         IntFunction<PackedCollection<?>> tokenEmbed);

    public void setTemperature(double temperature);
    public int next();  // Generate next token
}
```

### ByteLevelBPETokenizer

```java
public abstract class ByteLevelBPETokenizer implements Tokenizer {
    public int[] encodeAsInt(String text);
    public String decodeAsInt(int[] tokens);

    public abstract int getBOSToken();
    public abstract int getEOSToken();
    public abstract int getPADToken();
    public abstract int getUNKToken();
}
```

## Qwen3 Model Architecture

### Configuration

```java
Qwen3Config config = new Qwen3Config();
config.vocabSize = 151669;      // Vocabulary size
config.dim = 3584;              // Model dimension
config.hiddenDim = 11008;       // FFN hidden dimension
config.nLayers = 36;            // Number of transformer layers
config.nHeads = 32;             // Query heads
config.nKVHeads = 8;            // KV heads (GQA 4:1 ratio)
config.headDim = 112;           // Dimension per head
config.maxSeqLen = 128000;      // Maximum context length
config.ropeTheta = 1000000.0;   // RoPE base frequency
```

### Special Features

- **QK-Normalization** - Stabilizes attention training
- **Grouped-Query Attention** - 4:1 query-to-KV head ratio for efficiency
- **Extended Context** - Up to 128K tokens
- **SwiGLU Activation** - Gated linear unit in FFN
- **Shared Embeddings** - Input/output embeddings share weights

### Layer Structure

```
Qwen3 Model
+-- Token Embeddings (151669 x 3584)
+-- 36 Transformer Layers
|   +-- Self-Attention
|   |   +-- QK-Norm (query/key normalization)
|   |   +-- Multi-Head Attention (32 heads)
|   |   +-- Grouped-Query (8 KV heads)
|   |   +-- RoPE (rotary position embeddings)
|   +-- Feed-Forward
|   |   +-- Gate Projection (W1)
|   |   +-- Up Projection (W3)
|   |   +-- SwiGLU Activation
|   |   +-- Down Projection (W2)
|   +-- RMSNorm (pre-attention, pre-FFN)
+-- Output Projection (shared with embeddings)
```

## Common Patterns

### Pattern 1: Loading and Running Qwen3

```java
// Initialize model
Qwen3 model = new Qwen3(
    "/models/qwen3-4b",
    "/models/tokenizer.bin"
);

// Configure generation
model.setTemperature(0.7);

// Generate text with callback
model.run(
    100,                          // Max 100 tokens
    "Explain quantum computing:", // Prompt
    token -> {
        System.out.print(token);
        System.out.flush();
    }
);
```

### Pattern 2: Custom Model with Attention

```java
import static org.almostrealism.layers.AttentionFeatures.*;

Model customModel = new Model(shape(dim));

for (int layer = 0; layer < nLayers; layer++) {
    // Load weights for this layer
    PackedCollection<?> wq = stateDict.get("layer." + layer + ".attn.wq");
    PackedCollection<?> wk = stateDict.get("layer." + layer + ".attn.wk");
    PackedCollection<?> wv = stateDict.get("layer." + layer + ".attn.wv");
    PackedCollection<?> wo = stateDict.get("layer." + layer + ".attn.wo");

    // Add attention block
    customModel.add(attention(
        nHeads, kvHeads, headDim,
        wq, wk, wv, wo,
        null, null,  // No QK-Norm
        freqCis,
        ComputeRequirement.ACROSS_CELLS
    ));

    // Add FFN
    customModel.add(feedForward(wGate, wUp, wDown));
}

// Compile model
CompiledModel compiled = customModel.compile();
```

### Pattern 3: Temperature-Based Sampling

```java
// Greedy decoding (deterministic)
generator.setTemperature(0.0);
int token = generator.next();  // Always picks highest probability

// Sampling with temperature
generator.setTemperature(0.7);  // Lower = more focused
int token = generator.next();   // Samples from distribution

generator.setTemperature(1.5);  // Higher = more random
int token = generator.next();
```

### Pattern 4: Custom Tokenization

```java
public class MyTokenizer extends ByteLevelBPETokenizer {
    @Override
    public int getBOSToken() { return 1; }

    @Override
    public int getEOSToken() { return 2; }

    @Override
    public int getPADToken() { return 0; }

    @Override
    public int getUNKToken() { return 3; }

    @Override
    protected void loadVocabulary(String path) {
        // Load vocabulary from file
    }
}
```

### Pattern 5: Manual Token Loop

```java
// Encode prompt
int[] promptTokens = tokenizer.encodeAsInt("Hello, world!");

// Initialize model state
PackedCollection<?> input = tokenEmbeddings.get(promptTokens[0]);

// Generation loop
for (int step = 0; step < maxTokens; step++) {
    // Forward pass
    PackedCollection<?> logits = model.forward(input);

    // Sample next token
    int nextToken = sampleFromLogits(logits, temperature);

    // Decode and print
    String tokenText = tokenizer.decodeAsInt(new int[]{nextToken});
    System.out.print(tokenText);

    // Check for EOS
    if (nextToken == tokenizer.getEOSToken()) break;

    // Update input for next iteration
    input = tokenEmbeddings.get(nextToken);
}
```

## Audio Diffusion Models

The ar-ml module also includes support for transformer-based diffusion models for audio generation.

### DiffusionTransformer

A conditional diffusion architecture combining self-attention with optional cross-attention:

```java
import org.almostrealism.ml.audio.DiffusionTransformer;

// Create model
DiffusionTransformer model = new DiffusionTransformer(
    64,    // ioChannels - input/output audio channels
    1536,  // embedDim - transformer embedding dimension
    24,    // depth - number of transformer layers
    24,    // numHeads - attention heads
    1,     // patchSize - 1 = no patching
    768,   // condTokenDim - cross-attention conditioning (0 = disabled)
    1536,  // globalCondDim - global conditioning (0 = disabled)
    "predict_noise",  // diffusion objective
    weights
);

// Forward pass
PackedCollection output = model.forward(
    audioInput,      // [batch, ioChannels, seqLen]
    timestep,        // [batch, 1] - diffusion timestep
    crossAttnCond,   // [batch, condSeqLen, condTokenDim] or null
    globalCond       // [batch, globalCondDim] or null
);
```

**Key Features:**
- Rotary Position Embeddings (RoPE)
- Timestep embeddings via Fourier features, either a learned frequency matrix or a deterministic
  geometric ladder (see `TimestepEncoding`)
- Optional cross-attention conditioning
- Configurable conditioning mode: prepended conditioning (default) or adaLN-Zero modulation
- Optional per-position local additive conditioning (e.g. an inpainting mask), summed into the
  hidden state of every block independently of the conditioning mode

### LoRA Fine-Tuning

For parameter-efficient fine-tuning, use `LoRADiffusionTransformer`:

```java
import org.almostrealism.ml.audio.LoRADiffusionTransformer;
import org.almostrealism.layers.AdapterConfig;

// Create LoRA-enabled model
AdapterConfig config = AdapterConfig.forAudioDiffusion();
LoRADiffusionTransformer loraModel = new LoRADiffusionTransformer(
    config, 64, 1536, 24, 24, 1, 768, 1536, "predict_noise", weights
);

// Get trainable parameters (only LoRA weights)
List<PackedCollection> trainable = loraModel.getTrainableParameters();

// After training, merge LoRA into base model
loraModel.mergeAllLoraWeights();
```

**AdapterConfig Options:**
- `forAudioDiffusion()` - rank=8, targets all attention projections
- `full()` - targets all layers including FFN
- `minimal()` - rank=4, only Q/K/V projections

### ProjectionFactory

Abstracts projection layer creation for LoRA vs dense selection:

```java
// Standard dense projections
ProjectionFactory factory = ProjectionFactory.dense();

// LoRA-wrapped projections
ProjectionFactory factory = ProjectionFactory.lora(config, loraLayers);

// Pass to attention methods
Block attention = sequenceAttention(shape, weights, factory);
```

The fully specified `sequenceAttention` overload additionally accepts a `NormalizationType`
for query/key normalization, an optional per-position `paddingMask` (zeroes masked value
vectors), an optional `keyMask` (excludes masked keys from softmax entirely via
`AttentionFeatures.MASKED_LOGIT_PENALTY`), and a `logitSoftcap` (`0` to disable). Every
shorter overload, including the one above, delegates to it with `NormalizationType.LAYER`
and no masking.

### Conditioning Approach: Prepended Conditioning vs AdaLayerNorm

`DiffusionTransformer` selects its conditioning scheme via `ConditioningMode`: `PREPEND` (the
default) or `ADALN`.

**What is AdaLayerNorm (adaLN)?**
AdaLayerNorm is a technique where normalization parameters (scale/shift/gate) are computed from
conditioning signals like timestep. Formula: `y = gamma(cond) * norm(x) + beta(cond)`. Many
diffusion transformers (including adaLN-Zero variants) use this for timestep and global
conditioning.

**`ConditioningMode.PREPEND` (default):**
- **Simpler architecture**: No per-layer conditioning projections needed
- **Standard normalization**: Regular LayerNorm throughout, less complexity
- **Attention-based integration**: Conditioning tokens participate in self-attention naturally
- **Flexibility**: Easy to add/remove conditioning types without architecture changes

How it works:
1. Timestep and global conditioning projected to embedding dimension
2. Prepended as extra tokens to the input sequence
3. Self-attention integrates conditioning information
4. Conditioning tokens removed before output

See `DiffusionTransformer.prependConditioning()` for implementation.

**`ConditioningMode.ADALN`:**
adaLN-Zero modulation derives per-block scale/shift/gate vectors from the combined timestep and
global conditioning embedding and modulates each sub-layer (self-attention and feed-forward) in
place, without lengthening the sequence. When no global conditioning is configured, the timestep
embedding alone drives the modulation. See `DiffusionTransformer.adaptiveConditioning()` and
`AdaptiveLayerNormFeatures`.

**Local additive conditioning** is orthogonal to the two modes above: when `DiffusionTransformerConfig`
is given a `localAddCondDim > 0`, `DiffusionTransformer.getLocalAddCond()` exposes a
`[batch, localAddCondDim, audioSeqLen]` buffer that the caller writes a per-position control signal
into (an inpainting mask concatenated with the masked latent, for example) before `forward()`. Each
block projects it to the transformer width and adds it to the hidden state between the self-attention
and feed-forward sub-layers; positions occupied by prepended or memory tokens receive no local
conditioning. The buffer starts zero-filled, which is the value plain generation supplies.

`DiffusionTransformerConfig.withNormalization(NormalizationType)` selects LayerNorm (default) or
RMSNorm for every block norm and query/key norm; `withPaddingMask(true)` adds a per-position latent
padding mask (`DiffusionTransformer.getPaddingMask()`), initially all valid; a caller generating less
than the full sequence marks the padded tail with `DiffusionTransformer.setValidLength(int)` before
`forward()`, and self-attention ignores those positions' values.

### Stable Audio 3 Conditioning and Codec

`StableAudio3Conditioner` (package `org.almostrealism.ml.audio`) builds the cross-attention and
global conditioning that `DiffusionTransformer` expects, from pre-tokenized prompt IDs and a
duration:

```java
import org.almostrealism.ml.audio.AudioAttentionConditioner.ConditionerOutput;
import org.almostrealism.ml.audio.StableAudio3Conditioner;
import org.almostrealism.ml.t5gemma.T5GemmaEncoder;

StableAudio3Conditioner conditioner = new StableAudio3Conditioner(
        t5GemmaEncoder,      // T5GemmaEncoder — encodes the prompt
        paddingEmbedding,    // learned embedding substituted at padded prompt positions
        durationConditioner  // NumberConditioner — embeds the requested duration
);

ConditionerOutput conditioning = conditioner.runConditioners(tokenIds, durationSeconds);
```

The prompt itself is encoded by `T5GemmaEncoder` (package `org.almostrealism.ml.t5gemma`), a
T5Gemma text encoder configured by `T5GemmaConfig` and loaded from a `StateDictionary` the same
way as the other model weights in this module.

`StableAudio3` (package `org.almostrealism.ml.audio`) is the end-to-end text-to-audio generator
that wires these pieces together: the conditioner, a `DiffusionSampler` driving the
`DiffusionTransformer` with the ping-pong schedule of the released models, and a `SAMEAutoEncoder`
decoder. `StableAudio3.small(...)` builds the released small model from the `dit`, `conditioner`
and `ae` weight sets extracted by `engine/ml/scripts/extract_sa3_weights.py` plus the T5Gemma
encoder weights:

```java
StableAudio3 sa3 = StableAudio3.small(transformerWeights, conditionerWeights,
        promptEncoderWeights, autoencoderWeights, maxSeconds);
PackedCollection audio = sa3.generate(seed, tokenIds, seconds).evaluate();  // [channels, samples]
```

The transformer and decoder are compiled once for the longest clip the instance generates; a
shorter request is generated at that length with the padding mask covering the requested duration
plus headroom, then truncated. Classifier-free guidance is off by default and is enabled with
`setGuidance(scale, negativePrompt)`.

Other Stable Audio 3 building blocks:
- **`ClassifierFreeGuidance`** — combines a conditional and unconditional denoiser prediction
  (with optional adaptive projected guidance); wired into sampling via
  `DiffusionSampler#setGuidance(ClassifierFreeGuidance, PackedCollection, PackedCollection)`.
- **`DistributionShift`** (`LogSNRShift`, `FluxDistributionShift`, `LogitDistributionShift`) —
  warps the rectified-flow sampler's uniform timestep schedule so more steps land at the noise
  levels where structure emerges. Some implementations and configurations shift with the
  generated sequence length (`isLengthDependent()` reports which); `DistributionShift.identity()`
  and a `FluxDistributionShift` constructed with a single constant `alpha` ignore sequence length
  entirely.
- **`OobleckCodec`** (`OobleckEncoder`/`OobleckDecoder`) and **`SAMEAutoEncoder`** — latent audio
  autoencoders that compress waveforms to and from the diffusion model's latent space.
- **`PatchedPretransform`** — a parameter-free pretransform, used by `SAMEAutoEncoder`, that folds
  runs of consecutive samples into the channel axis before encoding and unfolds them after decoding.

## Integration with Other Modules

### Graph Module
- Uses **Model** and **Block** for layer composition
- **CompiledModel** for optimized execution
- **LayerFeatures** for building layers

### Collect Module
- **PackedCollection** for weight storage
- **TraversalPolicy** for tensor shapes
- **Producer/Evaluable** for lazy computation

### Hardware Module
- GPU/CPU acceleration via **HardwareOperator**
- **ComputeContext** for kernel compilation
- **MemoryData** for efficient memory management

## Environment Configuration

`AR_HARDWARE_LIBS` is auto-detected — do not set it manually. `AR_HARDWARE_DRIVER` is optional and best left unset to auto-detect the best available backend. To force a specific backend, set it to `native`, `cl`, or `mtl` (see [base/hardware/README.md](../../base/hardware/README.md) for the full list and the named-vs-wildcard contract).

## Performance Features

- **KV Caching** - Attention keys/values cached to avoid recomputation
- **Grouped-Query Attention** - Reduces KV cache size by 4x
- **Hardware Compilation** - JIT compilation to native/GPU code
- **Memory Efficiency** - KV caches rely on zero-fill at allocation to avoid numerical issues;
  see [ml-inference-pipeline.md](../../docs/internals/ml-inference-pipeline.md#zero-initialization)
  for which memory providers this currently holds for
- **Batch Processing** - Support for processing multiple sequences

## Testing

Tests are organized by component:

```bash
# Run all ML tests
mvn test -pl ml

# Specific test
mvn test -pl ml -Dtest=Qwen3SyntheticTest
```

Test output is logged to files for review:
```java
Console.root().addListener(OutputFeatures.fileOutput("test_output.txt"));
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-graph</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-collect</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>com.google.protobuf</groupId>
    <artifactId>protobuf-java</artifactId>
    <version>3.25.1</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-ml</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Moonbeam MIDI Foundation Model

The `org.almostrealism.ml.midi` package implements Moonbeam — a ~200M parameter LLaMA-style transformer for symbolic music generation. Two architectural features distinguish it from standard transformer implementations:

### Compound MIDI Tokenization

Each MIDI note is represented as a **6-tuple compound token**, not a single integer:

| Index | Attribute | Vocab Size | Encoding |
|-------|-----------|-----------|---------|
| 0 | Onset (relative delta) | 4099 | `onset[i] - onset[i-1]` in ticks |
| 1 | Duration | 4099 | Note duration in ticks |
| 2 | Octave | 13 | `pitch // 12` |
| 3 | Pitch class | 14 | `pitch % 12` (+ 2 reserved) |
| 4 | Instrument | 131 | MIDI program number (128 = drums) |
| 5 | Velocity | 130 | MIDI velocity (+ 2 reserved) |

Special tokens: SOS = all -1, EOS = all -2, PAD = all -3.

`MidiTokenizer` in `studio/music/` converts standard MIDI files to compound token sequences. `MidiFileReader`/`MidiFileWriter` use `javax.sound.midi` (standard JDK, no external dependency).

### Fundamental Music Embedding (FME)

Sinusoidal embedding for continuous attributes — mathematically identical to positional encoding but with a learnable bias and linear projection:

```
angle_rates = 1 / base^(2*(i//2) / dim)
encoding[2i]   = sin((value + bias) * angle_rates[i])
encoding[2i+1] = cos((value + bias) * angle_rates[i])
output = Linear(encoding)
```

`FundamentalMusicEmbedding` implements `Block` for a single attribute. `CompoundMidiEmbedding` concatenates 5 FME embeddings (onset, duration, octave, pitch, velocity) and one standard instrument embedding lookup into a `hidden_size` vector (6 × 320 = 1920 for Moonbeam).

### Multidimensional Relative Attention (MRA)

The 12 attention heads are partitioned into 6 groups of 2. Each group applies RoPE with a different theta and position IDs derived from the corresponding attribute values, rather than sequential position IDs. This is handled in `AttentionFeatures` — pass attribute-derived position tensors instead of sequential indices.

### GRU Decoder

A stacked GRU (4 layers in the paper configuration, 2 in the 309M checkpoint) that autoregressively decodes 7 tokens per note from the transformer's hidden state. Its structure is the PDSL asset `pdsl/midi/gru_decoder.pdsl`: a start model (the summary projection, written into every layer's row of the hidden state) run once per note, and a step model (per layer: read the layer's hidden-state row, GRU cell, write the row back; then the logits head) run once per token. The GRU hidden state is a `[layers, decoderHiddenSize]` state collection the two models share, like the attention KV cache. `GRUDecoder.java` binds the checkpoint weights (`GRUDecoder.load(stateDict, config)`), compiles the two models, and runs the decode loop — token selection and the embedding lookup of the chosen token happen between forward passes.

### MoonbeamMidi

Top-level entry point that wires together:
- `CompoundMidiEmbedding` → transformer layers → `GRUDecoder` → output
- `StateDictionary` for weight loading from protobuf format
- `AutoregressiveModel` for compound token generation

### Key Classes

| Class | Package | Role |
|-------|---------|------|
| `MoonbeamMidi` | `org.almostrealism.ml.midi` | Top-level model |
| `GRUDecoder` | `org.almostrealism.ml.midi` | GRU decoder built from `gru_decoder.pdsl` |
| `CompoundMidiEmbedding` | `org.almostrealism.ml.midi` | 6-attribute compound embedding |
| `FundamentalMusicEmbedding` | `org.almostrealism.ml.midi` | Sinusoidal embedding, single attribute |
| `MidiTokenizer` | `org.almostrealism.music.midi` | MIDI → compound token conversion |
| `MidiFileReader` | `org.almostrealism.music.midi` | Standard MIDI file parsing |
| `MidiCompoundToken` | `org.almostrealism.music.midi` | 6-tuple note representation |

---

## Protobuf Disk Store

`org.almostrealism.persist` provides a general-purpose key-value store for datasets up to ~10 GB with bounded memory usage via `FrequencyCache`.

### ProtobufDiskStore

Generic over `T extends com.google.protobuf.Message`. Records are written in protobuf wire format using `writeDelimitedTo` — no wrapper messages, no double serialization. The store is already available in `ar-ml` (which has the protobuf-maven-plugin and dependency).

```java
// Construct with root directory, protobuf parser, and memory/batch budget
ProtobufDiskStore<MyRecord> store = new ProtobufDiskStore<>(
    new File("/data/store"),
    MyRecord.parser(),
    500 * 1024 * 1024L,  // 500 MB max in memory
    4 * 1024 * 1024      // target batch file size in bytes (4 MB)
);

// Or use the two-argument constructor for the default memory/batch budget
ProtobufDiskStore<MyRecord> defaultStore = new ProtobufDiskStore<>(
    new File("/data/store"), MyRecord.parser());

store.put("id-1", myRecord);
MyRecord r = store.get("id-1");
store.scan(record -> process(record));
store.pairwiseScan((a, b) -> compare(a, b));  // Visits every unordered pair exactly once
store.close();
```

**Data layout:**
```
<store-root>/
├── index.bin          # DiskStoreIndex: record ID → (batch_id, byte_offset)
├── hnsw.bin           # HnswIndex for vector search (if vectors were stored)
├── batch_0000.bin     # Length-delimited protobuf records
├── batch_0001.bin
└── ...
```

Batch files default to ~4 MB each. The index is loaded into memory on startup. Records are loaded batch-by-batch into `FrequencyCache`.

### HnswIndex — Vector Search

`HnswIndex` builds a Hierarchical Navigable Small World graph over `PackedCollection` vectors, all held in one contiguous `[capacity, dimension]` store. Every similarity computation — normalizing a vector on insert, or scoring a query against the whole store — is a single compiled `Evaluable` dispatch; at the scales this index currently serves, that whole-store dispatch is cheap enough that both construction and search use exact scores rather than walking the layered graph. The graph is still built and persisted from those exact neighbors so a batched graph traversal can become the search strategy later without a format change.

Integrated into `ProtobufDiskStore` via the `put(id, record, vector)` overload:

```java
// Store with embedding vector
PackedCollection<?> vector = new PackedCollection<>(shape(768));
vector.set(0, embedding);  // load a 768-dim embedding
store.put("id-1", myRecord, vector);

// Search by vector similarity
List<SearchResult<MyRecord>> results = store.search(queryVector, 10);  // top-10
// SearchResult<T> has: id, record, similarity (cosine by default)
```

Use `insertEmbedding(id, vector)` to add a vector for a record that is already persisted, without rewriting the record itself — useful for backfilling an index onto an existing store.

**Parameters:** `M=16` (connections per node, higher = better recall, more memory), `efConstruction=200` (build quality), `efSearch=50` (query-time candidate list, tunable via `setEfSearch`/`getEfSearch`, currently unused by the exact-scoring search path).

**Memory:** Vectors are held once in the contiguous store (`capacity * dimension * 4` bytes), not duplicated per node; each node's additional cost is its per-layer neighbor lists (ID + score per edge). The index is persisted as `hnsw.bin` and reloaded on startup.

### Collection Data Memory

`org.almostrealism.persist.assets` provides the read-only `Memory` surface that
`StateDictionary` places weight tensors behind. Together they let a tensor stay in
its protobuf file until a kernel first reads it; a model whose weights no kernel
ever asks for costs neither the Java heap nor the device:

- **`CollectionEncoder`** — encode/decode a `PackedCollection` to protobuf `CollectionData`. The deferred `decode` forms hand back a collection whose values are read from the encoding on demand, never copied off it.
- **`CollectionDataMemoryProvider`** — the shared `"PROTOBUF"` `MemoryProvider` exposing collection data as read-only memory. Default `allocate(int)` rejects empty allocation (memory is created only from existing messages or file references); the default `setMem(...)` rejects writes — migration to a device is one-way.
- **`CollectionDataMemory`** — the abstract `Memory` implementation the provider returns.
- **`ParsedCollectionDataMemory`** — memory backed by a `CollectionData` already on the heap. Skips the device allocation when no kernel ever reads the values.
- **`MappedCollectionDataMemory`** — memory backed by values still in the file they were written to. The values are read through a `FileMapping` of the file when a kernel first needs them.
- **`CollectionDataReference`** — locates collection data inside a message by descending a path of field numbers, and reports the byte range and precision of its values without parsing them. `MappedCollectionDataMemory` is built on top of one of these.
- **`EncodedMessage`** — bytes of a protobuf message with field positions locatable without decoding. `CollectionDataReference` walks one of these to find what it needs.

The `CollectionDataMemoryProvider` memory is consulted on first kernel use and migrates to the device lazily, so a `StateDictionary` weight that nothing uses costs nothing.

---

## Further Reading

- See **ar-graph** module for Model and Block composition
- See **ar-collect** module for PackedCollection fundamentals
- See **ar-hardware** module for acceleration setup
- See **CLAUDE.md** for development guidelines and patterns
