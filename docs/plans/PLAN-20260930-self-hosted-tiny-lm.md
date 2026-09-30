# Train a tiny byte-level causal transformer on the platform's own documentation

## Category

Proof of Value.

## Motivation

The Manager Log has named one proof-of-value milestone as "the first concrete step toward software
that studies itself" since March 2026: a **minimal end-to-end self-hosted training run**, meaning a
tiny language model trained on the platform's own documentation through `ModelOptimizer`. Every
prior cycle deferred it for the same reason. `docs/plans/FINE_TUNE_FAIL.md` said transformer
training was infeasible, with backward-pass compilation projected at "days, not minutes."

That reason is gone. The September 2026 re-baseline (`FINE_TUNE_FAIL.md` §"Current status",
merged in PR #582) withdrew the verdict. On the configurations measured, the cold first training
step of a 1–3-block LoRA `DiffusionTransformer` takes **13.6–20.8 s**, and it does not grow with
embed across 8 → 256. Each extra block adds about 1 s, and the warm step is 0.35–1.3 s. February
recorded 8 to over 44 minutes for the same configurations. Compile time no longer blocks training
a small transformer.

This cycle's survey found that what does block it now is **capability, not performance**. The
platform has never trained a transformer language model from scratch, and several pieces that
next-token training needs are missing or unverified:

| Need | Current state (verified against source this cycle) |
|------|------------------------------------------------------|
| Full-sequence **causal** self-attention | Missing. `AttentionFeatures.causalMask(shape, position)` masks relative to one scalar position, for KV-cache inference. `sequenceAttention` / `scaledDotProductAttention` are bidirectional and accept only a per-key `keyMask` and a `paddingMask`. |
| Gradients into **K and V** in `sequenceAttention` | Unverified and at risk. `sequenceAttention` writes K and V into fixed buffers (`k.andThen(into(kTensor))`, `v.andThen(into(vTensor))`), and `scaledDotProductAttention` reads them back as constants (`cp(k)`, `cp(v)`). The gradient may reach the fused QKV weight only through the Q slice. Existing training through attention (`AggressiveFineTuningTest`, LoRA) does not show otherwise, because it checks timing or MSE convergence, not per-slice gradients. |
| Trainable **token embedding** | Missing as a layer. `CollectionFeatures.rows(shape, table, rowIndex)` gathers rows (T5Gemma uses it for inference), but no test shows gradients reaching the table. Qwen3 looks up embeddings on the host, outside the graph. The known workaround is one-hot input into a `dense` layer (`MidiDataset` precedent). |
| **Byte/char tokenizer** | Missing. Every `Tokenizer` implementation (`Qwen3Tokenizer`, `BPE`, `SentencePieceTokenizer`, MIDI tokenizers) loads a pre-trained vocabulary. `ByteLevelEncoder` only maps bytes to unicode characters for BPE. |
| **Text next-token `Dataset`** | Missing. `MidiDataset` (studio/compose) is the closest template: token windows, one-hot input, next-token one-hot target. |
| Cross-entropy loss | Present. `logSoftmax` (`ActivationFeatures`, gradient-tested in `SoftmaxTests`) plus `NegativeLogLikelihood` gives cross-entropy. `NegativeLogLikelihood.loss` treats the leading dimension as a batch and averages per row, so a `(seqLen, vocab)` output gives mean per-position NLL. |
| Checkpointing | Present. `StateDictionary(Map)`, `put`, `save(Path)`, reload via `new StateDictionary(dir)`. |

This task is the smallest piece of work that turns "training a transformer is now possible" into
"the platform trained a language model on itself." It also yields general-purpose primitives —
causal sequence attention, a verified-trainable embedding, a byte tokenizer, a text dataset — that
every later LM-training step needs. These include training-mode Qwen3/Llama-style blocks, a
MIDI-LM from scratch, and eventually a model that grows with the codebase.

## Scope

The deliverable is a trained model with a measured learning curve, not a fluent model. Sampling
text from the trained weights through `AutoregressiveModel` is the **next** plan (see "What comes
after"). It needs its own KV-cache wiring and is not required to prove that training works.

1. **Full-sequence causal mask for sequence attention (general capability).**
   Add a causal option to the full-sequence attention path so position *i* attends only to keys
   *j ≤ i*. Build the lower-triangular mask as a **producer** (for example by comparing two
   `integers(...)` index producers with `greaterThan`/`lessThan` and folding it into the existing
   logit-masking path that `keyMask` already uses), never with a Java loop or `setMem`. The mask
   should apply wherever the logits are formed, so `sequenceAttention`, `scaledDotProductAttention`
   and `TransformerBlockFeatures.transformerBlock` can all use it without copies.
   - **Placement:** `AttentionFeatures.java` is already 1826 lines, above the 1500-line
     recommendation. The new capability must not grow it. Put the causal-mask construction in a
     focused feature interface in `engine/ml` (or on the existing mask-building code if a better
     home turns up during the discovery step). If threading the flag through requires touching
     `sequenceAttention`'s long overload list, the implementer should take the chance to move the
     sequence-attention family out of `AttentionFeatures` into its own mixin, so the file shrinks.
     That is the orderly refactor the file-length advisory asks for.
   - Tests: a forward test that sets the value vector at a future position to a sentinel and shows
     it cannot affect earlier outputs; and a test that causal and bidirectional outputs match at
     the last position (where the mask removes nothing).

2. **Verify, and if necessary fix, gradient flow to K and V in sequence attention.**
   Write a gradient test on `sequenceAttention` (tiny shapes: seqLen 4, dim 8, 2 heads). It
   compares the analytic gradient of a scalar loss with respect to `toQkvWeight` against a
   finite-difference estimate, **separately for the Q, K and V slices**. Take the finite differences
   in the test method, at the top of the call stack. If the K/V slices get zero or wrong gradients
   because of the `into(kTensor)` / `cp(k)` buffering, fix the attention block so K and V flow into
   the dot products as producers rather than host-side constants. This must keep inference behavior
   and the existing `sequenceAttention` tests unchanged; run their relevant methods one at a time.
   Before editing any autodiff internals, check the `feature/lora-gradients` family
   (`docs/plans/LORA_GRADIENTS_REDUCTION.md`), and stay out of its sparse-Jacobian projections,
   `Sum` reordering budget and memoization gating. A fix that needs those is a finding to record,
   not a change to make here.
   **This is the highest-risk item. Do it first**, because if K/V gradients cannot be made to flow
   without core autodiff work, the rest of the plan must scope down (see Open questions).

3. **Trainable token embedding.** Write a gradient test for `rows(...)` with respect to its
   `table` argument. If gradients reach the table, add a small `embedding(vocabSize, dim, table)`
   layer (the obvious home is `LayerFeatures`/`domain/graph`, next to `dense`, since it is not
   ML-specific) and use it. If they do not, and the fix is not small and self-contained, use the
   **one-hot → `dense` (no bias)** formulation. It is mathematically the same embedding. Record which
   path was taken and why, and store the `rows()` gradient result as a memory either way.

4. **`ByteTokenizer` implementing `Tokenizer`.** A fixed 256-symbol byte vocabulary, lossless for
   any UTF-8 input (`decode(encode(s)).equals(s)`), in `engine/ml`'s `tokenization` package. No
   training and no merges. It is the simplest tokenizer that needs no pre-trained vocabulary, so
   the model owes nothing to an external artifact. Unit tests: ASCII, multi-byte UTF-8, an empty
   string, and all 256 byte values.

5. **Text next-token dataset.** A `Dataset<PackedCollection>` that takes a token array and a
   context length and yields `ValueTarget`s of (input window, next-token targets): input
   `(seqLen)` token ids or one-hot `(seqLen, vocab)` rows, depending on step 3's outcome; targets
   one-hot `(seqLen, vocab)`. It has a deterministic train/held-out split. Build one-hot rows with
   producers, as `MidiDataset` does, not with element loops. Where it belongs is a placement
   decision to make before writing (`engine/ml` next to `DiffusionTrainingDataset` is the default).
   Check `MidiDataset` for logic that should be shared rather than duplicated.

6. **Tiny causal LM assembly and a first real training run.**
   - Model: byte vocab 256, context 64, embed 64, 4 heads, depth 2, pre-norm RMSNorm, RoPE,
     SiLU/GLU feed-forward from `FeedForwardFeatures`, output projection to 256, then `logSoftmax`.
     Batch 1 (`scaledDotProductAttention` rejects `batchSize != 1`). A 64-position window already
     gives 64 predictions per step. All computation is `CollectionProducer` composition, following
     the project's fundamental rule.
   - Corpus: a **fixed, listed** set of the platform's own documentation, for example the
     `docs/internals/*.md` pages (about 11k lines) or a named subset if throughput calls for it. The
     file list goes in the test so the run is reproducible. It is read at the test boundary.
   - Training: `ModelOptimizer` with `NegativeLogLikelihood` and Adam. `ModelOptimizer` owns the
     loop; no epoch loop outside it. It should use the patience/convergence helpers in
     `ModelTestFeatures` where they fit.
   - Measurement: report held-out loss in **bits per byte** at fixed checkpoints, next to two
     baselines computed from the same held-out split: the uniform baseline (8 bits/byte) and the
     **unigram byte-entropy** baseline. Record wall-clock time per step (cold and warm), total
     steps, host and backend, and the commit SHA.
   - Save the trained weights with `StateDictionary.save(...)` under the module's `results/`
     directory, then reload them and check that the reloaded model gives the same held-out loss.
   - Test hygiene: the long training run is its own `@Test` method in a class extending
     `TestSuiteBase`. It has an explicit JUnit timeout strictly below the 40-minute runner budget
     and `@TestProperties(excludeProfiles = TestUtils.PIPELINE)` so CI does not run it. Every
     primitive in steps 1–5 gets fast unit tests that **do** run in CI.

7. **Record the result.** Add a short section on training a model from scratch to
   `docs/internals/training-loop-examples.md`, or a new internals page if it does not fit. It
   covers the causal-attention option, the embedding path, the byte tokenizer, the dataset, and
   the measured learning curve, citing code by stable identifier and not by line number. Store a
   `progress` memory with the numbers.

**Out of scope:** text generation/sampling from the trained model (next plan); BPE tokenizer
training; batch > 1 attention; multi-node or distributed training; models above about 1M
parameters; any change to the `feature/lora-gradients` autodiff work; the small-reduction
native-compile lever named in `FINE_TUNE_FAIL.md` (a separate performance plan if this run shows it
matters).

## Approach

1. **Risk first.** Steps 2 (K/V gradients) and 3 (embedding gradients) decide the plan's shape.
   Run them before any assembly work. Each is a small gradient test with a clear pass/fail.
2. **Discovery before new types.** Before writing each new method or class, search for an
   equivalent (mask builders, one-hot builders in `MidiDataset`, `ModelFeatures`' classifier
   head). Place each at the most general rung where it makes sense. For example, a causal mask is
   generic attention machinery, an embedding layer is generic graph machinery, and a byte tokenizer
   is a `Tokenizer`.
3. **Producers everywhere in the model.** `.evaluate()` appears only in tests and at the dataset
   and step boundaries. The corpus file read and the bits-per-byte arithmetic on reported scalar
   losses happen at the test boundary.
4. **Use `ar-profile-analyzer` for wrong values.** If the loss is NaN, stays flat, or a gradient
   test fails, inspect the generated kernel source and argument bindings before adding `log()`
   probes.
5. **One test per invocation** through `mcp__ar-test-runner__start_test_run`, each with a timeout
   of at most 40 minutes. Run the build validator (`checkstyle`, `code_policy`, `test_timeouts`,
   `duplicate_code`) before declaring done.

## Success Criteria

- A causal option for full-sequence attention exists, is built from producers, is covered by a
  CI-running forward test proving no future-position leakage, and does not grow `AttentionFeatures`.
- A CI-running gradient test shows that `sequenceAttention` delivers correct gradients (within a
  stated finite-difference tolerance) to the Q, K **and** V slices of `toQkvWeight`, with any fix
  needed to get there. The existing `sequenceAttention` tests still pass.
- A trainable token embedding is in use, either `rows()`-based with a passing gradient test or
  one-hot → `dense`, and the choice is recorded with evidence.
- `ByteTokenizer` and the text next-token dataset exist, with CI-running unit tests.
- One end-to-end training run of the tiny causal LM on the listed documentation corpus completes
  inside the runner budget, and its held-out loss ends **below the unigram byte-entropy baseline**
  for that split. The report gives the measured curve, both baselines, step timings, host, backend
  and commit SHA. If the run does not beat unigram, the plan is not complete. The investigation
  into why (from profile and gradient evidence) and the recorded outcome become the deliverable,
  and the success claim is not made.
- The trained weights round-trip through `StateDictionary` save/load with the same held-out loss.
- The build validator is clean. No existing test is weakened.

## Dependencies

- None blocking. The compile-time prerequisite (the September 2026 re-baseline) is on `master`.
- Coordinate around, do not depend on: `feature/lora-gradients` (autodiff internals), and any
  in-flight branch that edits `AttentionFeatures`. Check `git branch -r` and open PRs at start.

## Open questions

- **If K/V gradients need core autodiff changes**, which item would the approver prefer? (a) Scope
  this plan down to steps 1, 3, 4 and 5 plus a training run that exercises attention with fixed
  (non-trainable) K/V projections, stated plainly as such. (b) Split the K/V gradient fix into its
  own plan first. This plan recommends (b) if the fix touches `delta()` machinery.
- **Corpus size versus budget.** The full `docs/internals` corpus may be larger than a
  40-minute run can use. The implementer picks a fixed subset that gives a meaningful held-out
  split, and records the choice. Beating unigram is the bar, not converging on the whole corpus.
- **Numerical stability of `logSoftmax`.** `ActivationFeatures.logSoftmax` computes
  `x − log(Σ exp(x))` without subtracting the max. At vocab 256 and small initial weights this
  should be fine. If NaNs appear, a max-subtracted variant is an in-scope fix, because it is
  generic and belongs in `ActivationFeatures`.

## Estimated Complexity

**Large**, but in stages with early exits. Steps 2–3 (gradient verification) are small and decide
the rest. Steps 1, 4 and 5 are small to medium, self-contained primitives. Step 6 is the
integration run. If step 2 finds a deep autodiff problem, the plan stops at a well-evidenced
finding plus the independent primitives. That is still a complete, valuable outcome.

## What comes after

1. **Sampling from the trained model.** Wrap the trained weights in an `AutoregressiveModel`
   (KV-cache inference path, reusing the `StateDictionary` from step 6) and generate text. This is
   the first time the platform "speaks" about itself.
2. **Scale the run.** Larger context and depth, batch > 1 in `scaledDotProductAttention`, and
   whatever the step-6 profile names as the dominant per-step cost (possibly the small-reduction
   native-compile lever from `FINE_TUNE_FAIL.md`).
3. **Grow with the platform.** Continued training as documentation changes, and evaluation of the
   model on questions about the codebase, toward the self-understanding goal.
