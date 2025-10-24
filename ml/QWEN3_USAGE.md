# Qwen3 Implementation - Usage Guide

## Overview

This guide explains how to extract weights from Hugging Face Qwen3 models and use them with the AR framework implementation.

## Phase 3: Weight Conversion Complete ✅

The weight conversion system supports loading Qwen3 models from Hugging Face checkpoints via protobuf format.

### Architecture

1. **Python Extraction** (`extract_qwen3_weights.py`): Converts Hugging Face weights to protobuf
2. **Java Loading** (`StateDictionary`): Loads protobuf weights in Java
3. **Model Integration** (`Qwen3.java`): Automatically infers config and loads weights

---

## Step 1: Extract Weights from Hugging Face

### Prerequisites

```bash
pip install torch transformers
```

### Extract Qwen3-4B Weights

```bash
# Extract from Hugging Face model
python /workspace/project/common/ml/extract_qwen3_weights.py \
    Qwen/Qwen3-Instruct-2507-4B \
    ./qwen3_weights

# The script will:
# 1. Download the model from Hugging Face (or use cached version)
# 2. Extract weights layer by layer
# 3. Save to protobuf format in ./qwen3_weights/
# 4. Extract tokenizer vocabulary
```

### Script Options

```bash
# Use bfloat16 precision (saves memory)
python extract_qwen3_weights.py Qwen/Qwen3-Instruct-2507-4B ./qwen3_weights --bf16

# Use float16 precision
python extract_qwen3_weights.py Qwen/Qwen3-Instruct-2507-4B ./qwen3_weights --fp16

# Extract all weights in one batch (not split by layer)
python extract_qwen3_weights.py Qwen/Qwen3-Instruct-2507-4B ./qwen3_weights --no-layer-split
```

### Output Structure

```
qwen3_weights/
├── embeddings         # Token embeddings, final norm, lm_head
├── layer_00          # Layer 0 weights
├── layer_01          # Layer 1 weights
├── ...
├── layer_35          # Layer 35 weights
├── vocab.txt         # Vocabulary file
└── tokenizer_config.json  # Tokenizer configuration
```

---

## Step 2: Load Model in Java

### Quick Start

```java
import org.almostrealism.ml.qwen3.Qwen3;

// Load model from protobuf weights
Qwen3 model = new Qwen3("./qwen3_weights", "./qwen3_weights/tokenizer.bin");

// Set temperature for sampling
model.setTemperature(0.7);

// Generate text
model.run(256, "Once upon a time", token -> System.out.print(token));
```

### Configuration Auto-Detection

The model automatically infers configuration from weight shapes:

```java
// Config is automatically inferred:
// - dim: from embedding dimensions
// - layerCount: from number of layer files
// - headCount: from q_norm shape
// - kvHeadCount: from k_norm shape
// - hiddenDim: from FFN weight shapes
// - vocabSize: from embedding shape
```

### Manual Configuration

```java
import org.almostrealism.ml.qwen3.Qwen3Config;

// Create explicit config
Qwen3Config config = Qwen3Config.qwen3_4B();

// Load with explicit config
Qwen3 model = new Qwen3("./qwen3_weights", "./qwen3_weights/tokenizer.bin", config);
```

---

## Step 3: Run Inference

### Basic Generation

```java
import java.io.IOException;

public class Qwen3Example {
    public static void main(String[] args) throws IOException {
        // Load model
        Qwen3 model = new Qwen3("./qwen3_weights", null);
        model.setTemperature(0.7);

        // Generate
        String prompt = "Explain quantum computing in simple terms:";
        model.run(256, prompt, token -> {
            System.out.print(token);
            System.out.flush();
        });
    }
}
```

### With Performance Profiling

```java
Qwen3 model = new Qwen3("./qwen3_weights", null);
model.setTemperature(0.0);  // Greedy decoding

long duration = model.run(100, "Hello, world!",
    token -> System.out.print(token));

System.out.printf("\nTokens per second: %.2f\n", 100.0 / duration * 1000);
model.getProfile().print();
```

---

## Weight Mapping

The system automatically maps Hugging Face weight keys to AR framework format:

| Hugging Face Key | AR Framework Usage |
|------------------|-------------------|
| `model.embed_tokens.weight` | Token embeddings |
| `model.layers.{i}.input_layernorm.weight` | Pre-attention RMSNorm |
| `model.layers.{i}.self_attn.q_proj.weight` | Query projection |
| `model.layers.{i}.self_attn.k_proj.weight` | Key projection (GQA) |
| `model.layers.{i}.self_attn.v_proj.weight` | Value projection (GQA) |
| `model.layers.{i}.self_attn.o_proj.weight` | Output projection |
| `model.layers.{i}.self_attn.q_norm.weight` | **QK-Norm** for queries |
| `model.layers.{i}.self_attn.k_norm.weight` | **QK-Norm** for keys |
| `model.layers.{i}.post_attention_layernorm.weight` | Pre-FFN RMSNorm |
| `model.layers.{i}.mlp.gate_proj.weight` | FFN gate (SwiGLU) |
| `model.layers.{i}.mlp.up_proj.weight` | FFN up projection |
| `model.layers.{i}.mlp.down_proj.weight` | FFN down projection |
| `model.norm.weight` | Final RMSNorm |
| `lm_head.weight` | Output projection (or shared) |

