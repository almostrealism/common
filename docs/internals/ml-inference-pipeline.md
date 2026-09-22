# ML Inference Pipeline Internals

## Overview

This document explains how the Almost Realism ML inference pipeline works — from
loaded weights to generated tokens. It covers the KV cache lifecycle, autoregressive
generation loop, attention computation flow, and cross-attention for diffusion models.

For compilation pipeline details (how `Producer` graphs become native kernels), see
[computation-graph-to-process-tree.md](computation-graph-to-process-tree.md),
[process-optimization-pipeline.md](process-optimization-pipeline.md), and
[backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md).

### High-Level Pipeline

```
StateDictionary           Model Build              Compile              Generate
 (protobuf files)    ─>   (Producer graph)    ─>   (native kernels)  ─>  (tokens)

 ┌──────────────┐     ┌───────────────────┐    ┌────────────────┐    ┌──────────────┐
 │ .pb weight   │     │ Model()           │    │ CompiledModel  │    │ Autoregressive│
 │ files on     │────>│  .add(transformer)│───>│  .forward(in)  │───>│  Model       │
 │ disk         │     │  .add(rmsnorm)    │    │  returns logits│    │  .next()     │
 │              │     │  .add(dense)      │    │                │    │  returns tok │
 └──────────────┘     └───────────────────┘    └────────────────┘    └──────────────┘
       │                      │                       │                     │
  StateDictionary       AttentionFeatures       Process.optimize()   AutoregressiveModel
  loads weights         builds attention        compiles to GPU      manages generation
  from protobuf         blocks with caches      or CPU kernels       loop and sampling
```

**Key classes in the pipeline:**

| Stage | Class | Location |
|-------|-------|----------|
| Weight loading | `StateDictionary` | `engine/ml/src/.../ml/StateDictionary.java` |
| Model building | `AttentionFeatures` | `engine/ml/src/.../ml/AttentionFeatures.java` |
| Position encoding | `RotationFeatures` | `engine/ml/src/.../ml/RotationFeatures.java` |
| Compilation | `Model.compile()` | `domain/graph/src/.../model/Model.java` |
| Generation | `AutoregressiveModel` | `engine/ml/src/.../ml/AutoregressiveModel.java` |
| Model implementation | `Qwen3` | `engine/ml/src/.../ml/qwen3/Qwen3.java` |

---

## KV Cache Architecture

The KV cache is the most performance-critical component of autoregressive inference.
It stores previously computed key and value projections so that each generation step
only needs to compute projections for the new token, not the entire sequence.

### Where the Structure Lives

The attention structure itself is not assembled in Java. It is described by the PDSL
asset `engine/ml/src/main/resources/pdsl/attention.pdsl`, whose `attention`,
`attention_qk_norm` and `attention_mra` layers spell out every stage of one forward
pass — normalization, projections, rotary embedding, the cache writes, scores, mask,
softmax, weighted values and the output projection. The Java side,
`AttentionFeatures.attention(...)`, is a thin loader: `attentionArguments` allocates the
caches and binds the arguments, `attentionLayer` parses the asset and builds the
requested layer through `PdslLoader.buildLayer`. The caches are declared in the asset
as caller-owned state:

```
state attention_cache {
    key_cache: weight
    value_cache: weight
}
```

### Cache Allocation

Each attention layer owns two caches — one for keys, one for values. They are
`(seqLen, dim)` where `dim = heads * headSize` and `heads` is the number of *query*
heads, not KV heads: row `s` holds token `s`'s key (or value) as one `headSize` slice
per query head.

```java
// AttentionFeatures.attentionArguments — bound to the asset's state block
args.put("key_cache", new PackedCollection(shape(seqLen, dim)));
args.put("value_cache", new PackedCollection(shape(seqLen, dim)));
```

For a Qwen3-4B model (32 query heads, 8 KV heads, headSize=112, seqLen=131072):
- Key cache: `(131072, 3584)` = ~1.8 GB per layer
- Value cache: same shape = ~1.8 GB per layer
- Total per layer: ~3.6 GB
- Total for 36 layers: ~130 GB (in practice, shorter seqLen is used)

### Unwritten Positions

A freshly allocated cache holds zeros, so the rows of positions that have not been
generated yet contribute zero to the dot product with the query. That alone is not
enough — a zero score is not a negligible one after softmax — so the causal mask adds
`-10000` to every column after the current position, and softmax assigns those
positions ~0 probability. Both mechanisms are visible as stages of the asset
(`causal_mask(position)` followed by `softmax()`).

