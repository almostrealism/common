# AR-ML Development Notes for Claude Code

---

## ⚠️ CRITICAL: DO NOT COMMIT CODE ⚠️

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** use `git commit` commands
- Claude does not have the ability to create valid commits
- You can only **stage changes** using `git add`
- The human developer must review and commit all changes themselves

See [../CLAUDE.md](../CLAUDE.md) for full details on this policy.

---

## ⚠️ CRITICAL: DO NOT MODIFY POM.XML FILES ⚠️

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** add dependencies to pom.xml files
- **NEVER** assume you understand the module dependency graph
- **IF IN DOUBT, DO NOT TOUCH THE POM FILE**
- Write your code, run `mvn compile`, and if it fails inform the user

See [../CLAUDE.md](../CLAUDE.md) for full details on this policy.

---

## ⚠️ CRITICAL: NEVER REFERENCE VERSION NUMBERS ⚠️

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** include specific version numbers anywhere in CLAUDE.md files
- **NEVER** mention library versions (e.g., "JavaFX 21", "gRPC 1.53.0")
- **NEVER** mention project versions (e.g., "version 0.72")
- **NEVER** reference artifact versions in documentation
- Version numbers change constantly and become stale immediately
- Always refer to pom.xml files as the single source of truth for versions
- If you need to mention a dependency, use just its name without any version

**Why this matters:** Hardcoded version numbers in documentation become outdated instantly, cause confusion, and lead to errors when developers trust stale documentation over actual build files.

---

## ⚠️ CRITICAL: USE MCP TEST RUNNER FOR ALL TESTS ⚠️

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** use `Bash` tool with `mvn test` commands to run tests
- **ALWAYS** use the `mcp__ar-test-runner__start_test_run` MCP tool for running tests

```
# Correct way to run ML module tests:
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"  # Skips comparison tests that need external data
  timeout_minutes: 10

# Then check status and failures:
mcp__ar-test-runner__get_run_status  run_id: "<id>"
mcp__ar-test-runner__get_run_failures  run_id: "<id>"
```

See [../CLAUDE.md](../CLAUDE.md) for full MCP test runner documentation.

---

## ⚠️ CRITICAL: TEST CLASS REQUIREMENTS ⚠️

All test classes **MUST** extend `TestSuiteBase`. See [../CLAUDE.md](../CLAUDE.md) for:
- Why `TestSuiteBase` is required (test grouping, depth filtering)
- Why you must NEVER manually add `@Rule TestDepthRule`
- Why you must ALWAYS consult ar-docs MCP before infrastructure changes

---

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

See [../claude.md](../claude.md) for AR_HARDWARE setup instructions. Note that the MCP test runner handles these automatically.

### Memory Configuration for Large Models

Large models (e.g., full Oobleck autoencoder, LLMs) require more memory than the default 8GB:

```bash
# Increase memory for large ML models
export AR_HARDWARE_MEMORY_SCALE=8   # 16GB
export AR_HARDWARE_MEMORY_SCALE=9   # 32GB
```

**If you see `HardwareException: Memory max reached`**, increase `AR_HARDWARE_MEMORY_SCALE`.

### Test Structure

**⚠️ Use MCP test runner - NOT bash commands!**

```
# Run all ML tests with pipeline profile (skips comparison tests):
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"

# Run a specific test class:
mcp__ar-test-runner__start_test_run
  module: "ml"
  test_classes: ["CausalMaskIsolationTest"]

# Run a specific test method:
mcp__ar-test-runner__start_test_run
  module: "ml"
  test_methods: [{"class": "CausalMaskIsolationTest", "method": "testCausalMaskDynamicPositionUpdates"}]
```

**Reference only** (what the MCP tool runs internally):
```bash
# DO NOT RUN DIRECTLY - use MCP tool instead
export AR_HARDWARE_MEMORY_SCALE=8 && \
export AR_HARDWARE_LIBS=/home/developer/.libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=<TestName> -DAR_TEST_PROFILE=pipeline
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

---

## ABSOLUTELY NO CODE DUPLICATION

**THIS IS NON-NEGOTIABLE.**

If you find yourself copying and pasting code, or writing nearly-identical logic multiple times, **STOP IMMEDIATELY**. This is unacceptable and will never be tolerated.

**The rule**: If you have written more than 3-5 lines that are structurally similar to other code, you MUST refactor to eliminate the duplication BEFORE proceeding. Use:
- Helper methods with parameters
- Generic methods with type parameters
- Factory functions
- Higher-order functions that accept lambdas/functional interfaces
- Template method pattern
- Any other appropriate abstraction

**Examples of violations**:
- Writing two attention implementations with identical structure but different model names
- Copy-pasting a layer method and changing a few parameter names
- Creating model-specific methods when a generalized version would work
- Duplicating tokenizer logic across different models

**No exceptions. No excuses. Refactor first, then proceed.**

The threshold is NOT 20 lines. If you catch yourself typing similar code a second time, that is already too much. Extract it immediately.

---

## References

- [Common AR Guidelines](../claude.md) - General framework patterns
- [AR Framework Documentation](../../README.md)
