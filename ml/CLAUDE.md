# AR-ML Development Notes for Claude Code

> **Note**: For general AR framework guidelines (environment setup, code organization principles), see [../claude.md](../claude.md)

## ML-Specific Patterns

### Model Implementation

When implementing a new language model (e.g., Llama, Qwen, Mistral):

1. **Use StateDictionary for weights** (see [../claude.md](../claude.md))
2. **Generalize existing attention/layer methods** rather than creating model-specific copies
3. **Follow the AutoregressiveModel pattern** for inference

### Package Structure

```
org.almostrealism.ml/
├── AttentionFeatures.java      # Generalized attention mechanisms
├── LayerFeatures.java           # Layer utilities (inherited from graph module)
├── AutoregressiveModel.java    # Autoregressive inference wrapper
├── StateDictionary.java         # Standard weight storage
├── BPE.java                     # Tokenization utilities
└── <model>/                     # Model-specific implementations
    ├── <Model>Config.java       # Configuration
    ├── <Model>Tokenizer.java    # Tokenizer (if model-specific)
    └── <Model>.java             # Main model class
```

### Example: Implementing a New Model

```java
public class Qwen3 implements AttentionFeatures {
    private Qwen3Config config;
    private StateDictionary stateDict;  // Use directly, no wrapper class
    private Qwen3Tokenizer tokenizer;
    private final AutoregressiveModel model;

    public Qwen3(String weightsDir, String tokenizerPath) throws IOException {
        // Load weights using StateDictionary
        this.stateDict = new StateDictionary(weightsDir);

        // Infer config from weights
        this.config = inferConfigFromWeights(stateDict);

        // Load tokenizer
        this.tokenizer = new Qwen3Tokenizer(tokenizerPath);

        // Build model using generalized methods
        this.model = buildModel();
    }

    private AutoregressiveModel buildModel() {
        Model transformer = new Model(shape(config.dim));

        for (int i = 0; i < config.layerCount; i++) {
            // Access weights directly from StateDictionary
            PackedCollection<?> wq = stateDict.get(
                String.format("model.layers.%d.self_attn.q_proj.weight", i));
            // ...

            // Use generalized attention method, not model-specific copy
            transformer.add(attention(
                config.headCount, config.kvHeadCount, config.headSize,
                wq, wk, wv, wo,
                qkNormQ, qkNormK,  // Optional parameters for QK-Norm
                freqCis, position, requirements
            ));
        }

        return AutoregressiveModel.of(transformer.compile(), /* ... */);
    }
}
```

## Weight Export from HuggingFace

Use the provided Python scripts to export weights to StateDictionary format:

```bash
# Export model weights to protobuf format
python extract_<model>_weights.py \
    <HuggingFace/Model-Name> \
    ./weights_output \
    --bf16  # Optional: use bfloat16 precision
```

This creates a directory of `.pb` files that StateDictionary can load directly.

## Testing ML Models

### Environment Variables Required

See [../claude.md](../claude.md) for AR_HARDWARE setup instructions.

### Test Structure

```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=<TestName>
```

### Test Output Logging

**IMPORTANT**: Use `Console` and `OutputFeatures` to log test output to files for later review.

**Pattern**:
```java
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;

public class MyTest implements ConsoleFeatures {
    @Test
    public void myTest() throws Exception {
        // Set up file logging BEFORE any output
        String logFile = "/workspace/project/common/ml/results/my_test.out";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        // Use Console methods instead of System.err/System.out
        log("=== My Test ===");
        log("Result: " + someValue);

        // Output goes to BOTH console AND file
    }
}
```

**Benefits**:
- Test output is saved to files for later review
- No need to capture stdout/stderr with bash redirects
- Output is available even if test crashes
- Easy to compare outputs across multiple test runs

**Best Practices**:
- Create `common/ml/results/` directory for test logs
- Use descriptive file names: `<TestName>_<date>.out` or `<TestName>.out`
- Add file logging setup at the START of each test method
- Use `log()` instead of `System.err.println()` for important results
- Keep log files in gitignore (test outputs are transient)

**Example Test with File Logging**:
```java
@Test
public void compareLogits() throws Exception {
    // Setup file logging
    String logFile = "/workspace/project/common/ml/test_output/logits_comparison.txt";
    Console.root().addListener(OutputFeatures.fileOutput(logFile));

    log("\n=== Logits Comparison Test ===\n");

    // ... test logic ...

    log(String.format("Mean Absolute Difference: %.6f", meanDiff));
    log(String.format("RMSE: %.6f", rmse));

    log("\nResults saved to: " + logFile);
}
```

### Test Types

1. **Synthetic Tests**: Validate architecture with random weights
   - Proves model constructs without crashing
   - Validates weight shapes
   - Does NOT prove numerical correctness

2. **Tokenizer Tests**: Validate encoding/decoding
   - Test special tokens
   - Test UTF-8 handling
   - Test encode/decode roundtrip

3. **Validation Tests**: Compare with reference implementation
   - Load real weights
   - Compare outputs token-by-token
   - Compare intermediate layer outputs

## Common ML Patterns

### Attention Mechanisms

**Don't create model-specific attention methods**. Instead, generalize existing ones:

```java
// In AttentionFeatures.java

// ✅ GOOD: Generalized method with optional parameters
default Block attention(int heads, int kvHeads, int headSize,
                       PackedCollection<?> wq,
                       PackedCollection<?> wk,
                       PackedCollection<?> wv,
                       PackedCollection<?> wo,
                       PackedCollection<?> qkNormQ,  // Optional: null if not using
                       PackedCollection<?> qkNormK,  // Optional: null if not using
                       PackedCollection<?> freqCis,
                       Producer<PackedCollection<?>> position,
                       ComputeRequirement... requirements) {
    // Unified implementation
    // Apply QK-Norm only if weights provided
    // Handle GQA when heads != kvHeads
}
```

```java
// ❌ AVOID: Model-specific duplicate methods
default Block qwen3Attention(...) { /* copy-paste */ }
default Block llamaAttention(...) { /* copy-paste */ }
```

### Positional Embeddings

```java
// Generalize RoPE with configurable theta
default PackedCollection<?> computeRopeFreqs(int seqLen, int headSize, double theta) {
    // Works for Llama (theta=10000), Qwen3 (theta=1000000), etc.
}
```

### Normalization Layers

```java
// RMSNorm is already generalized
rmsnorm(weights, epsilon, requirements);

// For new norm types, add optional parameters
norm(weights, bias, epsilon, requirements);  // Handles RMSNorm, LayerNorm, QK-Norm
```

## References

- [Common AR Guidelines](../claude.md) - General framework patterns
- [AR Framework Documentation](../../README.md)