Zero-initialization of a freshly allocated `PackedCollection` is a property of the active
`MemoryProvider`, not of `PackedCollection` itself: `JVMMemoryProvider` backs each allocation
with a `new double[len]`, which the JVM zero-fills, but the mechanism is provider-specific
rather than a guarantee documented on `PackedCollection`.

### GQA Expansion Strategy: Expand at Write Time

The caches use **expanded** shape `(seqLen, heads, headSize)` rather than compact
shape `(seqLen, kvHeads, headSize)`. This is a deliberate design choice:

```
Compact caches (NOT used):                Expanded caches (USED):
┌─────────────────────────┐               ┌─────────────────────────────────────┐
│ (seqLen, kvHeads=8,     │               │ (seqLen, heads=32, headSize=112)    │
│  headSize=112)          │               │                                     │
│                         │               │ KV head 0 data replicated to        │
│ Requires GQA expansion  │               │ query heads 0,1,2,3                 │
│ at EVERY read during    │               │ KV head 1 data replicated to        │
│ attention computation   │               │ query heads 4,5,6,7                 │
└─────────────────────────┘               │ ... etc                             │
                                          └─────────────────────────────────────┘
                                          GQA expansion done ONCE at write time
```

**Why expand at write time:**
- Each cache slot is written once (at position `p`) but read at every subsequent step
- Expanding during write (`O(1)` operations per step) avoids repeated expansion during
  read (`O(n)` operations per step where `n` grows with context length)
- Expanded caches let the score and weighted-value stages treat every query head
  alike (no GQA logic during the hot path)

The expansion is the `repeat_each(heads / kv_heads)` stage of the asset, backed by
`LayerFeatures.repeatEach`: a single `gather` whose indices duplicate each `(kvHeads,
headSize)` row `heads / kvHeads` consecutive times. With `kv_heads == heads` the stage
is a pass-through and adds no kernel.

```java
// For output index i in [0, dim):
//   outputHead = i / headSize
//   kvHead = outputHead / headsPerKvGroup
//   inputIdx = kvHead * headSize + (i % headSize)
```

### Cache Write

After the key/value projections (with optional QK-Norm, RoPE, and GQA expansion), the
`keys` and `values` branches of the asset end in a cache write at the current
position:

```
branch keys {
    dense(wk, bk)
    rotate_heads(kv_heads, head_size, freq_cis, position)
    repeat_each(heads / kv_heads)
    cache_write(key_cache, position)
}
branch values {
    dense(wv, bv)
    reshape([kv_heads, head_size])
    repeat_each(heads / kv_heads)
    cache_write(value_cache, position)
}
```

`cache_write(cache, position)` is backed by `LayerFeatures.cacheWrite`: a
`CollectionSlotUpdateComputation` assigned into the cache through `into(...)`, so the
row replacement is part of the compiled operation order rather than a host-side copy.
The stage passes its input through unchanged. A branch runs before the path that
follows it, so both caches already hold the current token when the scores are
computed.

```
Cache state at position=3:

Position:  0       1       2       3       4    ...  seqLen-1
         ┌───────┬───────┬───────┬───────┬───────┬───┬────────┐
Keys:    │ k[0]  │ k[1]  │ k[2]  │ k[3]  │  0    │...│   0    │
         │(1,dim)│(1,dim)│(1,dim)│(1,dim)│(zeros)│   │(zeros) │
         └───────┴───────┴───────┴───────┴───────┴───┴────────┘
                                    ▲
                                 Written this step
```

### Cache Read

The `attend` layer of the asset reads the whole of each cache: the key cache when the
scores are computed against the current query, the value cache when the attention
weights are applied.

```
layer attend(heads: int, head_size: int, position: scalar,
             key_cache: weight, value_cache: weight) -> [heads, head_size] {
    attention_scores(key_cache)
    scale(1 / sqrt(head_size))
    causal_mask(position)
    softmax()
    weighted_values(value_cache)
}
```

`attention_scores` (`AttentionFeatures.attentionScores`) and `weighted_values`
(`AttentionFeatures.weightedValues`) take the cache as a `Producer`, giving the
computation access to all cached rows across all positions. The causal mask then
prevents attending to future (unwritten) positions.

### Causal Masking

A dynamic causal mask prevents the model from attending to future positions. The
`causal_mask(position)` stage is backed by `AttentionFeatures.causalMask`:

