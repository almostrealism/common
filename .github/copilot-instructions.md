# Almost Realism Common - Development Guidelines

## Overview

This is a monorepo of Maven modules for the Almost Realism framework - Java libraries for
high-performance scientific computing, generative art, machine learning, and multimedia generation
with pluggable native acceleration (OpenCL, Metal, JNI).

**Before making assumptions about how something works, read the source code and documentation.**
The AR codebase is a production application. If something seems like it "doesn't work," look it
up before speculating.

## Quick Links

- **[CLAUDE.md](../CLAUDE.md)** - Full development guidelines (authoritative)
- **[Quick Reference](../docs/QUICK_REFERENCE.md)** - Condensed API cheatsheet
- **[llms.txt](../llms.txt)** - Documentation index for AI agents
- **[Documentation Portal](../docs/index.html)** - Full HTML documentation

---

## Build and Test

**Always run `mvn` from the repository root**, never from module subdirectories.

```shell
mvn package -DskipTests                    # Build all modules
mvn package -pl utils -DskipTests          # Build specific module
```

**Before declaring any task complete**, verify the full build succeeds:
```shell
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
mvn clean install -DskipTests
```

### Environment Variables

**Required** before running any Java code or tests:
```shell
export AR_HARDWARE_LIBS=/tmp/ar_libs/
```

`AR_HARDWARE_DRIVER` is best left unset to auto-detect the best available backend. Available overrides: `native` (JNI), `opencl` (CPU/GPU), `metal` (Apple Silicon), `external` (generated executable).

For large models or memory-intensive tests: `export AR_HARDWARE_MEMORY_SCALE=8` (16GB) or `9` (32GB). Default is `7` (8GB).

### Running Tests

```shell
LD_LIBRARY_PATH=Extensions mvn test \
  -DAR_HARDWARE_MEMORY_SCALE=7 \
  -DAR_HARDWARE_LIBS=Extensions \
  -DAR_TEST_PROFILE=pipeline
```

Use `-pl <module>` for a specific module, `-Dtest=ClassName` for a specific test class.

Running tests generates dynamic libraries in `Extensions/`. **Never commit generated files.**

### Test Class Requirements

All test classes **MUST** extend `TestSuiteBase`:

```java
public class MyTest extends TestSuiteBase {
    @Test
    public void testSomething() {
        // Automatically participates in grouping and depth filtering
    }

    @Test
    @TestDepth(2)
    public void expensiveTest() {
        // Skipped if AR_TEST_DEPTH < 2
    }
}
```

For long-running tests (30+ minutes), use `if (skipLongTests) return;` at the start.

---

## Absolute Rules

### Do Not Modify pom.xml Files

The AR project has a complex transitive dependency structure. **Never** add dependencies unless
100% certain they are not already available transitively. Write code assuming the dependency
exists, run `mvn compile`, and if it fails inform the developer rather than editing the pom.

### Never Reference Version Numbers

**Never** include specific version numbers in documentation or instruction files. Version numbers
change constantly. Always refer to pom.xml files as the single source of truth.

### No Code Duplication

If you have written more than 3-5 lines that are structurally similar to other code, refactor to
eliminate the duplication before proceeding. Extend and generalize existing code rather than
creating model-specific copies.

---

## Code Organization Principles

1. Never use `@SuppressWarnings`
2. Always include javadoc for newly introduced code
3. Do not include excessive comments within method implementations
4. Never use `var` for variable declarations - always use explicit types
5. Use `@link` for types referenced in javadoc
6. Ensure `@param`, `@throws`, `@return` appear last in method javadoc (in that order)
7. Ensure `@see`, `@param`, `@author` appear last in class javadoc (in that order)

### Error Handling

- Use simple, single-sentence exception messages
- For field values in exceptions, create a custom Exception class that tracks those values
  separately from the message

### Agent Memory

Each module contains an `agent-memory.md` file for development notes and patterns discovered
during work. Update these when you learn something useful about a module. Do not duplicate
information already available in javadoc.

---

## Architectural Principles

### PackedCollection is NOT a Java Array

`PackedCollection` is a **handle** to potentially GPU-resident memory. You cannot use Java
operations (`System.arraycopy`, `Arrays.copyOf`, tight `setMem` loops) on it. Use the
**Producer pattern** with `CollectionProducer`:

```java
// WRONG: CPU loop defeats GPU parallelism
for (int i = 0; i < size; i++) {
    result.setMem(i, source.toDouble(i) * 2);
}

// CORRECT: GPU-accelerated computation
CollectionProducer result = cp(source).multiply(2.0);
PackedCollection evaluated = result.evaluate();
```

### Training Loop Architecture

**`ModelOptimizer` is the ONLY class that should contain a training loop.**

All training scenarios must: create a `Dataset`, create a `ModelOptimizer`, configure it,
call `optimizer.optimize(epochs)` once, and return. Never write `for` loops over epochs or
samples outside of `ModelOptimizer`.

### Sampling Loop Architecture

**`DiffusionSampler` is the ONLY class that should contain a diffusion sampling loop.**

All diffusion generation must: create a `SamplingStrategy`, create a `DiffusionSampler`,
configure it, call `sampler.sample(...)` once, decode with `AutoEncoder`, and return.
Never write `for` loops over timesteps outside of `DiffusionSampler`.

### Process Optimization and Isolation

**Only `IsolatedProcess` is empowered to break expression embedding.** No other computation
should return null from `getValueAt()` to force isolation. If expression trees grow too large,
check that `optimize()` is being called before `get()`. Never hack `getValueAt()` - fix the
optimization chain instead.

### StateDictionary for Model Weights

All model implementations should use `StateDictionary` for weight management:
```java
StateDictionary stateDict = new StateDictionary(weightsDirectory);
PackedCollection<?> wq = stateDict.get("model.layers.0.self_attn.q_proj.weight");
```

Avoid creating separate weight container classes unless weight transformations or caching are needed.

---

## Development Workflow

### Before Starting

1. Check for existing implementations - don't reinvent the wheel
2. Identify generalization opportunities - can existing code be extended?
3. Check design documents in `ringsdesktop/docs/planning/` if implementing a planned feature
4. State your reuse plan before writing code

### Before Completing

1. Verify the full build succeeds (`mvn clean install -DskipTests`)
2. Ensure all pipeline tests pass (see `.github/workflows/analysis.yaml`)
3. Remove TODO markers for completed work
4. Mark deprecated code with `@deprecated` tags

### Debugging

Follow a systematic, evidence-based approach:
1. Identify all components involved in the failing code path
2. Run tests bottom-up from smallest to largest scope
3. Record results for each level before drawing conclusions
4. Never speculate about causes without test evidence

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
