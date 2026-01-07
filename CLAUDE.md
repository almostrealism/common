# Almost Realism Common - Development Guidelines for Claude Code

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
- The MCP test runner automatically handles environment variables, async execution, and structured failure reporting

**Available MCP test runner tools:**
| Tool | Purpose |
|------|---------|
| `mcp__ar-test-runner__start_test_run` | Start a test run (use this!) |
| `mcp__ar-test-runner__get_run_status` | Check test run status |
| `mcp__ar-test-runner__get_run_output` | Get console output |
| `mcp__ar-test-runner__get_run_failures` | Get detailed failure info |
| `mcp__ar-test-runner__list_runs` | List recent test runs |
| `mcp__ar-test-runner__cancel_run` | Cancel a running test |

**Parameters for `start_test_run`:**
- `module`: Maven module to test (e.g., "ml", "utils")
- `test_classes`: List of specific test classes
- `test_methods`: List of specific test methods
- `profile`: Test profile name (sets AR_TEST_PROFILE system property)
- `depth`: AR_TEST_DEPTH value (0-10)
- `jvm_args`: Additional JVM arguments
- `timeout_minutes`: Max run time

**Example usage:**
```
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"
  timeout_minutes: 10
```

**Why this matters:** The MCP test runner is purpose-built for this codebase. Using Bash for tests bypasses proper environment setup, loses structured output, and ignores specialized tooling. This documentation showing bash commands is for REFERENCE ONLY - actual test execution must use MCP tools.

---

## Quick Links

- **[Quick Reference](docs/QUICK_REFERENCE.md)** - Condensed API cheatsheet
- **[llms.txt](llms.txt)** - Documentation index for AI agents
- **[Documentation Portal](docs/index.html)** - Full HTML documentation

---

### Setup Instructions

1. **Set environment variables** before running Java code:
   ```bash
   export AR_HARDWARE_LIBS=/tmp/ar_libs/
   export AR_HARDWARE_DRIVER=native
   ```

   The directory will be created automatically if it doesn't exist.

2. **For Maven tests**, always prefix test commands with the environment variables:
   ```bash
   export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
   export AR_HARDWARE_DRIVER=native && \
   mvn test -pl <module>
   ```

### What These Variables Do

- **`AR_HARDWARE_LIBS`**: Specifies the directory where hardware acceleration libraries (JNI .so files, OpenCL kernels, etc.) will be generated and loaded from
- **`AR_HARDWARE_DRIVER`**: Specifies which hardware backend to use:
  - `native`: Standard JNI operations with runtime-generated native code (default)
  - `opencl`: OpenCL acceleration (CPU/GPU)
  - `metal`: Metal GPU acceleration (Apple Silicon)
  - `external`: Generated executable approach

### Common Issues

❌ **Forgetting to set these variables** will result in:
- `NoClassDefFoundError: Could not initialize class org.almostrealism.collect.PackedCollection`
- Runtime errors when trying to compile operations
- Missing library errors
- Failures during model inference

✅ **Always verify** these are set before running:
```bash
echo $AR_HARDWARE_LIBS
echo $AR_HARDWARE_DRIVER
```

### Memory Configuration

For large models or tests that require more memory than the default 8GB limit:

```bash
# Maximum memory allocation (2^SCALE × 64MB)
export AR_HARDWARE_MEMORY_SCALE=4   # 1GB (default)
export AR_HARDWARE_MEMORY_SCALE=6   # 4GB
export AR_HARDWARE_MEMORY_SCALE=7   # 8GB (current default)
export AR_HARDWARE_MEMORY_SCALE=8   # 16GB
export AR_HARDWARE_MEMORY_SCALE=9   # 32GB
```

**When to increase memory:**
- Running large ML models (e.g., full Oobleck decoder with 5 blocks)
- Tests that produce `HardwareException: Memory max reached`
- Working with large audio/image data

**Example with increased memory:**
```bash
export AR_HARDWARE_MEMORY_SCALE=8 && \
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=OobleckValidationTest
```

See [hardware/README.md](hardware/README.md) for complete memory and performance configuration options.

---

## Code Organization Principles

1. Never use @SuppressWarnings
2. Always include javadoc documentation for newly introduced code
3. Do not include excessive comments within method implementations

---

## ⚠️ CRITICAL: Process Optimization and Isolation Architecture ⚠️

**THIS IS A SACRED ARCHITECTURAL PRINCIPLE. VIOLATING IT WILL BREAK THE SYSTEM.**

### The Golden Rule

**ONLY `IsolatedProcess` is empowered to break expression embedding. No other computation should return null from `getValueAt()` to force isolation.**

### How Process Isolation Works