```java
CollectionProducer indices = integers(0, seqLength);
CollectionProducer maskRow = greaterThan(indices, position, c(-10000.0), c(0.0), false);
CollectionProducer mask = maskRow.reshape(1, 1, seqLength).repeat(heads);
```

This creates a mask vector where `mask[i] = 0` if `i <= position` (attend) and
`mask[i] = -10000` if `i > position` (block). Adding `-10000` to attention scores
before softmax makes those positions effectively zero probability:

```
At position=3, seqLen=8:
mask = [0, 0, 0, 0, -10000, -10000, -10000, -10000]
         ▲  ▲  ▲  ▲     ▲
       can attend    blocked (future positions)
```

### Memory Layout

Caches are stored as `PackedCollection` objects, which are handles to contiguous
memory blocks that may reside on GPU or CPU. The `TraversalPolicy` defines how
multi-dimensional indexing maps to flat memory offsets.

A cache with shape `(seqLen, heads, headSize)`:
- `cache[pos][head][i]` → flat offset: `pos * heads * headSize + head * headSize + i`
- When reshaped to `(seqLen, dim)`: `cache[pos][j]` → flat offset: `pos * dim + j`

Both views share the same underlying memory — reshape is zero-copy.

---

## Autoregressive Generation Loop

`AutoregressiveModel<T>` (`engine/ml/src/.../ml/AutoregressiveModel.java`) orchestrates
token-by-token generation. It is generic in the token type — `Integer` for text models
and structured token types (e.g. `MidiCompoundToken`) for MIDI — and wraps a compiled
transformer model with the position bookkeeping, prompt handling, and sampling
infrastructure that generation needs. The class is bound to the device: the sequence
position is maintained on the device by compiled operations, and only the data that
the host actually needs to make the next sampling decision is read back. Greedy
decoding pulls a single `argmax` index off the device (`indexOfMax.evaluate(logits)
.toDouble(0)`), while temperature sampling reads the full vocabulary array via
`sampleToken`'s `toArray(0, vocabSize)` so it can renormalize and pick from the
distribution.

### Components

```java
// AutoregressiveModel.java:104-156
public class AutoregressiveModel<T> {
    private final PackedCollection position;       // Shared device-resident step counter
    private final Runnable resetPosition;         // Compiled once: position = 0
    private final Runnable advancePosition;       // Compiled once: position += 1
    private final Consumer<T> token;              // Loads the current token into the input buffer
    private final Supplier<PackedCollection> forward;  // Runs the compiled model
    private final Function<PackedCollection, T> sample; // Maps logits to the next token
    private PackedCollection temperature;            // Single-element, host-writable

    private int currentStep;                      // Host-side mirror of `position`
    private T currentToken;
    private T[] prompt;
    private int promptLength;
    private PackedCollection cachedOutput;        // Logits from the previous forward pass
}
```

The position is a single-element `PackedCollection` shared with the model's computation
graph — attention reads it as `p(position)` to index the KV cache and apply RoPE. It is
never written from the host: `resetPosition` and `advancePosition` are compiled once
over constants and reused for every token of every sequence. Writing the position per
step from the host (e.g. by assigning a constant built from the step index) would
compile a distinct operation for every position in the sequence.

### Factory Method

The `of()` factory wraps a compiled text model and constructs the sampling function
inline:

```java
// AutoregressiveModel.java:405-434
public static AutoregressiveModel<Integer> of(CompiledModel model,
                                             PackedCollection position,
                                             IntFunction<PackedCollection> tokenEmbed) {
    PackedCollection in = new PackedCollection(model.getInputShape());
    int vocabSize = model.getOutputShape().getTotalSize();

    PackedCollection temperature = new PackedCollection(1);

    Evaluable<PackedCollection> indexOfMax = Ops.o().indexOfMax(Ops.o().x(vocabSize)).get();
    Evaluable<PackedCollection> rescale = Ops.o().x(vocabSize).divide(Ops.o().cp(temperature)).get();
    Evaluable<? extends PackedCollection> softmax =
            Process.optimized(DIST.softmax(Ops.o().x(vocabSize))).get();

    Random random = new Random();
    Function<PackedCollection, Integer> sample = logits -> {
        if (temperature.toDouble(0) == 0.0) {
            return (int) indexOfMax.evaluate(logits).toDouble(0);
        } else {
            rescale.into(logits).evaluate(logits);
            softmax.into(logits).evaluate(logits);
            return sampleToken(logits, vocabSize, 1.0, 1.0, random);
        }
    };

    return new AutoregressiveModel<>(
            position,
            t -> in.setFrom(0, tokenEmbed.apply(t), 0,
                            model.getInputShape().getTotalSize()),
            () -> model.forward(in),
            sample,
            temperature);
}
```

