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
(`DifferentialAttentionAssetTest`). The per-head lambda itself also trains: it is applied with
`scale(lambda, 1, λq1, λk1, λq2, λk2)`, which registers the four vectors the re-parameterization
`exp(λq1·λk1) − exp(λq2·λk2) + λinit` is built from (`differentialLambda`) as the scaling stage's
weights, so the backward pass differentiates the lambda with respect to them. The overload of
`differentialSequenceAttention` that takes the four vectors wires this; the one that takes a fixed
lambda producer binds those weights null and applies the lambda without training it, exactly as
before. A finite-difference check in `DifferentialAttentionAssetTest` confirms each vector receives
its gradient — on an earlier revision the lambda was applied with a weightless scale, so its
parameters were captured as a constant and received no gradient at all. Which operand of each
PDSL built-in trains, which is a constant and which has no backward path is tabulated in the
[PDSL Language Reference](pdsl-language.md#builtin-catalog). Cross-attention now trains its K/V projection the same way:
`sequenceCrossAttention` computes the keys and values on a `branch()` of its context input and feeds
those branch blocks to the score and context products through the `Block`-valued
`scaledDotProductAttention` overload (again `compose`), so the backward pass reaches both halves of
the fused `toKvWeight` and the key normalization weights (`SequenceCrossAttentionGradientTest`).
Because the context is an auxiliary model input, this also required propagating the training
`ParameterUpdate` into `BranchBlock` children and into `Model`'s auxiliary inputs, so a weight that
lives on a branch of an auxiliary input trains like one on the main chain. The learned-resampling
transformer of `TransformerResamplingFeatures` was the last to buffer: its layer copied the first keys,
the values, the second queries and the second attention map into collections, and built its
projections as weightless cells over constant weight tensors, so a backward pass reached none of its
projections. Its layer is now `resampling_transformer.pdsl`, which composes the attention maps of
`differential_attention.pdsl` and the `glu_feed_forward` of `feed_forward.pdsl` with `dense`
projections, so every section of its fused projection, its output projection and its feed-forward
projections receive their gradients (`ResamplingTransformerAssetTest`). Its DynamicTanh
normalizations differentiate correctly because the hyperbolic tangent is now differentiated at the
Producer level (`CollectionHyperbolicTangentComputation`), as the sine and cosine are; the
expression-level derivative it used before was wrong for the scaled, row-traversed argument
DynamicTanh and GELU give it.

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

**The full-Jacobian weight gradient was wrong for the output projection.** For the 256 by 64
weight over 64 rows, `jacobianGradient` disagreed with the definition in about a tenth of the
16,384 entries (1,536 or 1,728 in the runs measured, always from weight row 64 onward) on Metal,
while the product agreed exactly. Its kernel sums 16,384 outputs of 64 masked terms for each
weight entry over an enumeration of 2^34 positions. The 192 by 64 and 128 by 64 weights were
correct. The documentation runs before the product existed therefore trained the output projection
with partly wrong gradients. The cause was in code generation, not the sparse-Jacobian machinery:
the kernel hoisted repeated parts of the flattened position into variables declared as 32-bit
integers, so a hoisted row multiplied by a stride of 1,048,512 overflowed where the same product
computed inline, with the 64-bit kernel index, did not. Hoisted variables now take a 64-bit type
whenever the expression refers to the kernel index (`Expression.getDeclarationType`; see
[Automatic Differentiation](automatic-differentiation.md)), and
`DenseVectorJacobianProductTest.outputProjection` asserts the full-Jacobian weight gradient against
the definition on several evaluations.

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
about 20 minutes on the machine below.

**Configuration**

| Setting | Value |
|---------|-------|
| Corpus | Six `docs/internals` pages (`features-pattern`, `producer-evaluable-pattern`, `shape-and-traversal`, `collection-producer-operations`, `state-dictionary`, `end-to-end-computation`), 53,731 bytes when last measured (the pages change, so the size and both baselines drift with them) |
| Split | First 90% of the bytes for training (48,358), last 10% held out (5,373), split before windowing |
| Model | Byte vocabulary 256, context 64, embed 64, 4 heads, depth 2, feed-forward width 128, full rotary embedding (base 10000), no query/key normalization, final RMS norm; 115,008 parameters |
| Initialization | Normal(0, 0.02) for embedding and projections, ones for norm scales, seed 20260930 |
| Optimizer | Adam, betas 0.9 / 0.999; learning rate decaying linearly per epoch from 5e-4 to 5e-5 |
| Training data | 150 windows per epoch, rotating; the stride is `NextTokenDataset.spanningStride` of the 6,000 windows the run reads (8 bytes for this corpus), so the windows of the run cover the whole training region once, overlapping, ending at the text just before the held-out region |
| Held-out data | 12 fixed non-overlapping windows scored at every epoch boundary (the progress curve); all 83 non-overlapping held-out windows scored once after training (the asserted number) |
| Length | 40 epochs, 6,000 steps (one step per 64-byte window, batch 1) |

**Measured curve** (bits per byte, from the mean NLL divided by ln 2). Baselines: uniform 8.0;
unigram byte entropy of the 5,312 targets the 83 held-out windows score, 4.904; of the 768
targets the 12 per-epoch windows score, 4.588. The training column is the mean over that epoch's
150 windows, which are a different stretch of the training region in every epoch, so it moves
with the text as much as with the model.

| Epoch | Train | Held-out (12 windows) |
|-------|-------|-----------------------|
| 0 | 5.497 | 4.686 |
| 3 | 4.194 | 4.901 |
| 6 | 3.285 | 4.303 |
| 9 | 3.711 | 4.058 |
| 12 | 3.392 | 3.840 |
| 15 | 3.282 | 4.029 |
| 18 | 2.820 | 3.925 |
| 21 | 2.387 | 3.820 |
| 24 | 2.778 | 4.304 |
| 27 | 3.156 | 3.901 |
| 30 | 3.927 | 3.884 |
| 33 | 3.687 | 4.170 |
| 36 | 3.498 | 3.805 |
| 39 | 2.581 | 3.547 |

Scored over all 83 held-out windows after training: **3.426 bits per byte against a baseline of
4.904**.

**The run beats the unigram baseline** by 1.48 bits per byte, measured on exactly the held-out
targets the baseline describes (the asserted loss and the asserted baseline come from the same
`NextTokenDataset` of every held-out window), and below the 3.78 of the count-based bigram
described below. The reloaded checkpoint reproduces the per-epoch held-out loss exactly, and the
greedy continuation is no longer a single repeated byte (see "Generation"), so both assertions of
the test pass. Training is deterministic on this backend: a second run of the same configuration
reproduced every logged loss and the continuation exactly.

### From 96 spaces to words

Until this round, every documented run greedily continued the prompt with 96 spaces. Two
measurements located the cause, both logged by the test:

- **It was the training, not the decoder or the gradient.** The test logs the most probable bytes
  after the prompt. With the previous recipe (peak rate 3e-3, 3,000 steps) a space was the most
  probable byte after `"The computation graph is compiled to "` (p = 0.21, then `a` at 0.07), and
  also after a verbatim fragment of the first training page, `"A Features interface is a Java
  interface whose methods are all "` (p = 0.11), whose greedy continuation was 96 spaces as well.
  A model that collapses on text it trained on is underfit, not failing to generalize. In this
  corpus the most frequent byte after a space is another space (about 2,700 of the 7,640 spaces in
  the training region, from the indentation of code blocks), so a model that conditions on little more
  than the last byte repeats the space forever; a greedy decoder over counts of the last two bytes
  already writes ``"a `Collection a `Collection"``.
  `CausalLanguageModelTest.gradientsMatchDirectionalDerivativesAtDocumentedScale` rules out a
  gradient defect at this size: for every weight of the documented configuration, the analytic
  gradient projected on a random direction matches the central difference of the loss along that
  direction (within about 1% for the projections and the embedding, and 2% for the normalization
  scales, whose differences are closer to single-precision noise).
- **The peak learning rate was the limit.** With batch 1, a peak rate of 3e-3 holds the training
  loss near 4 bits per byte however long the run is: 7,500 steps at that rate left it there, and
  only changed the continuation to `"tere arontighe te te te thate te tion aterororor…"`. Lowering
  the rate let the same model learn: the training loss falls to 2.4 to 3.5 bits per byte, and the
  held-out loss with it.

The recipes measured in this round, all at the configuration above apart from the learning rate
and length (all-window held-out loss, baseline 4.904; first bytes of the greedy continuation of
`"The computation graph is compiled to "`):

| Learning rate | Steps | Held-out | Greedy continuation |
|---------------|-------|----------|---------------------|
| 3e-3 to 3e-4 | 3,000 | 4.495 | 96 spaces |
| 3e-3 to 3e-4 | 7,500 | 4.669 | `tere arontighe te te te thate te tion aterorororor…` |
| 1e-3 to 1e-4 | 6,000 | 3.695 | `the the the the the the the the the the the the the troduction.` |
| 5e-4 to 5e-5 (the configuration above) | 6,000 | 3.426 | ``a the a the a `CollectionProducerCongetionCons a `CollectionProducerCongetion` a `CollectionProd`` |
| 2.5e-4 to 2.5e-5 | 6,000 | 3.665 | `and the the the the the the the …` |

The first row is the previous documented recipe re-measured on the current code and corpus (it
had recorded 4.194 on a slightly smaller corpus). Capacity was the more expensive lever and was
not needed: a step costs 0.35 s at embed 128 and feed-forward width 256 (against 0.18 s at the
configuration above), and 2.8 s at context 256 with that width, so within the test's time budget
a larger model reads far fewer bytes. Batch sizes above 1 were not tried.

### Earlier rounds

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
| *after* — learning rate 3e-3 to 3e-4, spanning stride (the previously documented configuration) | 3,000 | 4.194 | −0.718 |

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
  has a great deal of headroom; the configuration documented then, at 4.19, used only part of it.

**Timing** (Mac Studio, Apple silicon, Metal, FP32), measured by the test on the same machine
before and after dense layers took the vector-Jacobian product. The "after" figures were measured
with an earlier form of the product, a matmul-specific object attached to the matrix multiply; the
"now" figures with the reduced delta described above that replaced it.

| | Before | After | Now |
|---|--------|-------|-----|
| Warm time per window (training step or held-out pass) | about 3.6 s | 0.344 s (550 steps), 0.351 s (3,000 steps) | 0.182 s (6,000 steps) |
| Warm epoch | about 395 s (110 + 12 windows) | 42 s (110 + 12 windows), 57 s (150 + 12 windows) | 29.5 s (150 + 12 windows) |
| Whole run | about 34 min (550 steps) | 5.5 min (550 steps), 21 min (3,000 steps) | 20 min (6,000 steps) |

Compile takes 2 s, the cold first epoch includes 5 to 120 s of kernel compilation depending on
the native kernel cache, and scoring all 83 held-out windows takes about 1 s.

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
otherwise, restricted to the nucleus (top-p) of most probable bytes when `setTopP` has lowered the
threshold below its default of `1.0`. Nucleus sampling draws only from the smallest set of most
probable bytes whose cumulative probability reaches `p`, renormalized over that set, which keeps the
varied quality of tempered sampling while cutting off the low-probability tail that otherwise
derails a weakly-generalizing model; `getTopP`/`setTopP` carry the threshold (valid range
`(0, 1]`), and `SlidingWindowAutoregressiveModel` and the `AutoregressiveModel.of(...)` factory now
route the same tempered logits through `sampleToken` once, so the two generators sample identically
at a given temperature, top-p and seed. Positions after the filled prefix hold padding, which the causal mask keeps from
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

The cost of this sliding-window decode is one full forward pass per byte. In steady state it decodes
about 190 bytes per second for this configuration (Mac Studio, Apple silicon, Metal, FP32, measured
by `CausalLanguageModelDecodeThroughputTest` with an operation profile attached), issuing 86 kernel
invocations, 128 Metal dispatches and 7 command-buffer commits per byte. The single-position
key/value-cache decode was added to avoid recomputing the window.
`KeyValueCacheAutoregressiveModel` decodes the same weights one token at a time, scoring the new
position against each layer's cached keys and values instead of recomputing the whole window, so the
work per byte is one position rather than one window. It is built by `CausalLanguageModel.decodeModel`,
which bridges the trained weight layout to the cached `AttentionFeatures.attention` overloads without
copying a weight, passing each block a caller-owned `(seqLen, dim)` key and value cache it appends to
the generator's lists so the generator — not the block — owns them: the fused QKV `(3·dim, dim)`
weight of each block is exposed as three contiguous
`(dim, dim)` query, key and value views — the same sections the full-sequence path slices — and the
position-indexed rotary `freqCis` the cached overload expects is derived from the stored
`rope_inv_freq` with `RotationFeatures.computeRopeFreqs(seqLen, invFreq)`, which produces exactly the
rotation the full-sequence path applies at each row, so greedy output matches. The feed-forward, final
normalization, output projection and log-softmax are the trained ones, unchanged. The generator mirrors
`SlidingWindowAutoregressiveModel`: it owns the decode compilation, its input buffer, the per-layer
caches, the position and the temperature, and `reset()` clears the caches and position so a new sequence
starts clean; it routes the same tempered logits through `sampleToken`, so greedy, temperature and
top-p decoding behave as above. Because each layer's cache is a fixed `(seqLen, dim)` and does not
slide, this decode runs for sequences up to `seqLen` positions; longer generation still uses the
sliding window. `CausalLanguageModelTest.keyValueCacheDecodeMatchesFullForward` checks that every
greedily decoded byte, including those after the first where the cache alone carries the past, is the
most probable byte of the corresponding row of a separate full forward pass, and that decoding is
reproducible after a reset; `keyValueCacheResetClearsCacheAcrossSequences` and
`keyValueCacheLaterPositionsReadEarlierEntries` check that a cleared cache does not leak across
sequences and that a later position reads an earlier position's cached entry.

**Measured decode throughput.** The cache is now the faster decode, by a small margin.
`CausalLanguageModelDecodeThroughputTest.keyValueCacheDecodeThroughput` decodes the same
documentation prompt to the full 64-position context with both generators, over one model of the
configuration above (random weights, which do not change the cost), warming each up once and then
timing three decodes. On the machine above the key/value-cache decode runs at about 220 bytes per
second against the sliding window's 170–200 (wall-clock figures vary by a few tens of bytes per second
from run to run), a speedup of about 1.1, and issues 84 kernel invocations, 249 Metal dispatches and
2 command-buffer commits per byte against the sliding window's 86, 122 and 7.

