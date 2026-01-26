# Almost Realism Common - Development Guidelines for Claude Code

---

# STOP. READ THIS FIRST.

## ABSOLUTE PREREQUISITE: USE AR-DOCS MCP BEFORE ANY ACTION

**THIS IS THE MOST IMPORTANT RULE. IT COMES BEFORE ALL OTHER RULES.**

**BEFORE you write ANY code, make ANY assumptions, or take ANY action, you MUST:**

1. **SEARCH ar-docs**: `mcp__ar-docs__search_ar_docs query:"<relevant terms>"`
2. **READ module documentation**: `mcp__ar-docs__read_ar_module module:"<module>"`
3. **CHECK quick reference**: `mcp__ar-docs__read_quick_reference`

**YOU ARE NOT ALLOWED TO:**
- Assume you know how something works
- Speculate about architecture or data flow
- Make claims about what is or isn't stored/available
- Write code based on partial understanding
- Say "the problem might be X" without first looking it up

**THE AR CODEBASE IS A PRODUCTION APPLICATION** used by real people worldwide. If something seems like it "doesn't work" or "isn't stored," YOU ARE WRONG. The application works. You need to LOOK UP how it works.

**EVERY TIME you are about to:**
- Implement a feature -> SEARCH ar-docs first
- Fix a bug -> SEARCH ar-docs first
- **Investigate a CI/test failure** -> SEARCH ar-docs first
- **Debug any issue** -> SEARCH ar-docs first
- **Run git commands to understand changes** -> SEARCH ar-docs first
- Answer a question about architecture -> SEARCH ar-docs first
- Modify existing code -> SEARCH ar-docs first
- Make ANY claim about the codebase -> SEARCH ar-docs first

**"Investigation" and "debugging" ARE actions.** Running `git log`, `git diff`, reading test files, or exploring code changes are NOT exempt from this rule. You must understand the component architecture BEFORE looking at what changed.

**If ar-docs doesn't have the information you need:**
1. READ the actual source code thoroughly
2. TRACE the data flow from end to end
3. NEVER guess or speculate

**This rule exists because:** Claude repeatedly makes assumptions, writes incorrect code, and wastes the developer's time. The ar-docs MCP contains authoritative documentation. USE IT.

See [docs/internals/ar-docs-examples.md](docs/internals/ar-docs-examples.md) for detailed wrong/right examples.

---

## MECHANICAL GATE: AR-DOCS IN FIRST RESPONSE

**Your first response to any task MUST include ar-docs search results before any other tool calls.**

This is a mechanical requirement, not a judgment call. If your first tool call is `git log`, `git diff`, `Read`, `Grep`, or any tool other than an ar-docs MCP tool, you are violating this rule.

**Correct first response pattern:**
```
1. mcp__ar-docs__search_ar_docs query:"<component/test/feature name>"
2. mcp__ar-docs__read_ar_module module:"<relevant module>"
3. THEN git commands, file reads, etc.
```

**Why this is mechanical:** Judgment-based rules ("search ar-docs when relevant") fail because Claude always thinks the current task is an exception. Making it mechanical removes ambiguity.

---

## DEBUGGING PROTOCOL: AR-DOCS FIRST, THEN INVESTIGATE

**When a user reports a CI failure, test failure, or bug:**

1. **FIRST**: Extract component/test names from the error message
2. **SECOND**: Search ar-docs for those components: `mcp__ar-docs__search_ar_docs query:"<component name>"`
3. **THIRD**: Read the relevant module documentation: `mcp__ar-docs__read_ar_module module:"<module>"`
4. **FOURTH**: NOW you may run git commands, read files, investigate changes

**Example - CI failure in OobleckComponentTests:**
```
WRONG ORDER:
1. git log to see what changed  <- VIOLATION
2. git diff to see the changes  <- VIOLATION
3. Read the test file           <- VIOLATION

CORRECT ORDER:
1. mcp__ar-docs__search_ar_docs query:"Oobleck decoder block"
2. mcp__ar-docs__read_ar_module module:"ml"
3. NOW: git log, git diff, Read test file
```