Note the use of `setFrom` for the token-embedding copy — the recent
`setmem-policy-phases` work retired the `setMem(int, double[], int, int)` bulk-host-array
overload from `MemoryData`, and the consumer created here is the sanctioned way to
move one device buffer into another.

In Qwen3, this is wired as:

```java
// Qwen3.java:411-414
return AutoregressiveModel.of(
        compiledModel,
        position,                                                       // shared device-resident step
        t -> tokenEmbeddings.range(shape(1, config.dim), t * config.dim));
```

`position` is created earlier in the same method (`Qwen3.java:338`) as a single-element
`PackedCollection` and passed to attention as `p(position)` so the model reads the
current step from the device.

### Two-Phase Operation

The `next()` method (`AutoregressiveModel.java:295-308`) implements two distinct phases:

```
Phase 1: PROMPT (currentStep < promptLength)
─────────────────────────────────────────────
Step 0: Feed prompt[0] at position 0 → get logits → cache logits
Step 1: Feed prompt[1] at position 1 → get logits → cache logits
Step 2: Feed prompt[2] at position 2 → get logits → cache logits
  ...
Step N-1: Feed prompt[N-1] at position N-1 → get logits → cache logits

Returns: prompt token at each step (NOT sampled from logits)
Purpose: Build KV cache for prompt context

Phase 2: GENERATION (currentStep >= promptLength)
─────────────────────────────────────────────────
Step N:   Sample from cached logits → feed sampled token at position N → cache new logits
Step N+1: Sample from cached logits → feed sampled token at position N+1 → cache new logits
  ...

Returns: sampled token at each step
Purpose: Generate new tokens autoregressively
```

**Key detail:** In the generation phase, the token is sampled from the *previous*
step's cached output before the current forward pass. This is because the model's
output at step `t` predicts the token at position `t+1`:

```java
// AutoregressiveModel.java:295-308
public T next() {
    if (currentStep < promptLength) {
        token.accept(prompt[currentStep]);
        cachedOutput = forward.get();
        currentToken = prompt[currentStep];
    } else {
        currentToken = sample.apply(cachedOutput);
        token.accept(currentToken);
        cachedOutput = forward.get();
    }

    advance();
    return currentToken;
}
```

`advance()` (`AutoregressiveModel.java:217-220`) runs the compiled `advancePosition`
operation and increments the host-side `currentStep`, keeping the two in lock-step.
`reset()` (`AutoregressiveModel.java:203-207`) zeros the device position, the host
step index, and discards the cached output before a new sequence.

### Position Tracking

The shared `position` collection is read by the model's computation graph at three sites:

1. **RoPE computation** — to look up the correct rotation frequencies for this position
2. **Cache indexing** — to write K/V projections to the correct cache slot
3. **Causal mask** — to block attention to positions beyond the current one

Because `position` is a `PackedCollection` passed to attention as `p(position)`, the
attention operation reads its current value from the device at dispatch time. The host
never assigns it; `resetPosition` and `advancePosition` are the only writers.

### Token Embedding

The `token` consumer loads the current token's representation into the model's input
buffer. For Qwen3 the representation is a `(1, dim)` slice of the embedding matrix
extracted at the token ID:

```java
// Qwen3.java:414
t -> tokenEmbeddings.range(shape(1, config.dim), t * config.dim)
```

The factory's consumer wraps this in a `setFrom` so the slice is copied into the
fixed input buffer that the compiled model reads.

### Temperature Sampling

The `sample` function constructed inside `of()` (`AutoregressiveModel.java:418-426`)
handles both greedy and sampling modes. `temperature` is a host-writable single-element
collection updated by `setTemperature` (`AutoregressiveModel.java:258-260`):

```java
// Reading (greedy path)
if (temperature.toDouble(0) == 0.0) {
    return (int) indexOfMax.evaluate(logits).toDouble(0);
}

// Sampling path
rescale.into(logits).evaluate(logits);   // logits / temperature
softmax.into(logits).evaluate(logits);   // softmax(scaled_logits)
return sampleToken(logits, vocabSize, 1.0, 1.0, random);
```