1. **`Process.optimize()`** must be called before `Process.get()` for proper isolation
2. **`ParallelProcess.optimize(ctx, process)`** checks `process.isIsolationTarget(ctx)` on each child
3. If isolation is needed, it calls `process.isolate()` which wraps in `IsolatedProcess`
4. **`IsolatedProcess` does NOT implement `TraversableExpression`**
5. When a parent's `getValueAt()` checks `producer instanceof TraversableExpression`, it naturally returns `null`
6. This is the ONLY proper way to break expression embedding

### What NOT to Do

```java
// NEVER DO THIS - it violates the isolation architecture
@Override
public Expression<Double> getValueAt(Expression index) {
    // BAD: Returning null to "force" isolation
    if (producer instanceof SomeComputationType) {
        return null;  // WRONG! This bypasses proper isolation
    }
    return producer.getValueAt(index);
}
```

### Debugging Expression Tree Issues

If expression trees are growing too large:

1. **Check if `optimize()` is being called** before `get()`
2. If `isIsolationTarget()` returns true but isolation isn't happening, trace the optimization path
3. Ensure `OperationList.enableAutomaticOptimization` is set appropriately, OR ensure callers call `optimize()` explicitly
4. **NEVER** hack `getValueAt()` to return null - fix the optimization chain instead

### Proper Fix Pattern

```java
// CORRECT: Ensure optimize() is called
OperationList op = model.getForward().push(input);
op = (OperationList) op.optimize();  // This triggers proper isolation
Runnable compiled = op.get();
```

### Key Classes

- **`Process.optimize()`** - Entry point for optimization
- **`ParallelProcess.optimize(ctx, process)`** - Checks `isIsolationTarget()` and calls `isolate()`
- **`IsolatedProcess`** - The ONLY class that should break expression embedding
- **`isIsolationTarget()`** - Return true if computation requires isolation (e.g., native loops)

### Use StateDictionary for Model Weights

**Standard Pattern**: All model implementations should use `StateDictionary` for weight management.

```java
// GOOD: Use StateDictionary directly
StateDictionary stateDict = new StateDictionary(weightsDirectory);
PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
PackedCollection<?> wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
```

```java
// AVOID: Creating separate weight container classes
// Unless there's a compelling reason (e.g., weight transformations, caching)
public class ModelWeights {
    PackedCollection<?> wq;  // Duplicates StateDictionary storage
    PackedCollection<?> wk;
    // ...
}
```

**When to use a wrapper class**:
- Weight transformations are needed (e.g., transposing, reshaping)
- Complex weight organization logic
- Caching computed values (e.g., RoPE frequencies)

**If needed**, make it a subclass or thin wrapper:
```java
public class ModelWeights extends StateDictionary {
    // Add specialized methods only
}
```

### Generalize, Don't Duplicate

## ABSOLUTELY NO CODE DUPLICATION - THIS IS NON-NEGOTIABLE

If you find yourself copying and pasting code, or writing nearly-identical logic multiple times, **STOP IMMEDIATELY**. This is unacceptable and will never be tolerated.

**The rule**: If you have written more than 3-5 lines that are structurally similar to other code, you MUST refactor to eliminate the duplication BEFORE proceeding. Use helper methods, generics, factory functions, lambdas, or any appropriate abstraction.

**No exceptions. No excuses. Refactor first, then proceed.**

---

**Principle**: Extend and generalize existing code rather than creating model-specific copies.

```java
// GOOD: Generalize existing method with optional parameters
default Block attention(int heads, int kvHeads, int headSize,
                       PackedCollection<?> wq, PackedCollection<?> wk,
                       PackedCollection<?> wv, PackedCollection<?> wo,
                       PackedCollection<?> qkNormQ, PackedCollection<?> qkNormK,  // Optional
                       ...) {
    // Single implementation that handles all cases
    if (qkNormQ != null && qkNormK != null) {
        // Apply QK-Norm
    }
}
```

```java
// AVOID: Creating model-specific duplicate methods
default Block llamaAttention(...) { /* ... */ }
default Block qwenAttention(...) { /* ... */ }  // Copy-paste with minor changes
default Block mistralAttention(...) { /* ... */ }
```

**Benefits of generalization**:
- Single source of truth for attention logic
- Bugs fixed once, not per model
- Easier to add new features
- Better testing coverage

**When duplication is acceptable**:
- Fundamentally different architectures (encoder-decoder vs decoder-only)
- Performance-critical paths requiring specialization
- Temporary experimentation (mark with TODO to generalize)

### Deprecation Guidelines

**Mark deprecated code clearly**:
```java
/**
 * @deprecated Use StateDictionary constructor instead.
 * Binary checkpoint format is deprecated and will be removed in a future version.
 * This constructor remains for backward compatibility only.
 */
public ModelWeights(FloatBuffer buffer) {
    // Legacy code...
}
```

