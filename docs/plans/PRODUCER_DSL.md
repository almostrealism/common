# Producer DSL: A Restricted Language for CollectionProducer Computation Graphs

## Status: Recommendation (Research Phase)

**Date:** 2026-03-28
**Branch:** feature/producer-dsl

---

## Table of Contents

1. [Problem Statement](#problem-statement)
2. [How Computation Is Currently Expressed](#how-computation-is-currently-expressed)
3. [Vocabulary of Operations](#vocabulary-of-operations)
4. [Survey of Approaches](#survey-of-approaches)
   - A. Custom Textual DSL
   - B. JSON/YAML Computation Graph
   - C. Restricted JVM Language Subset
   - D. Annotation-Driven Java
   - E. Visual/Graph Editor Format
   - F. Typed Functional DSL (Kotlin Internal DSL)
5. [Evaluation Matrix](#evaluation-matrix)
6. [Concrete Syntax Examples (Top 3)](#concrete-syntax-examples)
7. [Recommendation](#recommendation)
8. [Implementation Roadmap](#implementation-roadmap)
9. [Risks and Open Questions](#risks-and-open-questions)

---

## Problem Statement

This project uses Java as an orchestration language to build computation graphs — directed
acyclic graphs (DAGs) of `CollectionProducer` nodes — that get compiled to native GPU/CPU
code (Metal, OpenCL) for execution. The **correct pattern** looks like this:

```java
// CORRECT: Pure Producer composition — no host-side math
CollectionProducer out1 = x1.multiply(cos).subtract(x2.multiply(sin));
CollectionProducer out2 = x2.multiply(cos).add(x1.multiply(sin));
```

The **recurring failure mode** is that agents (and sometimes humans) break this pattern by:

- Calling `.evaluate()` mid-graph to extract values to the host
- Using `.toDouble()` in loops to do host-side math
- Writing Java `for` loops that iterate over tensor elements
- Using `System.arraycopy`, `Arrays.copyOf`, or tight `setMem` loops
- Mixing host-side computation with Producer graph construction

These violations are difficult to catch in code review because they are syntactically valid
Java. The `CodePolicyViolationDetector` catches some cases in CI, but it operates on compiled
bytecode and cannot prevent all escape hatches.

**The core insight:** If we define computation layers in a language that can *only* express
`CollectionProducer` compositions, escape hatches become structurally impossible. The language
is the sandbox.

---

## How Computation Is Currently Expressed

### The Producer Pattern

All computation in the Almost Realism framework follows a deferred-execution pattern:

1. **Build a graph** of `CollectionProducer` nodes via method chaining
2. **Compile** the graph to native kernels
3. **Execute** the kernels on hardware

The key interfaces:

| Type | Role |
|------|------|
| `CollectionProducer<T>` | A node in the computation graph. Supports arithmetic (`.add()`, `.multiply()`, `.subtract()`, `.divide()`), shape ops (`.reshape()`, `.subset()`, `.repeat()`, `.enumerate()`, `.permute()`), reductions (`.sum()`, `.mean()`, `.max()`), transcendentals (`.exp()`, `.log()`, `.sqrt()`, `.pow()`), and comparisons (`.greaterThan()`). |
| `PackedCollection` | A handle to potentially GPU-resident memory. NOT a Java array. |
| `Block` | A composable neural network unit with input/output shapes and forward/backward cells. |
| `CellularLayer` | A Block created from a `Factor<PackedCollection>` (a function from Producer to Producer). |
| `Model` | Top-level container that chains Blocks sequentially. |

### Concrete Example: Transformer Layer (from Qwen3.java)

```java
// Build transformer stack: 36 layers
for (int i = 0; i < config.layerCount; i++) {
    String prefix = String.format("model.layers.%d", i);

    // Load weights from StateDictionary
    PackedCollection layerRmsAtt = stateDict.get(prefix + ".input_layernorm.weight");
    PackedCollection layerWq = stateDict.get(prefix + ".self_attn.q_proj.weight");
    PackedCollection layerWk = stateDict.get(prefix + ".self_attn.k_proj.weight");
    // ... more weights ...

    // Compose a complete transformer layer from primitives
    transformer.add(transformer(
        config.headCount, config.kvHeadCount,
        layerRmsAtt,
        layerWk, layerWv, layerWq, layerWo,
        layerBk, layerBv, layerBq,
        layerQkNormQ, layerQkNormK,
        freqCis,
        layerRmsFfn,
        layerW1, layerW2, layerW3,
        p(position), 1e-6,
        requirements));
}
```

### Concrete Example: Attention Block (from AttentionFeatures.java)

The `attention()` method is ~120 lines of pure Producer composition:

```java
SequentialBlock attention = new SequentialBlock(inputShape);
attention.add(rmsnorm(inputShape, rmsAttWeight, epsilon));

SequentialBlock keys = attention.branch();
SequentialBlock values = attention.branch();

// KEYS: dense -> QK-Norm -> reshape -> RoPE -> reshape -> GQA expand -> cache
keys.add(dense(wk));
keys.add(reshapeToSplitHalfRope(kvDim, kvHeads, headSize));
keys.add(ropeRotation(kvHeadShapeComplex, freqCis, position));
keys.add(reshapeFromSplitHalfRope(kvHeads, headSize));
keys.andThen(into(keyCache, position));

// VALUES: dense -> GQA expand -> cache
values.add(dense(wv));
values.andThen(into(valueCache, position));

// QUERY: dense -> QK-Norm -> reshape -> RoPE -> reshape -> attnKeys -> mask -> softmax -> attnValues -> dense
attention.add(dense(wq));
attention.add(reshapeToSplitHalfRope(dim, heads, headSize));
attention.add(ropeRotation(headShapeComplex, freqCis, position));
attention.add(reshapeFromSplitHalfRope(heads, headSize));
attention.add(attentionKeys(headShape, p(keyCache)));
attention.add(causalMask(...));
attention.add(softmax(attentionShape));
attention.add(attentionValues(attentionShape, p(valueCache)));
attention.add(dense(wo));
```

### Concrete Example: SwiGLU Feed-Forward (from AttentionFeatures.java)

```java
SequentialBlock feedForward = new SequentialBlock(shape);
feedForward.add(rmsnorm(shape, normWeights, normBiases, epsilon));

SequentialBlock hidden = new SequentialBlock(shape);
hidden.add(dense(w1));
hidden.add(silu());

feedForward.product(dense(w3), hidden);  // element-wise multiply
feedForward.add(dense(w2));
```

### Concrete Example: RoPE Rotation (from RotationFeatures.java)

```java
// Inside the ropeRotation layer's operator lambda:
CollectionProducer cos = c(shape(totalHeadFreq), p(weights), cosIdx);
CollectionProducer sin = c(shape(totalHeadFreq), p(weights), sinIdx);
CollectionProducer x1 = c(shape(totalHeadFreq), input, p(x1IndexMap));
CollectionProducer x2 = c(shape(totalHeadFreq), input, p(x2IndexMap));

CollectionProducer out1 = x1.multiply(cos).subtract(x2.multiply(sin));
CollectionProducer out2 = x2.multiply(cos).add(x1.multiply(sin));
```

---

## Vocabulary of Operations

Based on analysis of `LayerFeatures`, `AttentionFeatures`, `RotationFeatures`,
`CollectionProducer`, and `CollectionFeatures`, the complete vocabulary is:

### Primitive Layer Operations (return Block or CellularLayer)

| Operation | Signature Pattern | Description |
|-----------|------------------|-------------|
| `dense` | `(weights) -> Block` | Matrix multiply (+ optional bias) |
| `rmsnorm` | `(weights, epsilon) -> Block` | RMS normalization |
| `norm` | `(weights, biases) -> Block` | Layer normalization |
| `softmax` | `(shape) -> Block` | Softmax activation |
| `relu` | `() -> Block` | ReLU activation |
| `silu` | `() -> Block` | SiLU / Swish activation |
| `gelu` | `() -> Block` | GELU activation |
| `sigmoid` | `() -> Block` | Sigmoid activation |
| `tanh` | `() -> Block` | Tanh activation |
| `embedding` | `(table) -> Block` | Embedding lookup |
| `convolution1d` | `(channels, kernel, ...) -> Block` | 1D convolution |
| `convolution2d` | `(channels, kernel, ...) -> Block` | 2D convolution |
| `pool2d` | `(size) -> Block` | 2D max pooling |
| `ropeRotation` | `(shape, weights, position) -> Block` | Rotary position embedding |

### Composition Operations (combine Blocks)

| Operation | Description |
|-----------|-------------|
| `add(block)` / `accum` | Residual connection (elementwise add) |
| `product(block, block)` | Elementwise multiply of two branches |
| `branch()` | Create parallel branch from current output |
| `andThen(block)` | Sequential chaining |
| `concat(axis, ...)` | Concatenation along axis |
| `split(shape, axis)` | Split along axis |
| `residual(block)` | Explicit residual wrapper |

### Shape Operations (on CollectionProducer)

| Operation | Description |
|-----------|-------------|
| `reshape(shape)` | Reshape without data movement |
| `subset(shape, offsets...)` | Extract sub-tensor |
| `repeat(axis, count)` | Repeat along axis |
| `enumerate(axis, len)` | Enumerate along axis |
| `permute(order...)` | Permute dimensions |
| `traverse(depth)` | Set traversal axis |
| `pad(shape, offsets...)` | Zero-pad tensor |

### Element-wise Arithmetic (on CollectionProducer)

| Operation | Description |
|-----------|-------------|
| `add`, `subtract`, `multiply`, `divide` | Basic arithmetic |
| `pow`, `sqrt`, `exp`, `log` | Transcendentals |
| `minus` (negation) | Unary minus |
| `abs`, `floor`, `ceil` | Rounding |
| `sin`, `cos` | Trigonometric |
| `sum`, `mean`, `max`, `min` | Reductions |
| `greaterThan`, `lessThan` | Conditional select |

### Composite High-Level Operations

| Operation | Composes |
|-----------|----------|
| `attention(...)` | rmsnorm + dense + RoPE + attentionKeys + causalMask + softmax + attentionValues + dense |
| `transformer(...)` | attention + feedForward (with residuals) |
| `feedForward(...)` | rmsnorm + dense + silu + product + dense (SwiGLU) |
| `linearAttention(...)` | conv2d + reshape + softmax + context + dense |

---

## Survey of Approaches

### A. Custom Textual DSL

A purpose-built mini-language with syntax designed for this domain.

```
layer attention(
    input: [1, dim],
    q_weight: weight, k_weight: weight, v_weight: weight, o_weight: weight,
    freq_cis: weight, position: scalar
) -> [1, dim] {
    normed = rmsnorm(input, rms_weight, 1e-6)

    q = dense(normed, q_weight)
    k = dense(normed, k_weight)
    v = dense(normed, v_weight)

    q_rope = rope_rotation(reshape(q, [heads, head_size/2, 2]), freq_cis, position)
    k_rope = rope_rotation(reshape(k, [kv_heads, head_size/2, 2]), freq_cis, position)

    scores = attention_keys(reshape(q_rope, [heads, head_size]), key_cache)
    masked = scores + causal_mask(position, seq_len)
    weights = softmax(masked)

    output = attention_values(weights, value_cache)
    return dense(output, o_weight)
}
```

**Strengths:**
- Syntax can be designed to make the correct pattern natural and violations impossible
- No `evaluate()`, no `toDouble()`, no Java loops — they simply don't exist in the grammar
- Shape annotations make tensor dimensions visible at every step
- Can be parsed and validated with standard tools (ANTLR, JavaCC)
- Error messages can reference DSL line numbers and operation names

**Weaknesses:**
- Requires building a parser, type checker, and code generator from scratch
- New syntax to learn (though deliberately simple)
- IDE support (syntax highlighting, autocomplete) requires custom tooling
- Debugging is indirect — errors map back to generated Java

### B. JSON/YAML Computation Graph

A data format describing the DAG as a sequence of operations.

```yaml
name: attention
inputs:
  - { name: input, shape: [1, dim] }
  - { name: q_weight, type: weight }
  - { name: k_weight, type: weight }
  - { name: freq_cis, type: weight }
  - { name: position, type: scalar }
output_shape: [1, dim]

graph:
  - { id: normed, op: rmsnorm, args: [input, rms_weight], params: { epsilon: 1e-6 } }
  - { id: q, op: dense, args: [normed, q_weight] }
  - { id: k, op: dense, args: [normed, k_weight] }
  - { id: v, op: dense, args: [normed, v_weight] }
  - { id: q_reshaped, op: reshape, args: [q], params: { shape: [heads, "head_size/2", 2] } }
  - { id: q_rope, op: rope_rotation, args: [q_reshaped, freq_cis, position] }
  - { id: scores, op: attention_keys, args: [q_rope, key_cache] }
  - { id: masked, op: add, args: [scores, { op: causal_mask, args: [position, seq_len] }] }
  - { id: weights, op: softmax, args: [masked] }
  - { id: attended, op: attention_values, args: [weights, value_cache] }
  - { id: output, op: dense, args: [attended, o_weight] }
```

**Strengths:**
- No parser needed — JSON/YAML parsing is built into Java
- Machine-readable and machine-writable (good for programmatic graph construction)
- Trivially serializable, version-controllable, diffable
- No escape hatches by construction — the format only supports declared operations

**Weaknesses:**
- Extremely verbose — the attention example above is harder to read than the Java
- No natural support for branching/parallel paths (branches become awkward)
- Shape expressions like `head_size / 2` require an embedded expression language anyway
- No type checking at authoring time — all validation happens at load time
- Terrible authoring experience — autocomplete and validation require custom editor plugins
- Agent-unfriendly: LLMs produce better structured text than structured data formats
- Debugging is painful — "error at graph node index 7" is not helpful

### C. Restricted Subset of an Existing JVM Language (Groovy)

Use Groovy (which already has a module in `extern/ml-script/`) with a sandboxed
`CompilerConfiguration` that restricts available classes and methods.

```groovy
@ProducerGraph
def attention(input, q_weight, k_weight, v_weight, o_weight, freq_cis, position) {
    def normed = rmsnorm(input, rms_weight, 1e-6)

    def q = dense(normed, q_weight)
    def k = dense(normed, k_weight)
    def v = dense(normed, v_weight)

    def q_rope = ropeRotation(reshape(q, [heads, headSize/2, 2]), freq_cis, position)
    def k_rope = ropeRotation(reshape(k, [kvHeads, headSize/2, 2]), freq_cis, position)

    def scores = attentionKeys(reshape(q_rope, [heads, headSize]), keyCache)
    def masked = scores + causalMask(position, seqLen)
    def weights = softmax(masked)

    def output = attentionValues(weights, valueCache)
    return dense(output, o_weight)
}
```

**Strengths:**
- Groovy already runs on the JVM and integrates seamlessly with Java
- Rich expression syntax — operator overloading, closures, string interpolation
- Existing IDE support (IntelliJ, VS Code)
- `CompilerConfiguration` + `SecureASTCustomizer` can whitelist/blacklist classes and methods
- Hot-reloadable at runtime via `GroovyShell`
- `extern/ml-script/` module already exists (though may need updates)
- Familiar syntax for anyone who knows Java, Python, or Ruby

**Weaknesses:**
- Security sandbox is leaky. `SecureASTCustomizer` is a blacklist, not a whitelist of the
  execution model. An agent can call `getClass()`, use reflection, or invoke methods on
  objects passed into the script. Groovy's `@CompileStatic` + AST transforms help but
  don't fully close the gap.
- Type safety is weak — Groovy is dynamically typed by default. `@CompileStatic` adds
  static typing but then you lose much of the syntactic sugar.
- Shape errors are runtime errors, not authoring-time errors
- Groovy compilation adds startup overhead
- The agent "escape hatch" problem is mitigated but not eliminated: Groovy is a general-
  purpose language, and restricting it enough to be truly safe requires significant effort

### D. Annotation-Driven Java

Use Java itself with annotation processing to enforce the Producer pattern at compile time.

```java
@ProducerGraph
public interface AttentionLayer extends LayerFeatures {

    @Layer(input = "[1, dim]", output = "[1, dim]")
    default Block attention(
            @Weight PackedCollection qWeight,
            @Weight PackedCollection kWeight,
            @Weight PackedCollection vWeight,
            @Weight PackedCollection oWeight,
            @Weight PackedCollection freqCis,
            @Input Producer<PackedCollection> position) {

        // Only CollectionProducer method calls allowed here.
        // Annotation processor rejects: .evaluate(), .toDouble(), System.arraycopy,
        // for loops, new array creation, etc.

        return inputShape -> {
            SequentialBlock attn = new SequentialBlock(inputShape);
            attn.add(rmsnorm(inputShape, rmsWeight, 1e-6));
            // ... same Java as today ...
            return attn;
        };
    }
}
```

**Strengths:**
- No new language — developers and agents write Java they already know
- Full IDE support: autocomplete, refactoring, navigation, debugging
- Compile-time checking via annotation processor
- Gradual adoption — annotate new code, leave old code unchanged
- Type-safe: Java's type system + annotation processor = strong guarantees

**Weaknesses:**
- Annotation processors operate on AST, not bytecode — detecting all escape hatches
  (e.g., calling a helper method that internally calls `.evaluate()`) requires whole-program
  analysis, which annotation processors cannot do
- False positives: legitimate setup code (computing index maps, creating PackedCollections
  for constants) may trigger violations
- The boundary between "setup code" and "graph code" is blurry. In `ropeRotation()`,
  the precomputation of `cosRelativeIndexMap` uses `setMem` loops — is that an escape hatch
  or legitimate setup?
- Does not address the fundamental problem: agents write Java, and Java allows everything.
  The annotation processor is another `CodePolicyViolationDetector` — a better one, but
  still a post-hoc check
- Complex to implement correctly: JSR 269 annotation processing is notoriously tricky

### E. Visual/Graph Editor Format

A node-graph representation (like Unreal Blueprints or shader graphs) serialized to a file.

**Strengths:**
- Intuitive for visual thinkers
- Impossible to express non-graph operations
- Great for understanding data flow

**Weaknesses:**
- Agents cannot author or edit visual graphs (they work with text)
- Requires building a GUI editor — enormous engineering effort
- Version control is painful (binary or verbose JSON diffs)
- Scaling: a 36-layer transformer with 20+ ops per layer = 700+ nodes
- Not viable as a primary authoring format for this project

**Verdict:** Rejected. The primary consumers are coding agents, not visual designers.

### F. Typed Functional DSL (Kotlin Internal DSL)

Use Kotlin's type system and DSL builder pattern to create a type-safe internal DSL
that compiles to standard JVM bytecode but restricts what operations are available.

```kotlin
val attention = producerGraph("attention") {
    val input by input(shape(1, dim))
    val qWeight by weight()
    val kWeight by weight()
    val vWeight by weight()
    val oWeight by weight()
    val freqCis by weight()
    val position by scalar()

    val normed = rmsnorm(input, rmsWeight, epsilon = 1e-6)

    val q = dense(normed, qWeight)
    val k = dense(normed, kWeight)
    val v = dense(normed, vWeight)

    val qRope = ropeRotation(
        reshape(q, shape(heads, headSize / 2, 2)),
        freqCis, position
    )
    val kRope = ropeRotation(
        reshape(k, shape(kvHeads, headSize / 2, 2)),
        freqCis, position
    )

    val scores = attentionKeys(reshape(qRope, shape(heads, headSize)), keyCache)
    val masked = scores + causalMask(position, seqLen)
    val weights = softmax(masked)

    val attended = attentionValues(weights, valueCache)
    output(dense(attended, oWeight))
}
```

**Strengths:**
- Kotlin's type system can encode tensor shapes (to a degree)
- Extension functions + lambda-with-receiver = clean DSL syntax
- Full IDE support in IntelliJ (Kotlin is first-class there)
- Compiles to JVM bytecode — seamless Java interop
- The receiver type controls what methods are available inside the builder lambda,
  naturally restricting the vocabulary

**Weaknesses:**
- Kotlin is an additional language dependency for the project
- Kotlin's type system cannot fully encode dependent types (shape arithmetic like
  `headSize / 2` cannot be checked at compile time)
- An agent can still break out by calling Java methods on objects — the receiver
  restriction is advisory, not enforced
- Build complexity: Kotlin compiler plugin or kapt for additional validation
- Still a general-purpose language at its core

---

## Evaluation Matrix

| Criterion | A. Custom DSL | B. JSON/YAML | C. Groovy | D. Annotations | E. Visual | F. Kotlin |
|-----------|:---:|:---:|:---:|:---:|:---:|:---:|
| **Expressiveness** | High | Medium | High | High | Medium | High |
| **Type Safety (shapes)** | High (custom checker) | Low (runtime only) | Low (dynamic) | Medium (AST) | N/A | Medium (partial) |
| **Integration with Java** | Medium (codegen) | High (data load) | High (JVM) | High (native) | Low | High (JVM) |
| **Error Reporting** | High (custom) | Low (runtime) | Medium | Medium | N/A | Medium |
| **Debuggability** | Medium (source maps) | Low | High (JVM debugger) | High (Java debugger) | Low | High (JVM debugger) |
| **Learnability** | Medium (new syntax) | High (data format) | High (Java-like) | High (just Java) | High (visual) | Medium (new lang) |
| **Tooling Effort** | High (parser+checker) | Low (just loader) | Medium (sandbox) | High (annotation proc) | Very High | Medium (build setup) |
| **Safety (prevents escape)** | **Perfect** | **Perfect** | **Weak** | **Weak** | **Perfect** | **Weak** |
| **Agent Authoring** | High | Medium | High | High | None | High |

The critical differentiator is **safety**. Options A, B, and E achieve perfect safety
because they define a closed language with no escape hatches. Options C, D, and F attempt
to restrict a general-purpose language, which is inherently leaky.

Between A and B, Option A is far more readable, expressive, and agent-friendly. Option B
is rejected for verbosity and poor authoring ergonomics.

---

## Concrete Syntax Examples

Based on the evaluation, the top 3 approaches are:
1. **A. Custom Textual DSL** (best safety + expressiveness)
2. **C. Groovy Restricted Subset** (best integration + least effort)
3. **F. Kotlin Internal DSL** (best type safety among JVM options)

### Example 1: A Single Dense Layer

#### A. Custom DSL

```
layer dense_relu(
    input: [1, in_dim],
    weights: weight[out_dim, in_dim],
    biases: weight[1, out_dim]
) -> [1, out_dim] {
    projected = matmul(input, transpose(weights))
    biased = projected + biases
    return relu(biased)
}
```

#### C. Groovy

```groovy
def denseRelu(input, weights, biases) {
    def projected = dense(input, weights, biases)
    return relu(projected)
}
```

#### F. Kotlin

```kotlin
val denseRelu = producerGraph("dense_relu") {
    val input by input(shape(1, inDim))
    val weights by weight(shape(outDim, inDim))
    val biases by weight(shape(1, outDim))

    val projected = dense(input, weights, biases)
    output(relu(projected))
}
```

### Example 2: An Attention Head

#### A. Custom DSL

```
layer attention(
    input: [1, dim],
    wq: weight[dim, dim],
    wk: weight[kv_dim, dim],
    wv: weight[kv_dim, dim],
    wo: weight[dim, dim],
    rms_att: weight[dim],
    qk_norm_q: weight[dim] | null,
    qk_norm_k: weight[kv_dim] | null,
    freq_cis: weight[seq_len, head_size / 2, 2],
    position: scalar,

    config: {
        heads: int,
        kv_heads: int,
        head_size: int,
        epsilon: float = 1e-5
    }
) -> [1, dim] {
    // Pre-attention normalization
    normed = rmsnorm(input, rms_att, config.epsilon)

    // Projections
    q = dense(normed, wq)
    k = dense(normed, wk)
    v = dense(normed, wv)

    // Optional QK-Norm
    if qk_norm_q != null {
        q = rmsnorm(q, qk_norm_q, 1e-6)
        k = rmsnorm(k, qk_norm_k, 1e-6)
    }

    // RoPE
    q_complex = reshape_split_half_rope(q, config.heads, config.head_size)
    k_complex = reshape_split_half_rope(k, config.kv_heads, config.head_size)

    q_rotated = rope_rotation(q_complex, freq_cis, position)
    k_rotated = rope_rotation(k_complex, freq_cis, position)

    q_flat = reshape_from_split_half_rope(q_rotated, config.heads, config.head_size)
    k_flat = reshape_from_split_half_rope(k_rotated, config.kv_heads, config.head_size)

    // KV cache write (GQA expansion happens at cache write)
    cache_keys(k_flat, position, config.heads, config.kv_heads, config.head_size)
    cache_values(v, position, config.heads, config.kv_heads, config.head_size)

    // Attention computation
    scores = attention_keys(q_flat, key_cache)
    masked = scores + causal_mask(position, seq_len)
    weights = softmax(masked)
    attended = attention_values(weights, value_cache)

    // Output projection
    return dense(attended, wo)
}
```

#### C. Groovy

```groovy
def attention(input, wq, wk, wv, wo, rmsAtt, qkNormQ, qkNormK,
              freqCis, position, heads, kvHeads, headSize, epsilon) {
    def normed = rmsnorm(input, rmsAtt, epsilon)

    def q = dense(normed, wq)
    def k = dense(normed, wk)
    def v = dense(normed, wv)

    if (qkNormQ != null) {
        q = rmsnorm(q, qkNormQ, 1e-6)
        k = rmsnorm(k, qkNormK, 1e-6)
    }

    def qComplex = reshapeToSplitHalfRope(q, heads, headSize)
    def kComplex = reshapeToSplitHalfRope(k, kvHeads, headSize)

    def qRotated = ropeRotation(qComplex, freqCis, position)
    def kRotated = ropeRotation(kComplex, freqCis, position)

    def qFlat = reshapeFromSplitHalfRope(qRotated, heads, headSize)
    def kFlat = reshapeFromSplitHalfRope(kRotated, kvHeads, headSize)

    cacheKeys(kFlat, position, heads, kvHeads, headSize)
    cacheValues(v, position, heads, kvHeads, headSize)

    def scores = attentionKeys(qFlat, keyCache)
    def masked = scores.add(causalMask(position, seqLen))
    def weights = softmax(masked)
    def attended = attentionValues(weights, valueCache)

    return dense(attended, wo)
}
```

#### F. Kotlin

```kotlin
val attention = producerGraph("attention") {
    val input by input(shape(1, dim))
    val wq by weight(shape(dim, dim))
    val wk by weight(shape(kvDim, dim))
    val wv by weight(shape(kvDim, dim))
    val wo by weight(shape(dim, dim))
    val rmsAtt by weight(shape(dim))
    val freqCis by weight(shape(seqLen, headSize / 2, 2))
    val position by scalar()

    val normed = rmsnorm(input, rmsAtt, 1e-6)

    val q = dense(normed, wq)
    val k = dense(normed, wk)
    val v = dense(normed, wv)

    val qRope = ropeRotation(reshapeSplitHalfRope(q, heads, headSize), freqCis, position)
    val kRope = ropeRotation(reshapeSplitHalfRope(k, kvHeads, headSize), freqCis, position)

    val qFlat = reshapeFromSplitHalfRope(qRope, heads, headSize)
    val kFlat = reshapeFromSplitHalfRope(kRope, kvHeads, headSize)

    cacheKeys(kFlat, position, heads, kvHeads, headSize)
    cacheValues(v, position, heads, kvHeads, headSize)

    val scores = attentionKeys(qFlat, keyCache)
    val masked = scores + causalMask(position, seqLen)
    val weights = softmax(masked)
    val attended = attentionValues(weights, valueCache)

    output(dense(attended, wo))
}
```

### Example 3: Complete Transformer Block

#### A. Custom DSL

```
layer swiglu_ffn(
    input: [1, dim],
    rms_ffn: weight[dim],
    w1: weight[hidden_dim, dim],
    w2: weight[dim, hidden_dim],
    w3: weight[hidden_dim, dim],
    config: { epsilon: float = 1e-5 }
) -> [1, dim] {
    normed = rmsnorm(input, rms_ffn, config.epsilon)

    gate = dense(normed, w1)
    gate_activated = silu(gate)

    up = dense(normed, w3)
    hidden = gate_activated * up

    return dense(hidden, w2)
}

layer transformer_block(
    input: [1, dim],
    // ... all attention weights ...
    // ... all FFN weights ...
    config: { heads: int, kv_heads: int, head_size: int, epsilon: float = 1e-5 }
) -> [1, dim] {
    // Attention with residual
    attn_out = attention(input, wq, wk, wv, wo, rms_att,
                         qk_norm_q, qk_norm_k, freq_cis, position, config)
    after_attn = input + attn_out

    // FFN with residual
    ffn_out = swiglu_ffn(after_attn, rms_ffn, w1, w2, w3, config)
    return after_attn + ffn_out
}
```

#### C. Groovy

```groovy
def swiGluFfn(input, rmsFfn, w1, w2, w3, epsilon) {
    def normed = rmsnorm(input, rmsFfn, epsilon)

    def gate = silu(dense(normed, w1))
    def up = dense(normed, w3)
    def hidden = gate.multiply(up)

    return dense(hidden, w2)
}

def transformerBlock(input, /* all weights */, config) {
    def attnOut = attention(input, /* attention weights */, config)
    def afterAttn = input.add(attnOut)

    def ffnOut = swiGluFfn(afterAttn, rmsFfn, w1, w2, w3, config.epsilon)
    return afterAttn.add(ffnOut)
}
```

### Example 4: Complete LLM Model Sketch

#### A. Custom DSL

```
// qwen3.pdsl (Producer DSL)

import "transformer_block.pdsl"

model qwen3(
    config: {
        dim: 3584,
        hidden_dim: 11008,
        layers: 36,
        heads: 32,
        kv_heads: 8,
        head_size: 112,
        vocab_size: 151669,
        seq_len: 131072,
        rope_theta: 1000000.0,
        epsilon: 1e-6
    }
) {
    weights: state_dict {
        embed_tokens:   "model.embed_tokens.weight"     // [vocab_size, dim]
        final_norm:     "model.norm.weight"              // [dim]

        for layer_idx in 0..config.layers {
            layer[layer_idx]: {
                rms_att:    "model.layers.{layer_idx}.input_layernorm.weight"
                wq:         "model.layers.{layer_idx}.self_attn.q_proj.weight"
                wk:         "model.layers.{layer_idx}.self_attn.k_proj.weight"
                wv:         "model.layers.{layer_idx}.self_attn.v_proj.weight"
                wo:         "model.layers.{layer_idx}.self_attn.o_proj.weight"
                bq:         "model.layers.{layer_idx}.self_attn.q_proj.bias"
                bk:         "model.layers.{layer_idx}.self_attn.k_proj.bias"
                bv:         "model.layers.{layer_idx}.self_attn.v_proj.bias"
                qk_norm_q:  "model.layers.{layer_idx}.self_attn.q_norm.weight"
                qk_norm_k:  "model.layers.{layer_idx}.self_attn.k_norm.weight"
                rms_ffn:    "model.layers.{layer_idx}.post_attention_layernorm.weight"
                w1:         "model.layers.{layer_idx}.mlp.gate_proj.weight"
                w2:         "model.layers.{layer_idx}.mlp.down_proj.weight"
                w3:         "model.layers.{layer_idx}.mlp.up_proj.weight"
            }
        }
    }

    // Precomputed constants
    freq_cis = compute_rope_freqs(config.seq_len, config.head_size, config.rope_theta)

    // Model pipeline
    pipeline(input: token_embedding) -> logits {
        x = input   // [1, dim]

        for layer_idx in 0..config.layers {
            x = transformer_block(x,
                weights.layer[layer_idx],
                freq_cis, position,
                config)
        }

        x = rmsnorm(x, weights.final_norm, config.epsilon)
        logits = dense(x, weights.embed_tokens)  // shared weights
    }
}
```

#### C. Groovy

```groovy
// Qwen3.groovy

def buildQwen3(stateDict, config) {
    def freqCis = computeRopeFreqs(config.seqLen, config.headSize, config.ropeTheta)
    def position = placeholder(1)

    def model = sequential(shape(1, config.dim))

    for (int i = 0; i < config.layers; i++) {
        def prefix = "model.layers.${i}"
        model.add(transformerBlock(
            stateDict.get("${prefix}.input_layernorm.weight"),
            stateDict.get("${prefix}.self_attn.q_proj.weight"),
            stateDict.get("${prefix}.self_attn.k_proj.weight"),
            stateDict.get("${prefix}.self_attn.v_proj.weight"),
            stateDict.get("${prefix}.self_attn.o_proj.weight"),
            stateDict.get("${prefix}.self_attn.q_proj.bias"),
            stateDict.get("${prefix}.self_attn.k_proj.bias"),
            stateDict.get("${prefix}.self_attn.v_proj.bias"),
            stateDict.get("${prefix}.self_attn.q_norm.weight"),
            stateDict.get("${prefix}.self_attn.k_norm.weight"),
            freqCis,
            stateDict.get("${prefix}.post_attention_layernorm.weight"),
            stateDict.get("${prefix}.mlp.gate_proj.weight"),
            stateDict.get("${prefix}.mlp.down_proj.weight"),
            stateDict.get("${prefix}.mlp.up_proj.weight"),
            position,
            config
        ))
    }

    model.add(rmsnorm(shape(1, config.dim),
        stateDict.get("model.norm.weight"), config.epsilon))
    model.add(dense(stateDict.get("model.embed_tokens.weight")))

    return model
}
```

---

## Recommendation

### Pick: Option A — Custom Textual DSL

The custom textual DSL is the best approach for this project. Here is why:

#### 1. It solves the actual problem

The problem is not "we need a nicer syntax for writing computation graphs." The problem is
"agents keep breaking the computation graph pattern by using Java escape hatches." Options
C, D, and F attempt to restrict general-purpose languages, but restriction is fundamentally
harder than construction. A custom DSL solves this by construction: if the grammar does not
include `evaluate()`, `toDouble()`, `for` loops, or method calls on arbitrary objects, then
those operations cannot be expressed. Period.

This is the same principle behind SQL (you cannot write arbitrary code, only queries), shader
languages (GLSL/HLSL/MSL cannot access the filesystem), and configuration languages (Dhall
is total and cannot loop forever). The restriction IS the feature.

#### 2. It is more expressive than it appears

The syntax examples above show that the DSL naturally expresses:
- Linear layers (dense + bias + activation)
- Attention heads with GQA, QK-Norm, and RoPE
- SwiGLU feed-forward networks
- Residual connections
- Complete transformer blocks
- Full model definitions with weight binding

The vocabulary of operations (documented above) covers everything in `LayerFeatures`,
`AttentionFeatures`, and `RotationFeatures`. If a new primitive is needed, it gets added
to the DSL runtime (the Java side), not the DSL grammar.

#### 3. The implementation is tractable

The DSL does not need to be Turing-complete. It needs:
- **Variable bindings** (`x = operation(...)`)
- **Function calls** with positional and named arguments
- **Arithmetic on shapes** (`head_size / 2`, `dim * kv_heads / heads`)
- **Conditional expressions** (`if qk_norm_q != null { ... }`)
- **Bounded loops** for layer repetition (`for layer_idx in 0..36 { ... }`)
- **Imports** for composing layers from separate files
- **Config blocks** for parameterization

This is a simple expression language — no closures, no classes, no generics, no
inheritance, no exceptions, no concurrency. A parser can be written with ANTLR in
under 500 lines of grammar. The code generator maps each DSL function call to the
corresponding Java method call on `LayerFeatures`/`AttentionFeatures`/etc.

#### 4. It changes the agent workflow for the better

Today: Agents write Java classes, have access to all of Java, and must exercise discipline
to stay within the Producer pattern. They fail regularly.

With the DSL: Agents write `.pdsl` files that can ONLY express Producer compositions.
The Java integration layer loads `.pdsl` files and produces `Block`/`Model` objects.
Agents writing computation layers never touch Java at all — they work in a sandbox where
the only possible output is a valid computation graph.

The orchestration code (loading weights, setting up tokenizers, running inference) stays
in Java. Only the computation graph definitions move to the DSL.

#### 5. Why not Groovy (Option C)?

Groovy is tempting because it requires less tooling effort. But it fails the safety test.
Even with `SecureASTCustomizer`:
- An agent can call `.getClass().getMethod("evaluate").invoke(this)` via reflection
- An agent can call methods on `PackedCollection` objects passed as arguments
- An agent can use Groovy's `MetaClass` system to add methods dynamically
- An agent can import arbitrary Java classes unless ALL imports are blocked

Closing every escape hatch in Groovy requires more effort than building a simple custom
parser, and the result is still less trustworthy.

#### 6. Why not Kotlin (Option F)?

Kotlin is a great language, but it adds a heavy dependency (the Kotlin compiler and
runtime) for a problem that needs a lightweight solution. The DSL builder pattern in
Kotlin provides nice syntax but no actual safety — the lambda body has access to all
of Kotlin and Java. It would be another instance of "trust the agent to stay in bounds."

#### 7. Why not Annotation-Driven Java (Option D)?

Option D is the second-best approach and could serve as a complement to the DSL. The
annotation processor can catch violations in existing Java code while the DSL prevents
them in new code. However, as a standalone solution, it has the fundamental problem that
annotation processing cannot perform whole-program analysis. A method annotated
`@ProducerGraph` can call an unannotated helper method that calls `.evaluate()`.

### Integration Design

The DSL integrates with the existing Java codebase via a loader:

```java
// Load a DSL-defined layer
ProducerDSL dsl = ProducerDSL.load("attention.pdsl");

// Bind weights from StateDictionary
dsl.bind("wq", stateDict.get("model.layers.0.self_attn.q_proj.weight"));
dsl.bind("wk", stateDict.get("model.layers.0.self_attn.k_proj.weight"));
// ... or bind all at once from a StateDictionary with naming convention

// Get the resulting Block
Block attentionBlock = dsl.toBlock(inputShape);

// Use in a Model like any other Block
model.add(attentionBlock);
```

Or for a complete model:

```java
// Load a complete model definition
ProducerDSL modelDef = ProducerDSL.load("qwen3.pdsl");
modelDef.bindStateDictionary(stateDict);
Model model = modelDef.toModel();
CompiledModel compiled = model.compile(false, profile);
```

The DSL runtime is a thin Java library that:
1. Parses `.pdsl` files into an AST
2. Type-checks shapes (propagating dimensions through the graph)
3. Generates `CollectionProducer` / `Block` / `Model` objects by calling the existing
   Java APIs (`LayerFeatures`, `AttentionFeatures`, etc.)

No bytecode generation. No code generation. The DSL interpreter directly calls the
existing Java methods at load time to construct the computation graph.

---

## Implementation Roadmap

### Phase 1: Grammar and Parser (1-2 weeks)

- Define the ANTLR grammar for `.pdsl` files
- Implement the parser and AST representation
- Support: variable bindings, function calls, arithmetic expressions, config blocks
- Deliverable: Can parse the attention layer example into an AST

### Phase 2: Type Checker / Shape Propagator (1-2 weeks)

- Implement shape inference and validation
- Track tensor shapes through operations
- Report shape mismatches at load time with clear error messages
- Deliverable: Shape errors caught before any Java code executes

### Phase 3: Code Generator / Interpreter (2-3 weeks)

- Map DSL function calls to Java method calls on `LayerFeatures`/`AttentionFeatures`
- Implement weight binding from `StateDictionary`
- Support `for` loops (bounded, for layer repetition)
- Support imports for composing layers from multiple files
- Deliverable: Can load `attention.pdsl` and produce a working `Block`

### Phase 4: Model-Level Support (1-2 weeks)

- Support complete model definitions (the `model` construct)
- Implement `state_dict` weight binding blocks
- Support `pipeline` construct for model assembly
- Deliverable: Can load `qwen3.pdsl` and produce a working `Model`

### Phase 5: Tooling (ongoing)

- VS Code / IntelliJ syntax highlighting for `.pdsl` files
- LSP server for autocomplete and hover documentation
- Agent instructions / CLAUDE.md updates for writing `.pdsl` files
- Migration guide for converting existing Java layers to DSL

### Total Estimated Effort

Core implementation (Phases 1-4): 6-9 weeks of focused work.

This can be parallelized: the grammar/parser can be developed independently of the
interpreter, and both can proceed while the type checker is designed.

---

## Risks and Open Questions

### Risk: DSL becomes a bottleneck

If every new operation requires a DSL grammar change, the DSL could slow down development.

**Mitigation:** The DSL grammar maps to Java method calls. Adding a new primitive means:
1. Implement the Java method on the appropriate Features interface (as today)
2. Register the method name in the DSL runtime's function table

No grammar changes needed for new primitives — only for new language constructs (which
should be rare after Phase 4).

### Risk: Insufficient expressiveness

Some future computation pattern may not fit the DSL's model.

**Mitigation:** The DSL is not meant to replace all Java code — only computation graph
definitions. Orchestration, weight loading, tokenization, and inference loops remain in
Java. If a layer truly cannot be expressed in the DSL, it can be implemented as a new
Java primitive and exposed to the DSL as a function call.

### Risk: Shape arithmetic limitations

The DSL needs to evaluate expressions like `dim * kv_heads / heads` at load time. If
shapes depend on runtime values, the type checker cannot validate them.

**Mitigation:** In practice, all shapes in the existing codebase are statically known at
model construction time (they come from config objects). Runtime-dependent shapes are
not used in the current architecture.

### Open Question: Conditional computation

Should the DSL support `if/else` beyond null-checking? Some architectures (Mixture of
Experts, early exit) use conditional computation. The current recommendation supports
null-checking for optional parameters but not general conditionals.

**Recommendation:** Start without general conditionals. If needed, add them in a later
phase with the constraint that both branches must produce the same output shape.

### Open Question: Backpropagation

The DSL defines forward computation. Backpropagation is handled by the existing
`DefaultGradientPropagation` system. Does the DSL need to express custom backward
passes?

**Recommendation:** No. The existing automatic differentiation through Producer composition
handles backpropagation. The DSL should only express forward computation, and the Java
runtime derives gradients automatically.

### Open Question: File extension and naming

The examples use `.pdsl` (Producer DSL). Other options: `.ar` (Almost Realism),
`.flow` (computation flow), `.graph` (computation graph), `.layer` (layer definition).

**Recommendation:** Use `.pdsl` — it is specific, unambiguous, and unlikely to conflict
with other tools.

### Open Question: Relation to existing ar-ml-script module

The `extern/ml-script/` module suggests prior work on Groovy-based scripting. The custom
DSL is a different approach but could coexist: Groovy scripts for exploratory/prototyping
work, `.pdsl` files for production computation layers.

---

## Summary

| Aspect | Decision |
|--------|----------|
| **Approach** | Custom textual DSL (`.pdsl` files) |
| **Safety model** | Closed language — only Producer operations expressible |
| **Parser technology** | ANTLR4 |
| **Execution model** | Interpreted at load time — directly constructs Java objects |
| **Integration** | `ProducerDSL.load()` returns `Block` / `Model` objects |
| **Weight binding** | Via `StateDictionary` with configurable key mapping |
| **Shape checking** | Static analysis at load time |
| **Backward pass** | Automatic (existing `DefaultGradientPropagation`) |
| **File extension** | `.pdsl` |
| **What stays in Java** | Orchestration, weight loading, tokenization, inference loops, new primitives |
| **What moves to DSL** | Computation graph definitions (layers, blocks, models) |