`sampleToken` (`AutoregressiveModel.java:323-386`) is the static, host-side sampling
helper. It reads the logits once with `toArray(0, vocabSize)` — a single
sanctioned device-to-host transfer at the step boundary — and operates on the
resulting host array. The recent `setmem-policy-phases` work changed sampling from
per-element `toDouble(i)` calls to this single bulk read after the same per-element
pattern produced ~17 million native readbacks during
`MoonbeamValueDistributionTest.testSoftmaxAndSampling` (10.3s → 0.14s).

- **Temperature = 0:** Greedy decoding — always picks the highest-probability token
- **Temperature > 0:** Divides logits by temperature before softmax, then samples
  from the resulting probability distribution. Higher temperature = more random.

---

## Attention Computation Flow

This section traces data flow through a single attention layer during autoregressive
inference. The structure is the `attention` layer of
`engine/ml/src/main/resources/pdsl/attention.pdsl` (with `attention_qk_norm` and
`attention_mra` as the QK-Norm and per-head-group variants); the primitives it is
composed from are implemented in `AttentionFeatures`, `RotationFeatures` and
`LayerFeatures`, and `AttentionFeatures.attention()` only binds the arguments and
builds the layer.

### Step-by-Step Flow

```
Input: x (1, dim)  —  single token embedding after transformer layers
                │
                ▼
        ┌───────────────┐
        │   RMSNorm     │  Pre-attention normalization
        │  (1, dim)     │
        └───────┬───────┘
                │
       ┌────────┼────────┐
       ▼        ▼        ▼
   ┌───────┐ ┌───────┐ ┌───────┐
   │ Wq    │ │ Wk    │ │ Wv    │   Linear projections
   │(dim,  │ │(kvDim,│ │(kvDim,│   Q: (dim) → (dim)
   │ dim)  │ │ dim)  │ │ dim)  │   K: (dim) → (kvDim)
   └───┬───┘ └───┬───┘ └───┬───┘   V: (dim) → (kvDim)
       │         │         │
       ▼         ▼         │
   ┌───────┐ ┌───────┐    │
   │QK-Norm│ │QK-Norm│    │      Optional per-head RMSNorm
   │(Q)    │ │(K)    │    │
   └───┬───┘ └───┬───┘    │
       │         │         │
       ▼         ▼         │
   ┌───────┐ ┌───────┐    │
   │ RoPE  │ │ RoPE  │    │      Rotary position embeddings
   │ (Q)   │ │ (K)   │    │      at current position
   └───┬───┘ └───┬───┘    │
       │         │         │
       │    ┌────┴────┐    │
       │    │GQA      │    │
       │    │Expand   │    │      kvDim → dim (duplicate KV heads)
       │    │(K)      │    │
       │    └────┬────┘    │
       │         │    ┌────┴────┐
       │         │    │GQA      │
       │         │    │Expand   │  kvDim → dim (duplicate KV heads)
       │         │    │(V)      │
       │         │    └────┬────┘
       │         │         │
       │         ▼         ▼
       │    ┌─────────────────┐
       │    │  Write to cache │  keyCache[position] = expanded_k
       │    │  at position    │  valueCache[position] = expanded_v
       │    └─────────────────┘
       │
       ▼
   ┌──────────────────┐
   │ Q @ K_cache^T    │  Scaled dot-product: (heads, headSize) @ (seqLen, heads, headSize)^T
   │ / sqrt(headSize) │  Output: (heads, seqLen) attention scores
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │  + Causal Mask   │  Add -10000 to positions > current
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │    Softmax       │  Normalize scores to probabilities
   │  (per head)      │
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │ Attn @ V_cache   │  Weighted sum of cached values
   │                  │  Output: (1, dim)
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │    Wo            │  Output projection: (dim) → (dim)
   └────────┬─────────┘
            │
            ▼
        Output: (1, dim)  —  added to input via residual connection
```

### 1. QKV Projection

The input `x` (after RMSNorm) is projected into queries, keys, and values using
separate weight matrices. The biases are optional weights: a model without them
(Llama 2, SkyTNT) binds `null`, and `dense()` adds nothing.

```
branch keys   { dense(wk, bk) ... }     // K: (dim) → (kvDim)
branch values { dense(wv, bv) ... }     // V: (dim) → (kvDim)
dense(wq, bq)                            // Q: (dim) → (dim)
```

For Qwen3: `dim=3584`, `kvDim=896` (8 KV heads * 112 headSize).

### 2. QK-Norm (Optional)