**Common deprecated patterns**:
- Binary checkpoint constructors (use StateDictionary)
- Model-specific weight container classes (use StateDictionary directly)
- Duplicate attention/layer implementations (generalize existing code)

---

## Development Workflow

### Before Starting a Task

1. **Check for existing implementations**: Don't reinvent the wheel
2. **Identify generalization opportunities**: Can existing code be extended?
3. **Review StateDictionary**: Can it handle your use case?
4. **Set environment variables**: Especially for testing

### During Development

1. **Prefer composition over duplication**
2. **Add optional parameters rather than creating new methods**
3. **Use StateDictionary as the standard weight storage**
4. **Test frequently with environment variables set**

### Before Committing

1. **Remove TODO markers for completed work**
2. **Mark deprecated code with @deprecated tags**
3. **Ensure all tests pass with hardware acceleration enabled**
4. **Document any new patterns or breaking changes**

### Build Verification Requirements

**CRITICAL**: Before declaring any task complete, you MUST verify the full build succeeds:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn clean install -DskipTests
```

**This command must complete with BUILD SUCCESS.** Do not rely on:
- `mvn compile` alone (misses test compilation and packaging)
- `mvn compile -q` (suppresses errors that may appear later)
- Building individual modules (misses cross-module dependencies)

The full `mvn clean install -DskipTests` command:
- Compiles all main sources
- Compiles all test sources
- Packages all modules
- Installs to local repository
- Verifies all inter-module dependencies

**Only after this command succeeds** should you report that the build is working.

### Considering something completed

Sometimes you may encounter a summary of earlier work. That summary may indicate that there are other things to do.
If this happens, that means YOU ARE EXPECTED TO DO THOSE THINGS.

Working on something for a while, writing some commentary about completing more of it later, and then reading the
commentary back to yourself is NOT a condition for suspending your work.

Before you start working, REPEAT this principle to yourself. If summarization of earlier tasks is ever required,
REPEAT THIS PRINCIPLE IN THE SUMMARY

---

## SYSTEMATIC Debugging Approach

**THIS IS MANDATORY. NO SPECULATION. ONLY EVIDENCE.**

When debugging a failing test or numerical discrepancy, you MUST follow this systematic process:

### 1. Component Inventory
First, identify ALL components involved in the failing code path. For example, if a decoder test fails:
- List every layer type used (conv1d, convTranspose1d, activation, normalization, etc.)
- List every test that exists for each component
- Document this inventory BEFORE making any claims about the bug

### 2. Bottom-Up Test Execution
Run tests from smallest to largest scope. For each test, record:
- **Test name**: The exact test method
- **Result**: PASS or FAIL
- **Relevant output**: Key numbers or error messages

**Example test hierarchy:**
```
Level 1 (Unit): testConv1dSmall, testConvTranspose1dSmall
Level 2 (Scale): testConv1dLargeChannels, testConvTranspose1dLargeChannels
Level 3 (Component): testWNConv1d, testSnakeActivation
Level 4 (Block): testDecoderBlock1, testDecoderBlock3
Level 5 (Integration): testFullDecoder
```

### 3. Evidence-Based Conclusions
**NEVER say "the problem might be X" without test evidence.**

Instead, structure conclusions as:
- "Tests A, B, C passed, proving components X, Y, Z work correctly"
- "Test D failed, which isolates the bug to component W"
- "No test exists for component V, so I need to create one to verify"

### 4. Gap Identification
If all existing tests pass but the integration test fails:
- Identify which component combinations are NOT tested
- Create targeted tests for those gaps
- Run the new tests and record results

### What NOT to do:
- DO NOT speculate about possible causes without running tests
- DO NOT claim a component is correct without a test proving it
- DO NOT skip levels in the test hierarchy
- DO NOT make changes without understanding exactly which test will verify the fix

---

## Module-Specific Guidelines

For module-specific development notes, see:
- [ML Module](./ml/claude.md) - Machine learning models and layers
- [Graph Module](./graph/README.md) - Computation graph and layers
- [Collect Module](./collect/README.md) - Collection operations

---

## Common Patterns

### Loading Model Weights

```java
// Standard pattern for all models
StateDictionary stateDict = new StateDictionary(weightsDirectory);

// Access weights by HuggingFace key names
PackedCollection<?> embeddings = stateDict.get("model.embed_tokens.weight");
PackedCollection<?> wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");

// Use helper methods for repeated patterns
private PackedCollection<?> getLayerWeight(StateDictionary dict, int layer, String name) {
    return dict.get(String.format("model.layers.%d.%s", layer, name));
}
```

### Building Transformer Layers

```java
// Generalize existing methods with optional parameters
Model transformer = new Model(shape(dim));