**The reason for this order:** You cannot effectively investigate changes if you don't understand what the component is supposed to do. Understanding architecture first prevents wasted effort chasing red herrings.

---

## CRITICAL: DO NOT COMMIT CODE

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** use `git commit` commands
- Claude does not have the ability to create valid commits
- You can only **stage changes** using `git add`
- The human developer must review and commit all changes themselves

**Why this matters:** Claude cannot properly sign commits or verify the full context of changes. The human developer needs to review staged changes, write appropriate commit messages, and take responsibility for what goes into the repository history.

**What you CAN do:** `git add <file>`, `git status`, `git diff`, `git reset <file>`

**What you MUST NOT do:** `git commit` (any form), `git commit -m "..."`, `git commit --amend`

---

## CRITICAL: DO NOT MODIFY POM.XML FILES

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** add dependencies to pom.xml files unless 100% certain the dependency is not already available transitively
- **NEVER** assume you understand the module dependency graph - it is complex and you WILL get it wrong
- **IF IN DOUBT, DO NOT TOUCH THE POM FILE** - just use the classes and let compilation fail if the dependency is truly missing
- The human developer will fix any missing dependencies - this is NOT your job

**Why this matters:** The AR project has a complex transitive dependency structure. Adding a dependency that already exists transitively creates redundant declarations, can cause version conflicts, and wastes time.

**What to do instead:**
1. Write your code assuming the dependency exists
2. Run `mvn compile` to verify
3. If compilation succeeds, the dependency was already available transitively
4. If compilation fails with "package does not exist", inform the user - DO NOT modify the pom.xml yourself

**The ONLY exception:** User explicitly instructs you to add a specific dependency.

---

## CRITICAL: NEVER REFERENCE VERSION NUMBERS

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** include specific version numbers anywhere in CLAUDE.md files
- **NEVER** mention library versions (e.g., "JavaFX 21", "gRPC 1.53.0")
- **NEVER** mention project versions (e.g., "version 0.72")
- Version numbers change constantly and become stale immediately
- Always refer to pom.xml files as the single source of truth for versions

---

## CRITICAL: USE MCP TEST RUNNER FOR ALL TESTS

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

- **NEVER** use `Bash` tool with `mvn test` commands to run tests
- **ALWAYS** use the `mcp__ar-test-runner__start_test_run` MCP tool for running tests
- The MCP test runner automatically handles environment variables, async execution, and structured failure reporting

**Example usage:**
```
mcp__ar-test-runner__start_test_run
  module: "ml"
  profile: "pipeline"
  timeout_minutes: 10
```

**Why this matters:** The MCP test runner is purpose-built for this codebase. Using Bash for tests bypasses proper environment setup, loses structured output, and ignores specialized tooling.

See [docs/internals/test-examples.md](docs/internals/test-examples.md) for full tool reference and parameters.

---

## CRITICAL: TEST CLASS REQUIREMENTS

**THIS IS AN ABSOLUTE RULE WITH NO EXCEPTIONS.**

All test classes **MUST** extend `TestSuiteBase`:

```java
public class MyTest extends TestSuiteBase {
    @Test
    public void testSomething() {
        // Test automatically participates in grouping and depth filtering
    }

    @Test
    @TestDepth(2)
    public void expensiveTest() {
        // Automatically skipped if AR_TEST_DEPTH < 2
    }
}
```

**What TestSuiteBase provides automatically:**
- Test grouping (hash-based distribution across CI runners)
- `@TestDepth` annotation support
- All TestFeatures utilities (assertions, kernel testing, etc.)

**For long-running tests (30+ minutes):**
Use `skipLongTests` guard in addition to extending TestSuiteBase:
```java
if (skipLongTests) return;  // Respects AR_LONG_TESTS env var
```

See [docs/internals/test-examples.md](docs/internals/test-examples.md) for wrong patterns to avoid.

---

## Quick Links

- **[Quick Reference](docs/QUICK_REFERENCE.md)** - Condensed API cheatsheet
- **[llms.txt](llms.txt)** - Documentation index for AI agents
- **[Documentation Portal](docs/index.html)** - Full HTML documentation

---

## Setup Instructions

