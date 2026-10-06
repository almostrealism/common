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
| Causal attention | `SequenceAttentionFeatures` | `sequenceAttention(..., causal)` adds `causalLogitMask` (zero for key `j <= i`, a large negative penalty otherwise) to the logits before the key-axis softmax, alongside any per-key mask. Reached from `TransformerBlockFeatures.transformerBlock(..., causal)` through the `AttentionFeatures.selfAttention` variant seam; `DifferentialAttentionFeatures` rejects `causal = true`. |
| Gradients into K and V | `SequenceAttentionFeatures.scaledDotProductAttention(..., Block k, Block v, ...)` | K and V are wired into the two attention products as auxiliary inputs (`LayerRoutingFeatures.compose`), so the fused QKV weight receives gradients in all three slices (`SequenceAttentionGradientTest`). |
| Loss | `logSoftmax` + `NegativeLogLikelihood` | The model ends in a `(seqLen, vocab)` output, so the loss is the mean over positions; `NegativeLogLikelihood.gradient` is `-target / rows`, the gradient of that mean. |
| Model | `CausalLanguageModel` | Embedding, `depth` pre-norm RMS blocks with causal RoPE attention and a SiLU-gated feed-forward, final RMS norm, output projection, log-softmax. Weights live in a `StateDictionary`. |
| Optimizer | `AdamOptimizer` set on the `Model` | `Model` defaults to plain scaled SGD, so Adam must be passed explicitly. |
| Window spacing | `NextTokenDataset.spanningStride` | The largest stride at which a given number of windows fit in the region, so a run that reads that many windows covers the region from start to end. |
| Generation | `CausalLanguageModel.generator` | An `AutoregressiveModel<Integer>` that decodes with a sliding window over a compiled inference model of the same weights; see "Generation" below. |

## Gradient wiring in sequence attention

`sequenceAttention` once wrote K and V into fixed buffers and read them back as constants inside
the attention products. The forward result was correct, but the backward pass stopped at the
buffers: a finite-difference check showed the K and V slices of the fused QKV weight receiving a
gradient of exactly zero while the Q slice matched. The fused projection is now split with the
query part as the main path and the key and value parts as branches pushed ahead of it, and the
branches feed the products through `compose`, whose backward pass propagates into both inputs.
The same buffering pattern remains in `sequenceCrossAttention`, `TransformerResamplingFeatures`
and `DifferentialAttentionFeatures`; those paths do not yet deliver gradients to their K/V
projections.

## Device memory during training

The generic backward pass forms each weight gradient from the operator's Jacobian, and those
intermediates are allocated afresh on every step. For the configuration below this is several
gigabytes of temporary device memory per step. Device buffers are released only after the Java
objects that own them are garbage collected, and those objects are small, so heap pressure alone
does not trigger collection before the device ceiling is reached. `HardwareMemoryProvider.reclaim`
handles this: when an allocation would exceed the ceiling, the Metal and OpenCL providers request a
collection and wait briefly for releases before rejecting the allocation. Both providers reserve
room against the ceiling atomically (`HardwareMemoryProvider.reserve`), so concurrent allocations
can neither overshoot it together nor lose each other's updates. `AdamOptimizer` also
stores the gradient once per step instead of evaluating it separately for momentum and velocity.

The gated feed-forward input projection dominates this memory (its Jacobian grows with the square
of the feed-forward width), which is why the run uses a width of 128 rather than 256. The run
needs `AR_HARDWARE_MEMORY_SCALE=6`.

## The documentation run

`CausalLanguageModelTest.trainOnDocumentation` is a long-running test, excluded from the CI
pipeline profile. Run it with `AR_LONG_TESTS=enabled` and `AR_HARDWARE_MEMORY_SCALE=6`.

**Configuration**

| Setting | Value |
|---------|-------|
| Corpus | Six `docs/internals` pages (`features-pattern`, `producer-evaluable-pattern`, `shape-and-traversal`, `collection-producer-operations`, `state-dictionary`, `end-to-end-computation`), 53,465 bytes when last measured (the pages change, so the size and both baselines drift with them) |
| Split | First 90% of the bytes for training (48,119), last 10% held out (5,346), split before windowing |
| Model | Byte vocabulary 256, context 64, embed 64, 4 heads, depth 2, feed-forward width 128, full rotary embedding (base 10000), no query/key normalization, final RMS norm; 115,008 parameters |
| Initialization | Normal(0, 0.02) for embedding and projections, ones for norm scales, seed 20260930 |
| Optimizer | Adam, betas 0.9 / 0.999; learning rate decaying linearly per epoch from 1e-2 to 1e-3 |
| Training data | 110 windows per epoch, rotating; the stride is `NextTokenDataset.spanningStride` of the 550 windows the run reads (87 bytes for this corpus), so the run covers the whole training region once, ending at the text just before the held-out region |
| Held-out data | 12 fixed non-overlapping windows scored at every epoch boundary (the progress curve); all 83 non-overlapping held-out windows scored once after training (the asserted number) |
| Length | 5 epochs, 550 steps (one step per 64-byte window, batch 1) |