It was about four times slower, at 50 bytes per second with 109 kernel invocations, 181 dispatches and
45 commits per byte. Its arithmetic per byte is one position instead of 64, but a model this small is
bound by the cost of each kernel, dispatch and host wait rather than by arithmetic. Four changes
closed the gap:

- *One compute context per pass.* A single position makes nearly every operation of the decode
  small. `DefaultComputer` places an operation of at most 128 elements on a CPU context and a larger
  one (each cache read, each matrix product's elementwise term, the output projection) on the
  accelerator, so the pass alternated between the two. Each CPU operation that read an accelerator
  result waited on the host for it, forcing a command-buffer commit: the about 30 waits per byte
  the test attributed to `mtlCopy`, the copy that ends each accelerator operation. The benchmark
  sets `Model.setComputeRequirements(ComputeRequirement.GPU)` on the language model when an
  accelerator is available; `decodeModel` compiles its variant under the requirements of the model
  it is built from, and `CompiledModel` compiles and runs the whole pass under them. The model does
  not make this choice itself: a caller that wants it sets it.
- *One kernel per rotation.* `RotationFeatures.ropeRotation` is a single element-wise computation
  reading the input, the frequency table and the position directly; it was a chain of gathers from
  intermediate computations that compiled to five kernels per call. `computeRopeFreqs` builds its
  `[cos, sin]` table as one element-wise computation instead of concatenating a cosine and a sine
  table.
- *One kernel per single-vector product.* `MatrixFeatures.matmul` multiplies one vector by a matrix
  with the weighted sum, which computes each output directly; the elementwise product followed by a
  sum it used before ran as two kernels, because the product has as many elements as the matrix.
- *Copy-back of written arguments only.* Small kernel arguments are staged in one aggregate buffer
  around each dispatch, and after a dispatch with no explicit output every staged argument was copied
  back, including those the kernel only read. The copy-back is now limited to the staged arguments the
  kernel's compiled `Scope` may write (`MemoryDataArgumentMap.getWrittenSlices`, which asks
  `Scope.containsAssignment` about each staged slice). Nothing is declared by the computation: every write in a
  scope is an assignment to an element of the aggregate at a bounded position, so the slices written
  are read off the scope itself, and a scope whose writes cannot be enumerated (one with methods,
  required scopes or explicit code) copies every slice back.

