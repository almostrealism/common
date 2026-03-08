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
import org.almostrealism.model.StateDictionary;

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

### 2. Transformer Attention

```java
import org.almostrealism.layers.AttentionFeatures;
import static org.almostrealism.layers.LayerFeatures.*;

// Multi-Head Attention with GQA and QK-Norm
Block attnBlock = attention(
    nHeads,           // Number of query heads (e.g., 32)
    kvHeads,          // Number of KV heads (e.g., 8 for GQA)
    headSize,         // Dimension per head
    wq, wk, wv, wo,   // Weight matrices
    qkNormQ, qkNormK, // Optional QK normalization weights
    freqCis,          // RoPE frequencies
    requirements      // Computation requirements
);

// Feed-Forward Network with SwiGLU activation
Block ffnBlock = feedForward(
    wGate,  // Gate projection (W1)
    wUp,    // Up projection (W3)
    wDown   // Down projection (W2)
);

// Complete transformer block
Block transformerLayer = transformer(
    attnBlock,
    ffnBlock,
    attnNormWeights,
    ffnNormWeights
);
```

### 3. Qwen3 Model Implementation

```java
import org.almostrealism.models.qwen3.Qwen3;

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
import org.almostrealism.model.AutoregressiveModel;

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
import org.almostrealism.models.qwen3.Qwen3Tokenizer;

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

```java
public interface AttentionFeatures extends LayerFeatures {
    // Multi-head attention with optional GQA and QK-Norm
    default Block attention(int heads, int kvHeads, int headSize,
                           PackedCollection<?> wq, wk, wv, wo,
                           PackedCollection<?> qkNormQ, qkNormK,
                           PackedCollection<?> freqCis,
                           ComputeRequirement... requirements);

    // Feed-forward with SwiGLU activation
    default Block feedForward(PackedCollection<?> wGate,
                             PackedCollection<?> wUp,
                             PackedCollection<?> wDown);

    // Complete transformer layer
    default Block transformer(Block attention, Block ffn,
                             PackedCollection<?> attnNorm,
                             PackedCollection<?> ffnNorm);
}
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
- Timestep embeddings via Fourier features
- Optional cross-attention conditioning
- Prepended conditioning approach (not AdaLayerNorm)

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

### Conditioning Approach: Prepended Conditioning vs AdaLayerNorm

DiffusionTransformer uses **prepended conditioning** instead of Adaptive Layer Normalization (AdaLayerNorm).

**What is AdaLayerNorm?**
AdaLayerNorm is a technique where normalization parameters (scale/shift) are computed from conditioning signals like timestep. Formula: `y = gamma(cond) * norm(x) + beta(cond)`. Some diffusion models use this for timestep conditioning.

**Why AR uses prepended conditioning instead:**
- **Simpler architecture**: No per-layer conditioning projections needed
- **Standard normalization**: Regular LayerNorm throughout, less complexity
- **Attention-based integration**: Conditioning tokens participate in self-attention naturally
- **Flexibility**: Easy to add/remove conditioning types without architecture changes

**How it works:**
1. Timestep and global conditioning projected to embedding dimension
2. Prepended as extra tokens to the input sequence
3. Self-attention integrates conditioning information
4. Conditioning tokens removed before output

See `DiffusionTransformer.prependConditioning()` for implementation.

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

Hardware acceleration requires the following environment variable:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
```

`AR_HARDWARE_DRIVER` is optional and best left unset to auto-detect the best available backend. To force a specific backend, set it to `native`, `opencl`, or `metal`.

## Performance Features

- **KV Caching** - Attention keys/values cached to avoid recomputation
- **Grouped-Query Attention** - Reduces KV cache size by 4x
- **Hardware Compilation** - JIT compilation to native/GPU code
- **Memory Efficiency** - Zero-initialized caches prevent numerical issues
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

## Further Reading

- See **ar-graph** module for Model and Block composition
- See **ar-collect** module for PackedCollection fundamentals
- See **ar-hardware** module for acceleration setup
- See **CLAUDE.md** for development guidelines and patterns