The `attention_qk_norm` layer (Qwen3, Gemma2) normalizes each projected query and key
head by head before RoPE:

```
branch keys {
    dense(wk, bk)
    rmsnorm(qk_norm_k, 1e-6)
    ...
}
dense(wq, bq)
rmsnorm(qk_norm_q, 1e-6)
```

QK-Norm stabilizes attention logits by normalizing Q and K independently. The weights
have shape `(heads, headSize)` or `(kvHeads, headSize)`; `rmsnorm` takes its
statistic over the last axis of its weights, so every head is normalized by its own
root mean square and scaled by its own row of weights — the `RMSNorm(head_dim)` of the
reference implementation.

### 3. RoPE Application

Rotary position embeddings encode positional information by rotating Q and K vectors
in the complex plane. The `rotate_heads` layer of the asset uses the split-half format
matching PyTorch's Qwen/Llama RoPE:

```
layer rotate_heads(heads: int, head_size: int, freq_cis: weight, position: scalar)
        -> [1, heads * head_size] {
    split_half_rope(heads, head_size)
    rope_rotation([heads, head_size / 2, 2], freq_cis, position)
    merge_half_rope(heads, head_size)
}
```

The three-step process:
1. **Split-half layout** (`split_half_rope`, backed by `reshapeToSplitHalfRope`):
   Transforms from flat `(kvDim)` to `(kvHeads, headSize/2, 2)` where the last
   dimension pairs `[firstHalf, secondHalf]` of each head
2. **Apply rotation** (`rope_rotation`, backed by `ropeRotation`): For each pair
   `(x1, x2)` and frequency `(cos, sin)` at the current position:
   - `out1 = x1 * cos - x2 * sin`
   - `out2 = x2 * cos + x1 * sin`
3. **Merge** (`merge_half_rope`, backed by `reshapeFromSplitHalfRope`): Returns to
   `(kvHeads, headSize)`

The frequency tensor `freq_cis` has shape `(seqLen, headSize/2, 2)` where position
`p` and frequency index `i` store `[cos(p * theta_i), sin(p * theta_i)]`.
The rotation indexes into this tensor using the current position. The
`attention_mra` layer uses `rotate_head_groups` instead, which rotates each head
group by its own table at its own position (`mra_rope_rotation`).

### 4. GQA Expansion

For models with fewer KV heads than query heads (e.g., Qwen3: 8 KV heads, 32 query
heads), the key and value rows are expanded by duplicating each KV head's data before
the cache write:

```
repeat_each(heads / kv_heads)
```

`LayerFeatures.repeatEach` is a single gather with graph-computed indices:

```
Input:  (8, 112)   = 8 KV heads * 112
Output: (32, 112)  = 32 query heads * 112

KV head 0 → duplicated to query heads 0, 1, 2, 3
KV head 1 → duplicated to query heads 4, 5, 6, 7
...
KV head 7 → duplicated to query heads 28, 29, 30, 31
```

### 5. Scaled Dot-Product Attention with Causal Mask

After the cache writes, the `attend` layer computes the scores between the current
query and all cached keys, scales, masks, normalizes and applies the weights:

```
attention_scores(key_cache)      // scores[h][s] = sum_i(Q[h][i] * K[s][h][i])
scale(1 / sqrt(head_size))       // / sqrt(headSize)
causal_mask(position)            // -10000 on every column after position
softmax()                        // one distribution per head over the sequence
weighted_values(value_cache)     // sum_s(weights[h][s] * V[s][h][i]) → (1, dim)
```

`attention_scores` (`AttentionFeatures.attentionScores`) tiles the query across the
cache rows explicitly (`repeat(seqLength, ...)`) rather than relying on the broadcast
of `multiply(cache, query)`: that broadcast matches leading dimensions, so when the
head count equals the sequence length the `(heads, headSize)` query is a prefix of
the `(seqLength, heads, headSize)` cache and each query element would be spread over a
run of cache elements instead of the query being tiled across rows.
`AttentionAssetTest` pins the four-head, four-row case against a host reference.

### 6. Output Projection

The attended output `(1, dim)` is projected through `Wo`:

```
dense(wo)
reshape([1, heads * head_size])
```

### Complete Transformer Layer

The `transformer()` method (`AttentionFeatures.java:1460-1480`) wraps attention and
feed-forward with residual connections using `accum()`:

```java
SequentialBlock transformer = new SequentialBlock(shape(1, dim));
transformer.accum(attention(...), requirements);  // x = x + attention(x)
transformer.accum(feedForward(...), requirements); // x = x + ffn(x)
```