The cache path's remaining dispatches are mostly those staging copies: every argument of at most 1024
elements is copied into the aggregate before each kernel, and at a single position that is nearly every
argument, while the sliding window's are mostly larger. The test asserts, independently of the
backend, that the cache path invokes strictly fewer kernels per byte than the sliding window, that both
generators decode the same greedy continuation, and that the profile recorded each path's kernels. It
reports the throughput, the speedup, the dispatches and commits per byte, and the operations whose
waits forced each path's commits rather than asserting them, and is the drift guard for the figures in
this section. Its `greedyAndNucleusDecodeThroughCache` continues the prompt through the cache greedily
and with top-p sampling (temperature 0.8, `setTopP(0.9)`), showing that nucleus sampling composes with
the cache; over random weights neither continuation is text.

`trainOnDocumentation` generates from the reloaded checkpoint, greedily continuing the prompt
`"The computation graph is compiled to "` for 96 bytes, and logs the result together with whether
the bytes are valid UTF-8 under a strict decoder (reported rather than asserted, because a
byte-level model may emit a partial multi-byte sequence). It also logs the five most probable
bytes after the prompt and continues a verbatim fragment of a training page for comparison. The
test requires the continuation to be reproducible, to contain more than one distinct byte, and not
to be the prompt repeated. With the configuration above the platform writes valid UTF-8 (the rate
this test logs, about 100 bytes per second, times the generator's first decode and so includes its
first-call costs; the steady-state rate is the one measured above):

```
The computation graph is compiled to a the a the a `CollectionProducerCongetionCons a `CollectionProducerCongetion` a `CollectionProd
```

and continues the training fragment as

```
A Features interface is a Java interface whose methods are all a `Collection` `CollectionProducerCongerace tion.
```

followed by spaces. These are words and identifiers of the platform's own documentation, not yet
a sentence. The choice of the first byte is narrow (`a` at p = 0.124 against `t` at 0.122), so
the text is specific to this backend's arithmetic, although the run itself is deterministic.
The model now beats the count-based bigram on held-out text but not the trigram (3.07), and
greedy decoding locks onto whatever short loop the model ranks highest, so readable sentences
need the model to close more of that gap. Nucleus (top-p) sampling is now reachable through the
generator (`setTopP`), which cuts off the unreliable tail that full-distribution temperature
sampling can draw from; measuring whether it reads better than greedy decoding on this model is a
separate run. The next levers are those this round left untried: more steps at the lower rate (the
held-out curve was still falling in the last epochs) and batch sizes above 1. The top-p-versus-greedy
comparison over long samples need not wait on a longer cache either: the key/value-cache decode is
now slightly faster but stops at `seqLen`, and the sliding window, at a similar rate, is not bounded
by it.