---

## Key Features

### ✅ QK-Norm Support

The implementation includes full QK-Norm support:
- RMSNorm applied to Q and K projections (epsilon = 1e-6)
- Applied **after projection, before RoPE**
- Per-head normalization weights
- Critical for training stability at scale

### ✅ Grouped Query Attention (GQA)

- 32 query heads
- 8 KV heads (4:1 ratio)
- Reduces memory and computation for K/V caches
- Maintains quality with fewer parameters

### ✅ SwiGLU Activation

- Gated FFN with SiLU activation
- `FFN(x) = (W1(x) * silu(W3(x))) @ W2`
- Improves model quality

### ✅ RoPE with Extended Context

- Base frequency: 1,000,000 (vs 10,000 in original)
- Supports 128K context length
- Computed on-the-fly from config

---

## Hardware Acceleration

⚠️ **CRITICAL**: Set environment variables before running:

```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native
```

See [`claude.md`](./claude.md) for details.

---

## Testing with Small Models

For testing, you can use smaller Qwen3 models:

```bash
# Extract a smaller model for testing
python extract_qwen3_weights.py Qwen/Qwen3-0.5B ./qwen3_small_weights --bf16
```

Or create a test configuration:

```java
Qwen3Config testConfig = Qwen3Config.qwen3_test();
// Creates a small model: 4 layers, 512 dim, 1024 context
```

---

## Troubleshooting

### Shape Mismatches

If you encounter shape mismatch errors, check:
1. Weight extraction completed successfully
2. All layer files are present
3. Config matches the model (especially for custom models)

### Memory Issues

For large models (4B+ parameters):
1. Use `--bf16` flag during extraction
2. Ensure sufficient RAM (16GB+ recommended for 4B model)
3. Consider splitting inference into batches

### Missing Weights

If weights are missing:
```java
// The system will throw an error like:
// "Weight not found in StateDictionary: model.layers.0.self_attn.q_norm.weight"
```

Check that extraction completed and all layer files exist.

---

## Tokenization

### Qwen3Tokenizer

The `Qwen3Tokenizer` class provides byte-level BPE tokenization:

```java
import org.almostrealism.ml.qwen3.Qwen3Tokenizer;

// Load tokenizer
Qwen3Tokenizer tokenizer = new Qwen3Tokenizer("./qwen3_weights/tokenizer.bin");

// Encode text
String text = "Hello, world!";
int[] tokens = tokenizer.encode(text);  // With BOS/EOS by default
int[] tokensNoSpecial = tokenizer.encode(text, false, false);  // No special tokens

// Decode tokens
String decoded = tokenizer.decode(tokens);
```

### Special Tokens

Qwen3 uses the following special tokens:
- **BOS (Begin of Sequence)**: Token ID `151643` - `<|im_start|>`
- **EOS (End of Sequence)**: Token ID `151645` - `<|im_end|>`
- **PAD (Padding)**: Token ID `151643` (same as BOS)

### Byte-Level Encoding

The tokenizer uses byte-level BPE where:
- All UTF-8 bytes (0-255) have corresponding tokens
- Tokens are represented as `<0xXX>` for byte values
- Subword merges are applied to create longer tokens
- Total vocabulary: 151,669 tokens

## Next Steps

- **Phase 5**: Testing and validation
- **Phase 6**: Performance optimization

---

## Example: Complete Workflow

```bash
# 1. Extract weights (Python)
python extract_qwen3_weights.py Qwen/Qwen3-Instruct-2507-4B ./qwen3_weights --bf16

# 2. Set environment variables
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native

# 3. Run Java code
cat > Qwen3Demo.java << 'EOF'
import org.almostrealism.ml.qwen3.Qwen3;
import java.io.IOException;

public class Qwen3Demo {
    public static void main(String[] args) throws IOException {
        Qwen3 model = new Qwen3("./qwen3_weights", null);
        model.setTemperature(0.7);
        model.run(100, "Explain the theory of relativity:",
            token -> System.out.print(token));
    }
}
EOF

# 4. Compile and run (adjust classpath as needed)
javac -cp "path/to/ar-framework.jar" Qwen3Demo.java
java -cp ".:path/to/ar-framework.jar" Qwen3Demo
```

---

## Technical Details

### Protobuf Format

Weights are stored using `CollectionLibraryData` protobuf:
- **Shape**: Stored as `TraversalPolicyData` with dimensions
- **Data**: Float32 arrays (4 bytes per weight)
- **Keys**: Direct Hugging Face weight names

### Performance

Expected performance (approximate):
- **Qwen3-4B**: ~10-20 tokens/sec (CPU)
- **Qwen3-0.5B**: ~50-100 tokens/sec (CPU)

With hardware acceleration (GPU/Metal/OpenCL): 5-10x faster

---

## References

- [Qwen3 Technical Report](../qwen3/Qwen3_Technical_Report.pdf)
- [PLAN.md](./PLAN.md) - Complete implementation plan
- [claude.md](./claude.md) - Hardware acceleration setup
- [StateDictionary.java](./src/main/java/org/almostrealism/ml/StateDictionary.java) - Weight loading
