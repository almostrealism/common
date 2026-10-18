# Training a Language Model from Scratch

This page describes how the platform trains a small decoder-only transformer language model
from randomly initialized weights, using its own documentation as the corpus. The end-to-end run
is `CausalLanguageModelTest.trainOnDocumentation` in `engine/ml`; every primitive it uses has fast
unit tests that run in CI. See [training-loop-examples.md](training-loop-examples.md) for the
general rule that `ModelOptimizer` owns the training loop.

## Pieces

| Need | Where | Notes |
|------|-------|-------|
| Byte tokenizer | `ByteTokenizer` (`org.almostrealism.ml.tokenization`) | Fixed 256-symbol vocabulary; token id = unsigned UTF-8 byte. Decoding uses standard UTF-8 replacement for malformed sequences; ids outside `0..255` are rejected. |
| Next-token dataset | `NextTokenDataset` (`org.almostrealism.ml`) | Input `(seqLen)` token ids, target `(seqLen, vocab)` one-hot rows of the tokens shifted by one. `split` partitions the source tokens into contiguous regions before windowing, so no window crosses the train/held-out seam. |
| One-hot rows | `VectorFeatures.oneHotRows` | One producer for the whole `(rows, classes)` matrix. |
| Token embedding | `LayerFeatures.embedding` | Gathers table rows with `CollectionFeatures.rows`; gradients reach the table (`EmbeddingTests`). |
| Causal attention | `SequenceAttentionFeatures` | `sequenceAttention(..., causal)` builds a causal layer of `sequence_attention.pdsl`, whose score half's `sequence_causal_mask` stage adds `causalLogitMask` (zero for key `j <= i`, a large negative penalty otherwise) to the logits before the key-axis softmax, alongside any per-key mask. Reached from `TransformerBlockFeatures.transformerBlock(..., causal)` through the `AttentionFeatures.selfAttention` variant seam; `DifferentialAttentionFeatures` rejects `causal = true`. |
| Gradients into K and V | `sequence_attention.pdsl`, the PDSL `scaled_dot_product` built-in | The keys and values are branches of the attention layer, and `scaled_dot_product` takes a branch as its second operand, wiring it into the product as an auxiliary input (`LayerRoutingFeatures.compose`), so the fused QKV weight receives gradients in all three slices (`SequenceAttentionGradientTest`). |
| Loss | `logSoftmax` + `NegativeLogLikelihood` | The model ends in a `(seqLen, vocab)` output, so the loss is the mean over positions; `NegativeLogLikelihood.gradient` is `-target / rows`, the gradient of that mean. |
| Model | `CausalLanguageModel` (a `Model`) and `CausalLanguageModelConfig` (a `TransformerConfig`) | Embedding, `depth` pre-norm RMS blocks with causal RoPE attention and a SiLU-gated feed-forward, final RMS norm, output projection, log-softmax, assembled when the model is constructed. Weights live in a `StateDictionary`; the configuration owns their shapes and keys and creates fresh ones. |
| Optimizer | `AdamOptimizer` set with `Model.setParameterUpdate` | `CausalLanguageModel` starts with updates disabled, so Adam must be set before the model is compiled for training. |
| Window spacing | `NextTokenDataset.spanningStride` | The largest stride at which a given number of windows fit in the region, so a run that reads that many windows covers the region from start to end. |
| Generation | `SlidingWindowAutoregressiveModel` | An `AutoregressiveModel<Integer>` that compiles a `CausalLanguageModel` for inference and decodes with a sliding window over it; see "Generation" below. |

## Gradient wiring in sequence attention

`sequenceAttention` once wrote K and V into fixed buffers and read them back as constants inside
the attention products. The forward result was correct, but the backward pass stopped at the
buffers: a finite-difference check showed the K and V slices of the fused QKV weight receiving a
gradient of exactly zero while the Q slice matched. The fused projection is now split with the
query part as the main path and the key and value parts as branches pushed ahead of it, and the
branches feed the products through `compose`, whose backward pass propagates into both inputs.
That structure is the `sequence_attention.pdsl` asset `sequenceAttention` builds: its `keys` and
`values` branches are the operands of the `scaled_dot_product` stages of the score and context
layers.
Differential attention follows the same structure: `differentialSequenceAttention` builds a layer of
the `differential_attention.pdsl` asset, in which the keys and values of each of its two attention
maps are branches read by the products, and `accum_blocks` sums the two maps (the second
lambda-weighted and negated), so all five sections of its fused projection receive gradients
(`DifferentialAttentionAssetTest`). The buffering pattern remains in `sequenceCrossAttention` and
`TransformerResamplingFeatures`; those paths do not yet deliver gradients to their K/V projections.