**Measured curve** (bits per byte, from the mean NLL divided by ln 2). Baselines: uniform 8.0;
unigram byte entropy of the 5,312 targets the 83 held-out windows score, 4.912; of the 768
targets the 12 per-epoch windows score, 4.642.

| Epoch | Train | Held-out (12 windows) |
|-------|-------|-----------------------|
| 0 | 5.269 | 4.975 |
| 1 | 5.005 | 4.977 |
| 2 | 4.841 | 4.920 |
| 3 | 4.819 | 4.737 |
| 4 | 4.996 | 4.716 |

Scored over all 83 held-out windows after training: **4.957 bits per byte against a baseline of
4.912**.

**The run does not beat the unigram baseline.** It ends 0.045 bits per byte above the unigram
entropy of exactly the held-out targets it is scored on, so the assertion in
`CausalLanguageModelTest` fails. The asserted loss and the asserted baseline come from the same
`NextTokenDataset` of every held-out window. Scoring only 12 windows, as earlier versions of the
test did, describes an easier part of the region: the in-sample unigram entropy of those 768
targets is 0.27 bits lower than that of all 5,312.

What was measured on the way, all on this corpus and scored on all 83 windows:

| Recipe | Held-out | Gap to 4.912 |
|--------|----------|--------------|
| Learning rate 3e-3 to 3e-4, stride 64 (the previous configuration) | 5.115 | +0.203 |
| Learning rate 1e-2 to 1e-3, stride 64 | 5.047 | +0.135 |
| Learning rate 1e-2 to 1e-3, spanning stride (the configuration above) | 4.957 | +0.045 |
| Learning rate 1e-2 to 3e-3, spanning stride | 5.097 | +0.185 |

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
- **The step budget is the limit.** 550 steps of batch 1 score about 35,000 target bytes in total.
  For reference, an interpolated count-based bigram fitted to the whole training region scores
  3.78 bits per byte on the held-out targets and a trigram 3.07, so a byte-level model of this
  corpus has a great deal of headroom; the transformer has not seen enough text to use it. More
  steps do not fit: the run takes about 34 minutes of a 38-minute test timeout.

**Timing** (Mac Studio, Apple silicon, Metal, FP32): compile 2 s; cold first step about 25 s
when the native kernels are already cached and about 130 s when they are not; warm about 3.6 s
per training step, about 395 s per epoch; scoring all 83 held-out windows takes 4 s.

**Where a step goes.** A forward pass takes about 0.03 s; the backward pass takes 3.2 to 4.2 s,
and the test thread spends it waiting for Metal command buffers to complete, so the cost is GPU
work rather than host overhead or garbage collection. The weight gradients of the generic
backward pass (`DefaultGradientPropagation`, through `GradientFeatures`) are formed from the
operator's full Jacobian with respect to each weight, multiplied by the output gradient and summed
over outputs. For a matrix multiply that is an iteration space of output size times weight size
times the inner dimension: about 17 billion terms for the 256 by 64 output projection, 9.7 billion
for each fused 192 by 64 QKV weight, and 4.3 billion for each feed-forward weight, where the
gradient itself needs about a million multiply-adds per weight. A weight gradient that uses the
structure of the matrix multiply (the output gradient times the transposed input) would make each
step orders of magnitude cheaper and is the change that would let this run train for long enough
to approach its headroom. Note that `OperationProfileNode` records Metal dispatch time rather
than kernel execution time, so a profile of this run shows only tens of milliseconds per step;
the shapes of the recorded operations are what identify the expensive kernels.

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

`CausalLanguageModel.generator` turns a compiled inference model of the same weights (for example
`buildModel(ParameterUpdate.disabled()).compile(false)`) into an `AutoregressiveModel<Integer>`,
built through the general constructor of `AutoregressiveModel` rather than its `of` factory,
which expects a model that takes one token's embedding and returns one vocabulary row.

Decoding uses a sliding window over the full-sequence model the weights were trained with. The
`(seqLen)` input holds the most recent tokens (at most `seqLen`) in device memory: each new token
is written as a single value, and the window slides by device-to-device copies, so the window is
never staged on the host. Each step runs one forward pass and samples from the log-probabilities of the last filled row with
`AutoregressiveModel.sampleToken`: the most probable byte at temperature zero, a tempered sample
otherwise. Positions after the filled prefix hold padding, which the causal mask keeps from
affecting the filled rows; once the text is longer than `seqLen` the window slides, so every pass
sees its tokens at positions `0..seqLen-1`, as every training window did. The window is cleared
(padding zeroed, so every position is a valid token id) when the generator restarts at step zero,
so `AutoregressiveModel.reset()` begins a new sequence.
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
continuation is 96 spaces: a model at roughly unigram quality decoded greedily repeats its most
frequent byte, and the space is the most frequent byte of the corpus. The test requires the
continuation to be reproducible and to contain more than one distinct byte, so this, too, fails
until the model improves; the generation path itself is verified by the fast test above.
