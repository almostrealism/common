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
collection and wait briefly for releases before rejecting the allocation. `AdamOptimizer` also
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
| Corpus | Six `docs/internals` pages (`features-pattern`, `producer-evaluable-pattern`, `shape-and-traversal`, `collection-producer-operations`, `state-dictionary`, `end-to-end-computation`), 52,812 bytes |
| Split | First 90% of the bytes for training, last 10% held out, split before windowing |
| Model | Byte vocabulary 256, context 64, embed 64, 4 heads, depth 2, feed-forward width 128, full rotary embedding (base 10000), no query/key normalization, final RMS norm; 115,008 parameters |
| Initialization | Normal(0, 0.02) for embedding and projections, ones for norm scales, seed 20260930 |
| Optimizer | Adam, betas 0.9 / 0.999; learning rate decaying linearly per epoch from 3e-3 to 3e-4 |
| Data per epoch | 110 non-overlapping training windows, rotating through all 742 windows of the training region; 12 fixed held-out windows |
| Length | 5 epochs, 550 steps (one step per 64-byte window, batch 1) |

**Measured curve** (bits per byte, from the per-epoch mean NLL divided by ln 2). Baselines: uniform
8.0; unigram byte entropy of the 768 targets the 12 held-out windows score, 4.600 (of the whole
held-out region, 4.907).

| Epoch | Train | Held-out |
|-------|-------|----------|
| 0 | 5.365 | 5.056 |
| 1 | 4.833 | 4.766 |
| 2 | 4.822 | 4.720 |
| 3 | 4.604 | 4.649 |
| 4 | 4.628 | 4.691 |

**The run does not beat the unigram baseline.** The held-out loss ends 0.09 bits per byte above
the unigram entropy of the bytes it was scored on. It was first reported as 0.22 bits below the
baseline, but that compared the 12 scored windows against the entropy of the whole held-out
region, whose bytes are harder to predict than those windows. `CausalLanguageModelTest` now takes
its baseline from `NextTokenDataset.scoredTargetEntropyBits()`, which counts exactly the scored
targets, so this configuration fails it. For calibration, a unigram model fitted to the training
region scores 4.78 bits per byte on the scored targets (5.15 on the whole held-out region): the
model beats unigram frequencies learned from the training text, but not the in-sample entropy of
the held-out bytes. An interpolated count-based bigram model scores about 3.85 on the held-out
region, so there is much room left. Scoring every held-out window (82 at this stride), or training
further, are the next steps.

**Timing** (Mac Studio, Apple silicon, Metal, FP32): compile 2.5 s, cold first step about 113 s,
warm about 3.2 s per window (dominated by the attention backward pass), about 386 s per warm epoch
including validation, 34 minutes in total.

**Checkpoint.** The trained weights are saved with `StateDictionary.save` (FP32, the training
precision on this backend) and reloaded into a new model; the reloaded held-out loss matched the
trained one exactly (the test allows a relative difference of 1e-4).

### What the run taught

- With one or two passes over a fixed set of windows, the model stalls at the unigram rate; with
  many passes over a small set it memorizes those windows and the held-out loss rises. Rotating
  through the whole training region at a decaying learning rate is what moved the held-out loss
  below the region's unigram rate within the step budget.
- `ModelOptimizer` re-checks the first window of every epoch after its update and throws if that
  window's loss rose. A memorization probe on four windows tripped it after about 110 steps, once
  the loss was below one bit per byte; the budgeted run did not trip it.