## Device memory during training

The generic backward pass forms each weight gradient from the operator's Jacobian, and those
intermediates are allocated afresh on every step. Dense layers no longer take that path (see
"Weight gradients of dense layers" below), but the remaining operators (normalization, attention,
the gated feed-forward's element-wise product) still do, and for the configuration below this was
several gigabytes of temporary device memory per step before dense layers changed. Device buffers are released only after the Java
objects that own them are garbage collected, and those objects are small, so heap pressure alone
does not trigger collection before the device ceiling is reached. `HardwareMemoryProvider.reclaim`
handles this: when an allocation would exceed the ceiling, the Metal and OpenCL providers request a
collection and wait briefly for releases before rejecting the allocation. Both providers reserve
room against the ceiling atomically (`HardwareMemoryProvider.reserve`), so concurrent allocations
can neither overshoot it together nor lose each other's updates. `AdamOptimizer` also
stores the gradient once per step instead of evaluating it separately for momentum and velocity.

The gated feed-forward input projection dominated this memory (its Jacobian grows with the square
of the feed-forward width), which is why the run uses a width of 128 rather than 256. The run
needs `AR_HARDWARE_MEMORY_SCALE=6`.

## Weight gradients of dense layers

This section explains the reduced delta only as far as a dense layer needs it. The general
mechanism — the `ReduceAxis` contract, the computation extension points, the expression-level
Jacobian, and the backward pass — is documented in
[Automatic Differentiation](automatic-differentiation.md).

For a matrix multiply `y_r = W · x_r` applied to every row `x_r` of a sequence, the gradients the
backward pass needs are vector-Jacobian products with known structure:

```
dW   = Σ_r g_r ⊗ x_r      (summed over every row)
dx_r = Wᵀ · g_r
```

Contracting the dense Jacobian instead is an iteration space of output size times weight size
times the inner dimension: about 17 billion terms for the 256 by 64 output projection of the model
below, where the products above need about a million. The caller therefore tells `delta` which
axis of the Jacobian it is about to sum away: `CollectionProducer.delta(target, ReduceAxis.OUT, g)`
is the vector-Jacobian product `gᵀJ` (and `ReduceAxis.IN` with a tangent is the Jacobian-vector
product). Each computation produces the reduced delta from its own structure and passes the
reduction on to its inputs by the chain rule: a reshape passes it through, an addition gives every
operand the same weight, an element-wise product applies the product rule, a sum broadcasts the
weight over each group, and a repetition sums the weights of the copies. `matmul`'s
multiply-and-sum lowering is built from exactly those computations, so a dense layer's operator
(`LayerFeatures.denseOperator`: the output reshape of the bias addition of the matrix multiply)
yields `Σ_r g_r ⊗ x_r`, `Wᵀ · g_r` and the row-summed bias gradient without any code that
recognizes a matrix multiply. A computation without a reduced form of its own contracts its full
Jacobian, which is what `GradientFeatures.jacobianGradient` always does. The weighted-sum lowering
(`WeightedSumComputation`) that `matmul` uses for outputs wider than 1000 is not one of these: it
builds its own reduced delta for both axes from a transposed weighted sum, falling back to the full
Jacobian only for a traversal that cannot be transposed, such as a convolution's sliding window.
`DefaultGradientPropagation` forms
both the input gradient and every weight gradient as the output-reduced delta weighted by the
upstream gradient.

`DenseVectorJacobianProductTest` compares the reduced delta of the whole dense operator with the
forced full-Jacobian gradient and with the gradient computed on the host from its definition, for
every projection shape of the model below over a 64-row sequence, and checks that no computation in
the reduced delta's graph is larger than the largest computation of the operator itself.

**The full-Jacobian weight gradient is wrong for the output projection.** For the 256 by 64
weight over 64 rows, `jacobianGradient` disagrees with the definition in about a tenth of the
16,384 entries (1,536 or 1,728 in the runs measured, always from weight row 64 onward) on Metal,
while the product agrees exactly. Its kernel sums 16,384 outputs of 64 masked terms for each
weight entry over an enumeration of 2^34 positions. The 192 by 64 and 128 by 64 weights are
correct. The documentation runs before the product existed therefore trained the output projection
with partly wrong gradients; the cause inside the sparse-Jacobian machinery is not yet diagnosed.

## Pairing axes by size

Two operations in the dense layer relied on an implicit broadcast that pairs operands by their
traversal count (`TraversalAxisAlignment.align`) and so misread inputs whose sizes happen to
coincide:

- `matmul`'s multiply-and-sum lowering multiplied the `(m, n)` matrix with the `(rows, m, n)`
  repeated vectors. When `rows == m`, the counts match and each matrix row was paired with one
  vector, giving `y[r][i] = W[r] · x_r` for every `i`. The model below has 64 positions and a 64-wide
  embedding, so its attention output projection (64 by 64) and its feed-forward down projection
  (64 by 128) computed one value per position, copied across the channels.
- The dense bias `(nodes)` was added to the `(rows, nodes)` product the same way, adding bias entry
  `r` to every entry of row `r` when `rows == nodes`.

Both now repeat the smaller operand explicitly. `MatmulPathTest.batchAsLongAsMatrixRows`,
`DenseVectorJacobianProductTest.denseWithBiasRowsMatchingNodes` and
`CausalLanguageModelTest.outputsDoNotDependOnContextLength` (a causal model's rows must not depend on
the context length, compared at 64 and 63 positions) cover them. The finite-difference check
(`gradientsMatchFiniteDifferences`) could not catch either: it differentiates whatever the forward
pass computes, and its miniature configuration has no coinciding sizes.

## The documentation run

`CausalLanguageModelTest.trainOnDocumentation` is a long-running test, excluded from the CI
pipeline profile. Run it with `AR_LONG_TESTS=enabled` and `AR_HARDWARE_MEMORY_SCALE=6`; it takes
about 21 minutes on the machine below.

**Configuration**

| Setting | Value |
|---------|-------|
| Corpus | Six `docs/internals` pages (`features-pattern`, `producer-evaluable-pattern`, `shape-and-traversal`, `collection-producer-operations`, `state-dictionary`, `end-to-end-computation`), 53,465 bytes when last measured (the pages change, so the size and both baselines drift with them) |
| Split | First 90% of the bytes for training (48,119), last 10% held out (5,346), split before windowing |
| Model | Byte vocabulary 256, context 64, embed 64, 4 heads, depth 2, feed-forward width 128, full rotary embedding (base 10000), no query/key normalization, final RMS norm; 115,008 parameters |
| Initialization | Normal(0, 0.02) for embedding and projections, ones for norm scales, seed 20260930 |
| Optimizer | Adam, betas 0.9 / 0.999; learning rate decaying linearly per epoch from 3e-3 to 3e-4 |
| Training data | 150 windows per epoch, rotating; the stride is `NextTokenDataset.spanningStride` of the 3,000 windows the run reads (16 bytes for this corpus), so the windows of the run cover the whole training region once, overlapping, ending at the text just before the held-out region |
| Held-out data | 12 fixed non-overlapping windows scored at every epoch boundary (the progress curve); all 83 non-overlapping held-out windows scored once after training (the asserted number) |
| Length | 20 epochs, 3,000 steps (one step per 64-byte window, batch 1) |

**Measured curve** (bits per byte, from the mean NLL divided by ln 2). Baselines: uniform 8.0;
unigram byte entropy of the 5,312 targets the 83 held-out windows score, 4.912; of the 768
targets the 12 per-epoch windows score, 4.642.

| Epoch | Train | Held-out (12 windows) |
|-------|-------|-----------------------|
| 0 | 4.624 | 5.018 |
| 3 | 4.260 | 4.683 |
| 6 | 4.289 | 4.431 |
| 9 | 3.763 | 4.657 |
| 12 | 3.528 | 4.685 |
| 15 | 4.220 | 4.257 |
| 18 | 4.190 | 4.215 |
| 19 | 3.763 | 4.243 |

Scored over all 83 held-out windows after training: **4.194 bits per byte against a baseline of
4.912**.

**The run beats the unigram baseline** by 0.72 bits per byte, measured on exactly the held-out
targets the baseline describes (the asserted loss and the asserted baseline come from the same
`NextTokenDataset` of every held-out window), and the reloaded checkpoint reproduces the per-epoch
held-out loss exactly. Greedy generation is still 96 spaces (see "Generation"), so the test's
generation assertion, which runs before the baseline assertion, still fails.

What was measured on the way, all on this corpus and scored on all 83 windows. The rows above the
line ran before the dense-layer changes described above (full-Jacobian weight gradients, the size
pairings in `matmul`); the rows below ran after them, on the same corpus, windows and baseline:

| Recipe | Steps | Held-out | Gap to 4.912 |
|--------|-------|----------|--------------|
| Learning rate 3e-3 to 3e-4, stride 64 | 550 | 5.115 | +0.203 |
| Learning rate 1e-2 to 1e-3, stride 64 | 550 | 5.047 | +0.135 |
| Learning rate 1e-2 to 1e-3, spanning stride | 550 | 4.957 | +0.045 |
| Learning rate 1e-2 to 3e-3, spanning stride | 550 | 5.097 | +0.185 |
| *after* — learning rate 1e-2 to 1e-3, spanning stride | 550 | 4.687 | −0.225 |
| *after* — learning rate 1e-2 to 1e-3, spanning stride | 3,000 | 4.769 | −0.143 |
| *after* — learning rate 3e-3 to 3e-4, spanning stride (the configuration above) | 3,000 | 4.194 | −0.718 |

With the same 550-step recipe as before, the corrected model clears the baseline (4.687 against
4.957 before). Spending the freed time on 3,000 steps at the same peak rate of 1e-2 does not help
(4.769): the training loss stops falling and swings between 4.1 and 4.8, the noise of batch-1
updates at that rate. At a peak of 3e-3 the longer run keeps improving, with the training loss
reaching 3.5.

Lessons from the runs before the dense-layer changes (the first four rows):

- **Coverage was the largest lever.** At a stride of 64 the training region has 751 windows, and
  550 steps read only the first 550 of them: the first 35,200 bytes. The run never saw the last
  13 kB of the region, which is most of `end-to-end-computation`, the page whose tail is the
  held-out region. Even an ideal count-based bigram fitted to exactly the bytes a run reads
  scores 4.50 bits per byte on the held-out targets at stride 64, against 3.86 at the spanning
  stride. The per-epoch curve shows the effect: held-out loss drops sharply in the last two
  epochs, when training reaches that page.
- **A higher learning rate helped, a high final rate did not.** Raising the peak from 3e-3 to 1e-2
  gained 0.07 bits; holding the last epoch at 3e-3 instead of 1e-3 lost 0.14, presumably because
  with batch 1 the late updates are too noisy to settle.
- **The step budget was the limit.** 550 steps of batch 1 score about 35,000 target bytes in
  total, and at about 3.6 s per step more did not fit in the 38-minute test timeout. For
  reference, an interpolated count-based bigram fitted to the whole training region scores 3.78
  bits per byte on the held-out targets and a trigram 3.07, so a byte-level model of this corpus
  has a great deal of headroom; the configuration above, at 4.19, still uses only part of it.

**Timing** (Mac Studio, Apple silicon, Metal, FP32), measured by the test on the same machine
before and after dense layers took the vector-Jacobian product. The "after" figures were measured
with an earlier form of the product, a matmul-specific object attached to the matrix multiply,
which the reduced delta described above has replaced; they have not yet been re-measured with it.

| | Before | After |
|---|--------|-------|
| Warm time per window (training step or held-out pass) | about 3.6 s | 0.344 s (550 steps), 0.351 s (3,000 steps) |
| Warm epoch | about 395 s (110 + 12 windows) | 42 s (110 + 12 windows), 57 s (150 + 12 windows) |
| Whole run | about 34 min (550 steps) | 5.5 min (550 steps), 21 min (3,000 steps) |

That is about ten times cheaper per step. Compile takes 2 s, the cold first epoch includes 20 to
120 s of kernel compilation depending on the native kernel cache, and scoring all 83 held-out
windows takes 4 to 5 s.

**Where a step went.** Before the change a forward pass took about 0.03 s and the backward pass
3.2 to 4.2 s, waiting on Metal command buffers: the weight gradients were formed from each
operator's full Jacobian, an iteration space of about 17 billion terms for the 256 by 64 output
projection, 9.7 billion for each fused 192 by 64 QKV weight and 4.3 billion for each feed-forward
weight. A profile of the output projection's gradient alone shows the shape: the full-Jacobian
weight gradient is one kernel enumerating 2^34 positions (1.8 s of recorded time on its first
evaluation), the vector-Jacobian product one kernel over a `(256, 64, 64)` product (0.1 s,
including its compilation). Note that `OperationProfileNode` records Metal dispatch time rather
than kernel execution time, so per-step profiles of a training run show only tens of
milliseconds; the shapes of the recorded operations are what identify the expensive kernels.

**Checkpoint.** The trained weights are saved with `StateDictionary.save` (FP32, the training
precision on this backend) and reloaded into a new model; the reloaded held-out loss matched the
trained one exactly (the test allows a relative difference of 1e-4).

### What the run taught

- With one or two passes over a fixed set of windows, the model stalls at the unigram rate; with
  many passes over a small set it memorizes those windows and the held-out loss rises. Reading
  each window once, spread over the whole training region, at a decaying learning rate works best
  within the step budget.
- `ModelOptimizer` re-checks the first window of every epoch after its update and throws if that
  window's loss rose. A memorization probe on four windows tripped it after about 110 steps, once
  the loss was below one bit per byte; the budgeted runs did not trip it.

## Generation

`SlidingWindowAutoregressiveModel` is an `AutoregressiveModel<Integer>` constructed from a
`CausalLanguageModel`, which it compiles for inference itself, so it always decodes the model it
was given. It extends `AutoregressiveModel` and overrides its `load`, `forward` and `sample` steps
rather than using the `of` factory, which expects a model that takes one token's embedding and
returns one vocabulary row. A `Model` is compiled once, so generating from weights that are also
being trained or evaluated uses a second `CausalLanguageModel` over the same `StateDictionary`
(`new CausalLanguageModel(lm.getConfig(), lm.getWeights())`).

Decoding uses a sliding window over the full-sequence model the weights were trained with. The
`(seqLen)` input holds the most recent tokens (at most `seqLen`) in device memory: each new token
is written as a single value, and the window slides by device-to-device copies, so the window is
never staged on the host. Each step runs one forward pass and samples from the log-probabilities of the last filled row with
`AutoregressiveModel.sampleToken`: the most probable byte at temperature zero, a tempered sample
otherwise. Positions after the filled prefix hold padding, which the causal mask keeps from
affecting the filled rows; once the text is longer than `seqLen` the window slides, so every pass
sees its tokens at positions `0..seqLen-1`, as every training window did. The window is cleared
(padding zeroed, so every position is a valid token id) by `reset()`, which begins a new sequence.
The generator owns its inference compilation and its window, token, position and temperature
buffers: `AutoregressiveModel` is `Destroyable`, so a caller closes the generator
(try-with-resources works), while the `CausalLanguageModel` and its weights stay the caller's.
`CausalLanguageModelTest.slidingWindowGenerationMatchesFullForward` checks that every greedily
generated byte, including those after the window slides, is the most probable byte of the
corresponding row of a separate forward pass whose padding differs, and that greedy decoding is
reproducible after a reset.

The cost is one full forward pass per byte, about 35 to 45 bytes per second for this model. A
single-position decode with a key/value cache would avoid it, but the trained weights do not fit
the single-position `AttentionFeatures.attention` overloads as stored: the model keeps one fused
QKV weight per block and the rotary inverse frequencies, while those overloads take separate
query, key and value weights and their own rotary inputs.

`trainOnDocumentation` generates from the reloaded checkpoint, greedily continuing the prompt
`"The computation graph is compiled to "` for 96 bytes, and logs the result together with whether
the bytes are valid UTF-8 under a strict decoder (reported rather than asserted, because a
byte-level model may emit a partial multi-byte sequence). With the configuration above the
continuation is still 96 spaces, as it was in every run listed above: at 4.2 bits per byte the
model is better than the unigram rate on average, but after the prompt's trailing space its most
probable next byte is again a space, and greedy decoding then repeats it. The test requires the
continuation to be reproducible and to contain more than one distinct byte, so this assertion
still fails; the generation path itself is verified by the fast test above. Closing the remaining
gap to the bigram and trigram rates (and with it, presumably, the degenerate greedy continuation)
is a question of recipe and scale (batch size above 1, more steps or epochs, a larger context or
depth) rather than of step cost.