**Required environment variables** before running Java code:
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native
```

The directory will be created automatically if it doesn't exist.

**For Maven tests**, always prefix test commands with the environment variables:
```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl <module>
```

### What These Variables Do

- **`AR_HARDWARE_LIBS`**: Directory where hardware acceleration libraries (JNI .so files, OpenCL kernels, etc.) will be generated and loaded from
- **`AR_HARDWARE_DRIVER`**: Hardware backend to use:
  - `native`: Standard JNI operations with runtime-generated native code (default)
  - `opencl`: OpenCL acceleration (CPU/GPU)
  - `metal`: Metal GPU acceleration (Apple Silicon)
  - `external`: Generated executable approach

### Common Issues

Forgetting to set these variables will result in:
- `NoClassDefFoundError: Could not initialize class org.almostrealism.collect.PackedCollection`
- Runtime errors when trying to compile operations
- Missing library errors
- Failures during model inference

**Always verify** these are set before running:
```bash
echo $AR_HARDWARE_LIBS
echo $AR_HARDWARE_DRIVER
```

### Memory Configuration

For large models or tests that require more memory than the default 8GB limit:

```bash
export AR_HARDWARE_MEMORY_SCALE=7   # 8GB (default)
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
4. Never use `var` for variable declarations - always use explicit types

---

## CRITICAL: Training Loop Architecture

**THIS IS A SACRED ARCHITECTURAL PRINCIPLE. VIOLATING IT WILL RESULT IN WASTED EFFORT.**

### The Golden Rule

**`ModelOptimizer` is the ONLY class that should contain a training loop.** Full stop.

**YOU DO NOT OWN THE TRAINING LOOP.**

All training scenarios (supervised learning, diffusion, reinforcement learning, fine-tuning, etc.) must:

1. Create a `Dataset` implementation that yields appropriate `(input, target)` pairs
2. Create a `ModelOptimizer`
3. Configure it (loss function, log frequency, etc.)
4. Call `optimizer.optimize(epochs)` ONCE
5. Return

**There is NO scenario where your class should contain a `for` loop that iterates over epochs or samples.** If you have a loop, you are wrong. Delete it.

**If you want progress reporting**, use `ModelOptimizer.setLogFrequency()` and `ModelOptimizer.setReceptor()`. Do NOT create custom progress classes.

### Before Implementing ANY Training-Related Code

**You MUST:**
1. Run `mcp__ar-docs__search_ar_docs query:"ModelOptimizer training"`
2. Run `mcp__ar-docs__read_ar_module module:"optimize"`
3. Read the relevant design document (e.g., `ringsdesktop/docs/planning/10-MODEL-FINE-TUNING.md`)
4. Explicitly state: "According to the design document, I should use `ModelOptimizer` for the training loop and create a custom `Dataset` for [X]"

### Duplication Red Flags

**STOP IMMEDIATELY if you find yourself writing:**

- `for (int epoch = 0; epoch < ...)` outside of `ModelOptimizer` - **DELETE IT**
- `for (...) { optimizer.optimize(1); }` - wrapping optimize in a loop - **DELETE IT**
- `model.forward(...)` followed by `model.backward(...)` outside of `ModelOptimizer`
- `lossFunction.apply(...)` or `lossGradient.evaluate(...)` outside of `ModelOptimizer`
- Custom progress logging that duplicates `ModelOptimizer.setLogFrequency()`
- Custom progress/callback classes (e.g., `TrainingProgress`) - **DELETE IT**

See [docs/internals/training-loop-examples.md](docs/internals/training-loop-examples.md) for correct and wrong code patterns.

---

## CRITICAL: Sampling Loop Architecture

**THIS IS A SACRED ARCHITECTURAL PRINCIPLE. VIOLATING IT WILL RESULT IN WASTED EFFORT.**

### The Golden Rule

**`DiffusionSampler` is the ONLY class that should contain a diffusion sampling loop.** Full stop.

**YOU DO NOT OWN THE SAMPLING LOOP.**

All diffusion generation scenarios (text-conditional, unconditional, img2img, inpainting, etc.) must:

1. Create a `SamplingStrategy` (DDIM, ping-pong, DDPM, etc.)
2. Create a `DiffusionSampler`
3. Configure it (inference steps, progress callback, etc.)
4. Call `sampler.sample(seed, conditioning...)` or `sampler.sampleFrom(latent, strength, seed, conditioning...)` ONCE
5. Decode the resulting latent with `AutoEncoder`
6. Return

**There is NO scenario where your generator class should contain a `for` loop that iterates over timesteps.** If you have a loop, you are wrong. Delete it.

### Before Implementing ANY Diffusion Generation Code

**You MUST:**
1. Run `mcp__ar-docs__search_ar_docs query:"DiffusionSampler sampling"`
2. Explicitly state: "According to the architecture, I should use `DiffusionSampler` for the sampling loop with a `[DDIM/PingPong/etc]SamplingStrategy`"

### Duplication Red Flags

**STOP IMMEDIATELY if you find yourself writing:**

- `for (int step = 0; step < numSteps; step++)` outside of `DiffusionSampler` - **DELETE IT**
- `for (...) { sampler.sample(...); }` - wrapping sample in a loop - **DELETE IT**
- `model.forward(x, t, ...)` in a loop outside of `DiffusionSampler`
- Custom timestep schedule computation (e.g., `fillSigmas()`) - use `SamplingStrategy`
- Custom progress logging that duplicates `DiffusionSampler.setProgressCallback()`

See [docs/internals/sampling-loop-examples.md](docs/internals/sampling-loop-examples.md) for correct and wrong code patterns.

---

## CRITICAL: PackedCollection is NOT a Java Array

**THIS IS A FUNDAMENTAL ARCHITECTURAL PRINCIPLE. MISUNDERSTANDING IT SHOWS YOU DON'T KNOW HOW A GPU WORKS.**

### The Golden Rule

**`PackedCollection` is a HANDLE to potentially GPU-resident memory.** It is NOT a Java array. You CANNOT use Java operations on `PackedCollection` data.

### Mandatory Mental Model Check

**Before writing ANY code that manipulates `PackedCollection`, ask yourself:**

> "Where does this data physically live?"

- If you're thinking "it's just a Java object" -> **STOP. You are wrong.**
- `PackedCollection` is a *handle* to memory that may be on a completely different device (GPU, external accelerator, native memory)
- Operations on `PackedCollection` must go through the AR framework, not Java primitives

### Mandatory ar-docs MCP Consultation

**Before writing code that creates, copies, or transforms `PackedCollection` objects, you MUST:**

1. Run `mcp__ar-docs__search_ar_docs query:"PackedCollection operations"`
2. Run `mcp__ar-docs__read_ar_module module:"collect"`
3. Look for existing methods like `copy()`, `reshape()`, `traverse()`, etc.

### RED FLAG PATTERNS - STOP IMMEDIATELY

**STOP IMMEDIATELY if you find yourself writing:**

- `System.arraycopy` anywhere near `PackedCollection` -> **DELETE IT**
- `Arrays.copyOf` with `PackedCollection` -> **DELETE IT**
- `for` loops that call `setMem(i, ...)` in a tight loop -> **DELETE IT** (defeats GPU parallelism)
- Direct `.toArray()` followed by manipulation followed by `.setMem()` -> **DELETE IT** (round-trip through CPU)

**These patterns will either cause silent data corruption, runtime errors, or destroy performance.**

### What You MUST Do Instead

Use the **Producer pattern** with `CollectionProducer`:

```java
// WRONG: CPU loop defeats GPU parallelism
for (int i = 0; i < size; i++) {
    result.setMem(i, source.toDouble(i) * 2);  // Round-trip per element!
}

// CORRECT: GPU-accelerated computation
CollectionProducer result = cp(source).multiply(2.0);
PackedCollection evaluated = result.evaluate();  // Runs on GPU
```

### Common Operations - Quick Reference

| Task | WRONG | CORRECT |
|------|-------|---------|
| Multiply by scalar | `for (i) result.setMem(i, x.toDouble(i) * 2)` | `cp(x).multiply(2.0).evaluate()` |
| Add two collections | `for (i) result.setMem(i, a.toDouble(i) + b.toDouble(i))` | `cp(a).add(cp(b)).evaluate()` |
| Clamp values | `for (i) result.setMem(i, Math.max(min, x.toDouble(i)))` | `max(cp(x), c(min)).evaluate()` |
| Fill with noise | `for (i) result.setMem(i, random.nextGaussian())` | `new PackedCollection(shape).randnFill(random)` |