for (int i = 0; i < layerCount; i++) {
    // Load weights
    PackedCollection<?> wq = getLayerWeight(stateDict, i, "self_attn.q_proj.weight");
    // ...

    // Use generalized attention method
    transformer.add(attention(
        heads, kvHeads, headSize,
        wq, wk, wv, wo,
        qkNormQ, qkNormK,  // null if not using QK-Norm
        freqCis,
        requirements
    ));
}
```

---

## Testing

### Running Tests

**⚠️ IMPORTANT: Use the MCP test runner tool, NOT bash commands!**

See the critical section at the top of this document. The bash commands below are for **reference only** to understand what the MCP tool does internally.

```
# Use MCP tool - this is what you should actually do:
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"  # Optional: sets AR_TEST_PROFILE

# Check status:
mcp__ar-test-runner__get_run_status
  run_id: "<id from start_test_run>"

# Get failures:
mcp__ar-test-runner__get_run_failures
  run_id: "<id>"
```

**Reference only** - the MCP tool runs these internally:
```bash
# Single module (DO NOT RUN DIRECTLY - use MCP tool)
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml

# With profile (DO NOT RUN DIRECTLY - use MCP tool)
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -DAR_TEST_PROFILE=pipeline
```

### Test Organization

- **Unit tests**: Test individual components in isolation
- **Integration tests**: Test component interactions
- **Synthetic tests**: Validate architecture with random weights
- **Validation tests**: Compare against reference implementations

### Test Output Logging

**IMPORTANT**: Use `Console` and `OutputFeatures` (from `ar-io` module) to log test output to files for later review.

**Pattern**:
```java
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;

public class MyTest implements ConsoleFeatures {
    @Test
    public void myTest() throws Exception {
        // Set up file logging BEFORE any output
        String logFile = "/workspace/project/common/<module>/test_output/my_test_results.txt";
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
- Create test_output directories in each module for test logs
- Use descriptive file names: `<TestName>_results.txt`
- Add file logging setup at the START of each test method
- Use `log()` instead of `System.err.println()` for important results
- Keep log files in gitignore (test outputs are transient)

---

## Module Overview

Quick reference for what each module provides:

### Foundation & Core
| Module | Purpose | Key Classes |
|--------|---------|-------------|
| **uml** | Annotations, lifecycle, metadata | `@Function`, `Lifecycle`, `Named` |
| **io** | Logging, metrics, file I/O | `Console`, `ConsoleFeatures`, `OutputFeatures` |
| **relation** | Producer/Evaluable pattern | `Producer`, `Evaluable`, `Countable` |

### Data & Computation
| Module | Purpose | Key Classes |
|--------|---------|-------------|
| **code** | Expression trees, code generation | `Expression`, `Scope`, `TraversalPolicy` |
| **collect** | Multi-dimensional arrays | `PackedCollection`, `CollectionProducer`, `Shape` |
| **hardware** | Hardware acceleration | `Hardware`, `ComputeRequirement`, `MemoryData` |

### Mathematics
| Module | Purpose | Key Classes |
|--------|---------|-------------|
| **algebra** | Linear algebra operations | `Vector`, `Pair`, `PairFeatures`, `VectorFeatures` |
| **geometry** | 3D geometry, ray tracing | `Ray`, `TransformMatrix`, `Intersection` |
| **time** | Temporal, FFT, filtering | `Temporal`, `TemporalScalar`, `CursorPair` |

### Domain
| Module | Purpose | Key Classes |
|--------|---------|-------------|
| **graph** | Neural network layers | `Cell`, `Receptor`, `Layer`, `Model` |
| **ml** | Transformer models | `StateDictionary`, `AttentionFeatures`, `AutoregressiveModel` |
| **color** | Color and lighting | `RGB`, `Light`, `Shader` |
| **space** | Scene management | `Scene`, `Mesh`, `Triangle` |
| **physics** | Physical simulation | `Atom`, `PhotonField`, `RigidBody` |
| **heredity** | Genetic algorithms | `Gene`, `Chromosome`, `Genome` |

### Application
| Module | Purpose | Key Classes |
|--------|---------|-------------|
| **optimize** | Training, optimization | `Loss`, `Adam`, `PopulationOptimizer` |
| **render** | Ray tracing engine | `RayTracer`, `RenderParameters` |

For detailed documentation on any module, see `docs/modules/<module>.html`.

---

## Questions or Issues?

If you encounter issues or have questions about these guidelines:
1. Check module-specific documentation
2. Review existing implementations for patterns
3. Ask for clarification before creating duplicate code
