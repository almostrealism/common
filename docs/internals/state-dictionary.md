# StateDictionary: Model Weight Management

StateDictionary is the standard mechanism for loading, storing, and saving neural network
weights in the AR framework. It maps string keys to `PackedCollection` weight tensors,
using protobuf as the serialization format.

**Source:** `engine/ml/src/main/java/org/almostrealism/ml/StateDictionary.java`

## Overview

StateDictionary extends `AssetGroup` and implements `Destroyable`. It reads binary protobuf
files (`CollectionLibraryData`) from a directory, decodes each entry via `CollectionEncoder`,
and stores the results in an in-memory `Map<String, PackedCollection>`.

Models access weights by calling `stateDict.get(key)` with a dot-separated key that
identifies the specific parameter (e.g., `"model.layers.0.self_attn.q_proj.weight"`).

## Loading Weights

StateDictionary supports four constructors for different loading scenarios:

```java
// From a local directory containing .pb files
StateDictionary stateDict = new StateDictionary("/path/to/weights");

// From an AssetGroupInfo (supports remote/bundled assets with lazy download)
StateDictionary stateDict = new StateDictionary(assetGroupInfo);

// From a list of Asset objects
StateDictionary stateDict = new StateDictionary(assetList);

// From a pre-built map (useful for testing)
Map<String, PackedCollection> weights = new HashMap<>();
weights.put("model.embed_tokens.weight", myEmbeddings);
StateDictionary stateDict = new StateDictionary(weights);
```

When loading from files, `loadWeights()` iterates over all non-hidden files in the
directory, parses each as a `CollectionLibraryData` protobuf message, and decodes
every `CollectionLibraryEntry` into a `PackedCollection` keyed by its string name.

## HuggingFace Compatibility

Weights exported from HuggingFace/PyTorch models retain their original key names.
The Python extraction script (`engine/ml/qwen/tools/extract_qwen3_weights.py`) converts
PyTorch state dicts to protobuf format using a direct key mapping -- HuggingFace keys
are preserved as-is. The script:

1. Loads a HuggingFace model via `transformers.AutoModelForCausalLM`
2. Iterates over `model.state_dict()` entries
3. Converts each numpy array to a `CollectionData` protobuf (float32)
4. Splits output into multiple `.pb` files to stay under protobuf size limits

```bash
python extract_qwen3_weights.py Qwen/Qwen3-Instruct-2507-4B ./qwen3_weights
```

This produces a directory of `.pb` files (e.g., `embeddings.pb`, `layer_00.pb`, ...)
that StateDictionary loads directly.

## Key Naming Conventions

Keys follow the HuggingFace naming convention: dot-separated paths reflecting the
model's module hierarchy. Layer indices are zero-based integers.

**LLM keys (Qwen3 example):**

| Key | Description |
|-----|-------------|
| `model.embed_tokens.weight` | Token embedding matrix |
| `model.layers.{i}.self_attn.q_proj.weight` | Query projection weight |
| `model.layers.{i}.self_attn.k_proj.weight` | Key projection weight |
| `model.layers.{i}.self_attn.v_proj.weight` | Value projection weight |
| `model.layers.{i}.self_attn.o_proj.weight` | Output projection weight |
| `model.layers.{i}.self_attn.q_proj.bias` | Query projection bias |
| `model.layers.{i}.self_attn.k_proj.bias` | Key projection bias |
| `model.layers.{i}.self_attn.v_proj.bias` | Value projection bias |
| `model.layers.{i}.self_attn.q_norm.weight` | QK-Norm query weight |
| `model.layers.{i}.self_attn.k_norm.weight` | QK-Norm key weight |
| `model.layers.{i}.input_layernorm.weight` | Pre-attention RMSNorm |
| `model.layers.{i}.post_attention_layernorm.weight` | Pre-FFN RMSNorm |
| `model.layers.{i}.mlp.gate_proj.weight` | FFN gate projection (W1) |
| `model.layers.{i}.mlp.up_proj.weight` | FFN up projection (W3) |
| `model.layers.{i}.mlp.down_proj.weight` | FFN down projection (W2) |
| `model.norm.weight` | Final RMSNorm |
| `lm_head.weight` | Output projection (may be shared with embeddings) |

**Audio model keys (Oobleck encoder example):**

Keys for audio models use a similar dot-separated convention with numeric layer indices:
`encoder.layers.0.weight_g`, `encoder.layers.0.weight_v`, `encoder.layers.0.bias`,
`encoder.layers.6.alpha`, `encoder.layers.6.beta`.

## Using with Models

Model classes hold a `StateDictionary` reference and retrieve weights by key during
model construction. The typical pattern iterates over transformer layers, building
a key prefix from the layer index:

```java
for (int i = 0; i < config.layerCount; i++) {
    String prefix = "model.layers." + i;

    PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
    PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
    PackedCollection layerWv = stateDict.get(prefix + ".self_attn.v_proj.weight");
    PackedCollection layerWo = stateDict.get(prefix + ".self_attn.o_proj.weight");

    PackedCollection layerW1 = stateDict.get(prefix + ".mlp.gate_proj.weight");
    PackedCollection layerW2 = stateDict.get(prefix + ".mlp.down_proj.weight");
    PackedCollection layerW3 = stateDict.get(prefix + ".mlp.up_proj.weight");

    // Build attention and FFN blocks using these weights
    transformer.add(attention(nHeads, kvHeads, headSize,
                              layerWq, layerWk, layerWv, layerWo,
                              qkNormQ, qkNormK, freqCis, requirements));
    transformer.add(feedForward(layerW1, layerW3, layerW2));
}
```

See `Qwen3.java` for a complete example of this pattern.

## Saving and Serialization

StateDictionary uses protobuf (`CollectionLibraryData`) for persistence. Each weight
is encoded as a `CollectionLibraryEntry` containing the key string and a `CollectionData`
message (which holds the flattened array and its `TraversalPolicyData` shape).

```java
// Save all weights to a single protobuf file (FP32 precision)
stateDict.save(Path.of("/output/weights.pb"));

// Save with explicit precision
stateDict.save(Path.of("/output/weights.pb"), Precision.FP32);
```

The static `encode` method converts a weight map to protobuf without writing to disk:

```java
Collections.CollectionLibraryData data = StateDictionary.encode(weights, Precision.FP32);
```

This is used internally by `ModelBundle` when bundling weights with metadata.

## ModelBundle

`ModelBundle` wraps a `StateDictionary` with metadata for model management. It adds:

- **Model type** (`base`, `lora_adapter`, `merged`)
- **Base model ID** for adapter bundles
- **Training metrics** and configuration
- **AdapterConfig** for LoRA parameters (rank, alpha, target layers)

```java
// Save a LoRA adapter bundle
ModelBundle.forAdapter(weights, adapterConfig, "base-model-v1")
    .withMetrics(trainingMetrics)
    .withDescription("Fine-tuned on bass loops")
    .save(Path.of("my_adapter.pb"));

// Load a bundle and extract weights
ModelBundle loaded = ModelBundle.load(Path.of("my_adapter.pb"));
StateDictionary weights = loaded.getWeights();
AdapterConfig config = loaded.toAdapterConfig();
```

## Memory Management

StateDictionary implements `Destroyable`. Calling `destroy()` invokes
`PackedCollection.destroy()` on every loaded weight tensor and clears the map.
Always destroy a StateDictionary when it is no longer needed to release
GPU/off-heap memory.

```java
StateDictionary stateDict = new StateDictionary("/path/to/weights");
try {
    // Use weights...
} finally {
    stateDict.destroy();
}
```