See [docs/internals/packed-collection-examples.md](docs/internals/packed-collection-examples.md) for more detailed examples.

---

## CRITICAL: Process Optimization and Isolation Architecture

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

---

## StateDictionary for Model Weights

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

---

## ABSOLUTELY NO CODE DUPLICATION - THIS IS NON-NEGOTIABLE

If you find yourself copying and pasting code, or writing nearly-identical logic multiple times, **STOP IMMEDIATELY**. This is unacceptable and will never be tolerated.

**The rule**: If you have written more than 3-5 lines that are structurally similar to other code, you MUST refactor to eliminate the duplication BEFORE proceeding. Use helper methods, generics, factory functions, lambdas, or any appropriate abstraction.

**No exceptions. No excuses. Refactor first, then proceed.**

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
5. **Check design documents**: If implementing a planned feature, read the design doc in `ringsdesktop/docs/planning/`
6. **State your reuse plan**: Before writing code, explicitly state: "I will reuse [X] rather than reimplementing it"

### Design Document Verification Gate

**For any feature with a design document:**

1. Read the relevant design document section BEFORE writing code
2. Explicitly state: "According to the design document, I should use [X] for [Y]"
3. If deviating from design, document why and update the design document FIRST
4. Search for existing implementations: `Grep pattern:"class.*implements|extends.*<RelevantInterface>"`

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

**Only after this command succeeds** should you report that the build is working.

### Considering Something Completed

Sometimes you may encounter a summary of earlier work. That summary may indicate that there are other things to do.
If this happens, that means YOU ARE EXPECTED TO DO THOSE THINGS.

Working on something for a while, writing some commentary about completing more of it later, and then reading the
commentary back to yourself is NOT a condition for suspending your work.

Before you start working, REPEAT this principle to yourself. If summarization of earlier tasks is ever required,
REPEAT THIS PRINCIPLE IN THE SUMMARY

### Context Preservation Protocol

**When continuing a session or after context summarization:**

1. **Re-read the design document** for the feature being implemented
2. **Search for existing implementations** before writing new code:
   - `Grep pattern:"ModelOptimizer"` for training-related code
   - `Grep pattern:"extends CellularLayer"` for layer implementations
   - `mcp__ar-docs__search_ar_docs query:"<feature>"` for framework patterns
3. **Explicitly state your reuse plan**: "I will reuse [X] rather than reimplementing it"
4. **Check the summary for incomplete tasks** - if the summary mentions "remaining work" or "TODO", that work is YOUR responsibility

**This protocol exists because:** Design decisions made early in a session are often lost during context summarization. The design document is the authoritative source - always consult it, especially after a session break.

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
Model transformer = new Model(shape(dim));

for (int i = 0; i < layerCount; i++) {
    PackedCollection<?> wq = getLayerWeight(stateDict, i, "self_attn.q_proj.weight");
    // ...

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

### Test Organization

- **Unit tests**: Test individual components in isolation
- **Integration tests**: Test component interactions
- **Synthetic tests**: Validate architecture with random weights
- **Validation tests**: Compare against reference implementations

### Test Output Logging

**IMPORTANT**: Use `Console` and `OutputFeatures` (from `ar-io` module) to log test output to files for later review.

```java
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.OutputFeatures;

public class MyTest implements ConsoleFeatures {
    @Test
    public void myTest() throws Exception {
        String logFile = "/workspace/project/common/<module>/test_output/my_test_results.txt";
        Console.root().addListener(OutputFeatures.fileOutput(logFile));

        log("=== My Test ===");
        log("Result: " + someValue);
    }
}
```

**Best Practices**:
- Create test_output directories in each module for test logs
- Use descriptive file names: `<TestName>_results.txt`
- Add file logging setup at the START of each test method
- Use `log()` instead of `System.err.println()` for important results

---

## Module Overview

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