The `accum()` method adds the block's output to the residual stream, implementing
the standard pre-norm transformer pattern: `x = x + sublayer(norm(x))`.

---

## Cross-Attention for DiffusionTransformer

`DiffusionTransformer` (`engine/ml/src/.../ml/audio/DiffusionTransformer.java`) uses
a different attention pattern than autoregressive models. Instead of token-by-token
generation with KV caches, it processes full sequences with optional cross-attention
for conditioning.

### Architecture Overview

```
Audio Input                  Timestep        Cross-Attn Cond    Global Cond
(batch, channels, seqLen)    (batch, 1)      (condSeqLen, dim)  (globalDim)
        │                       │                   │                │
        ▼                       ▼                   ▼                ▼
   Conv1D Residual        Fourier Features     Cond Projection   Global Proj
        │                  + MLP Embed              │                │
        ▼                       │                   │                │
   Patchify + Reshape           │                   │                │
        │                       └───────────────────┼────────────────┘
        ▼                                           │         │
   Project In (dense)                               │    ┌────┴────┐
        │                                           │    │Prepend  │
        ├──── (prepend global+timestep tokens) ◄────┘────┤Cond     │
        │                                                └─────────┘
        ▼
   ┌─────────────────────── × depth ──────────────────────┐
   │ Transformer Block:                                    │
   │   x = x + self_attn(norm(x))         ← sequence attn │
   │   x = x + cross_attn(norm(x), cond)  ← optional      │
   │   x = x + ffn(norm(x))               ← gated linear  │
   └───────────────────────────────────────────────────────┘
        │
   Remove prepended tokens
        │
   Project Out → Unpatchify → Conv1D Residual
        │
        ▼
   Output (batch, channels, seqLen)
```

### How Cross-Attention Differs from Self-Attention

In self-attention, Q, K, and V all come from the same input. In cross-attention:
- **Queries (Q)** come from the main input (audio sequence)
- **Keys (K) and Values (V)** come from the external context (e.g., text embeddings)

```java
// AttentionFeatures.java:1312-1373 — sequenceCrossAttention
// 1. Project main input to queries
crossAttention.add(projectionFactory.create(queryShape, toQWeight, ...));

// 2. Apply Q normalization
crossAttention.add(norm(normType, qNormWeight, qNormBias, 1e-6));

// 3. Process context input through separate branch for K and V
SequentialBlock contextBranch = contextInput.branch();
contextBranch.add(projectionFactory.create(contextInput.getOutputShape(), toKvWeight, ...));
// Split into K and V
List<Block> kv = contextBranch.split(shape(batch, contextSeqLen, 1, dim), 0);
```

Key differences from self-attention:
- **No RoPE on context:** Cross-attention keys/values do not receive rotary
  position embeddings because the context positions are independent of the
  audio sequence positions (`AttentionFeatures.java:1349-1350`)
- **Separate sequence lengths:** The query sequence length may differ from the
  context sequence length
- **No causal mask:** Cross-attention allows attending to all context positions

### Context KV Projection and Caching

In `DiffusionTransformer`, cross-attention uses fused KV projection for the context:

```java
// DiffusionTransformer.java:803
crossKv = createWeight("...cross_attn.to_kv.weight", 2 * dim, dim);
```

The fused KV weight projects the context to `2 * dim`, which is then split into
separate K and V tensors:

```java
// AttentionFeatures.java:1339-1343
contextBranch.reshape(batchSize, contextSeqLen, 2, dim);
List<Block> kv = contextBranch.split(shape(batchSize, contextSeqLen, 1, dim), 0);
SequentialBlock k = (SequentialBlock) kv.get(0).reshape(batchSize, contextSeqLen, heads, dimHead);
SequentialBlock v = (SequentialBlock) kv.get(1).reshape(batchSize, contextSeqLen, heads, dimHead);
```

K and V are stored in intermediate `PackedCollection` tensors for the attention
computation:

```java
// AttentionFeatures.java:1353-1357
PackedCollection kTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
PackedCollection vTensor = new PackedCollection(shape(batchSize, heads, contextSeqLen, dimHead));
k.andThen(into(kTensor));
v.andThen(into(vTensor));
```

### Prepended Conditioning Strategy

Instead of adaptive layer normalization (AdaLayerNorm), `DiffusionTransformer` uses
**prepended conditioning** — timestep and global conditioning are projected and
prepended as extra tokens to the audio sequence:

```java
// DiffusionTransformer.java:620-632
protected Block prependConditioning(Block timestampEmbed, Block globalEmbed) {
    // ...
    return layer("prependConditioning",
        shape(batchSize, audioSeqLen, embedDim),
        shape(batchSize, audioSeqLen + 1, embedDim),
        in ->
            concat(1, add(cp(globalCond), cp(timestep)).reshape(batchSize, 1, embedDim), c(in)));
}
```

This adds one extra token at the start of the sequence (the sum of timestep and
global conditioning), increasing the sequence length from `audioSeqLen` to
`audioSeqLen + 1`. After the transformer blocks, the prepended token is removed:

```java
// DiffusionTransformer.java:558-562
if (seqLen > audioSeqLen) {
    int prependedLength = seqLen - audioSeqLen;
    main.reshape(batchSize, seqLen, ioChannels)
        .subset(shape(batchSize, audioSeqLen, ioChannels), 0, prependedLength, 0);
}
```

### Sequence-Based Self-Attention (DiffusionTransformer)

Unlike autoregressive attention which processes one token at a time with KV caches,
`DiffusionTransformer` uses full-sequence attention via `sequenceAttention`
(`AttentionFeatures.java:962-1106`, with further overloads adding a customizable
`ProjectionFactory` and a selectable query/key `NormalizationType`; the parenthetical
description at lines 1204-1234 is the Javadoc for the separate `sequenceCrossAttention`
method that follows):

- Processes all positions simultaneously with fused QKV projection
- Uses `scaledDotProductAttention` over the full sequence (no causal mask needed)
- Applies full-sequence RoPE via `applyRotaryPositionEmbedding` instead of
  single-position `ropeRotation`
- No KV cache — the full K and V tensors are computed and stored for each forward pass

---

## Related Files

### Core Source Files

| File | Description |
|------|-------------|
| `engine/ml/src/.../ml/AttentionFeatures.java` | Unified attention mechanisms (MHA, GQA, cross-attention, transformer blocks) |
| `engine/ml/src/.../ml/AutoregressiveModel.java` | Autoregressive generation loop with prompt/generation phases |
| `engine/ml/src/.../ml/RotationFeatures.java` | RoPE implementations (single-position and full-sequence) |
| `engine/ml/src/.../ml/StateDictionary.java` | Model weight loading from protobuf format |
| `engine/ml/src/.../ml/qwen3/Qwen3.java` | Qwen3 model implementation (reference for LLM inference) |
| `engine/ml/src/.../ml/qwen3/Qwen3Config.java` | Model configuration and parameter inference |
| `engine/ml/src/.../ml/audio/DiffusionTransformer.java` | Diffusion model with cross-attention |
| `engine/ml/src/.../ml/audio/DiffusionTransformerFeatures.java` | Fourier features and timestep embedding |
| `engine/ml/src/.../ml/audio/DiffusionSampler.java` | Diffusion sampling loop |

### Test Files

| File | Description |
|------|-------------|
| `engine/ml/src/.../ml/qwen3/BranchCacheTest.java` | KV cache branching and correctness tests |
| `engine/ml/src/.../ml/qwen3/GQAExpandTest.java` | GQA expansion verification |
| `engine/ml/src/.../ml/qwen3/FullAttentionMethodTest.java` | Full attention method integration tests |
| `engine/ml/src/.../ml/qwen3/RopePositionTest.java` | RoPE position encoding tests |
| `engine/ml/src/.../ml/qwen3/MultiTokenGenerationTest.java` | Multi-token autoregressive generation tests |
| `engine/ml/src/.../ml/AttentionTests.java` | Core attention computation tests |

### Related Internals Documentation

| Document | Description |
|----------|-------------|
| [computation-graph-to-process-tree.md](computation-graph-to-process-tree.md) | How `Producer` graphs become process trees |
| [process-optimization-pipeline.md](process-optimization-pipeline.md) | How process trees are optimized |
| [backend-compilation-and-dispatch.md](backend-compilation-and-dispatch.md) | How optimized processes compile to native kernels |

### Module Documentation

| Document | Description |
|----------|-------------|
| [ML README](../../engine/ml/README.md) | ML module API-level documentation |
| [Graph README](../../domain/graph/README.md) | Neural network layers and computation graph |
| [Collect README](../../base/collect/README.md) | PackedCollection and tensor storage |
| [Hardware README](../../base/hardware/README.md) | GPU/CPU acceleration and memory management |
