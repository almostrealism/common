# Make the self-hosted language model beat its unigram baseline and generate text

**Category:** Proof of Value
**Branch:** `project/plan-20261005-185050` (created from `master`)
**Status:** Implemented in part — Deliverables 2 and 3 are done; Deliverable 1 is unmet (held-out
4.957 vs baseline 4.912 bits/byte). See "Implementation outcome" below.
**Estimated complexity:** Medium–Large
**Requires:** Metal (macOS node) — see `plan-20261005-185050-workstream.yaml` and "Hardware" below.

---

## Motivation

The previous proof-of-value cycle (PR #596, `project/plan-20260930-180401`) did something the
platform had never done before: it trained a decoder-only transformer language model from random
weights, on the platform's own documentation, end to end, with every gradient flowing through the
same computation-graph machinery the rest of the platform uses, and it round-tripped the trained
weights through `StateDictionary`. That is a real milestone and it is the first concrete step of
the self-understanding goal — software that studies itself.

But the run stopped one step short of its own headline promise, and it stopped before the model
ever produced a single byte of text. Both gaps are recorded honestly in the code and the docs:

1. **It does not beat the unigram baseline.** `CausalLanguageModelTest.trainOnDocumentation`
   asserts the held-out loss ends below the unigram byte-entropy of the bytes it is scored on
   (`finalBits < unigramBits`). The documented configuration ends at **4.691 bits/byte held-out
   against a 4.600 unigram baseline** (`NextTokenDataset.scoredTargetEntropyBits()` over the 12
   scored held-out windows), so the assertion fails. The test is long-running and excluded from the
   CI pipeline profile, so it does not break CI — but it is a known-failing acceptance test, and
   the success criterion of the last cycle is unmet.

2. **The model has never generated text.** The general autoregressive sampling infrastructure
   exists (`AutoregressiveModel`, with device-resident position, temperature/greedy sampling, a
   static `sampleToken` with top-p, and a reusable `tokenLoader`), but `CausalLanguageModel` only
   knows how to build the full-sequence
   `(seqLen) → (seqLen, vocab)` *training* model. There is no path that takes the trained weights
   and emits text. The platform has not yet "spoken" about itself.

`training-a-language-model.md` already names the headroom and the levers: a unigram model fitted to
the *training* region scores 4.78 on the scored targets (the model beats that), while an
interpolated count-based **bigram scores about 3.85 on the held-out region** — so a small
transformer that genuinely models local structure should be able to clear the unigram bar with room
to spare. The doc's own closing line: *"Scoring every held-out window (82 at this stride), or
training further, are the next steps."*

This task turns "the platform trained a language model on itself" into "the platform trained a
language model on itself that predicts held-out text better than its own byte frequencies, and then
generated text from it." That is the honest completion of the milestone and the natural setup for
everything after it (sampling quality, scale, and eventually a model that reads the platform and
helps change it).

## How this fits the sequence

- **Before:** causal sequence attention, trainable embedding, byte tokenizer, next-token dataset,
  multi-row cross-entropy, and the end-to-end training run all landed in PR #596. The primitives
  are in place and unit-tested.
- **This task:** cash in those primitives — (a) drive the held-out loss genuinely below the unigram
  baseline, and (b) generate text from the trained weights for the first time.
- **After:** sampling quality and longer/structured generation; batch > 1 and larger context/depth
  in `scaledDotProductAttention`; whichever per-step cost the training-run profile names (the
  standing small-reduction native-compile lever). A later cycle can let a trained model read the
  platform's own source, which is the deeper self-understanding aim.

## Category assessment (priority order)

- **Documentation — excellent.** Every layer/domain/engine module has a README; `docs/internals/`
  tells the whole Producer → process-tree → compilation → dispatch → released-memory story, and now
  includes `training-a-language-model.md`. The one standing gap (the ONNX-induced-pressure research
  note) is low priority and out of scope here. The documentation touched by *this* task is the
  training page, which the task updates as part of its own deliverables (not a separate doc cycle).
- **Code quality — strong and owned.** The continuous QA pipeline merges fixes daily and
  enforcement is mechanical (checkstyle, code_policy, duplicate_code, test-integrity). No neglected
  high-value flagship here.
- **Performance — one evidenced, deferred lever** (13 small reductions compiled as native kernels
  at ~250 ms each). Real but a one-time per-JVM cost; the log has deliberately deferred it to
  *follow* a real training run rather than lead it. This task produces exactly such a run and is the
  right place to re-check whether that lever matters in practice (see "Optional profiling" below).
- **Proof of value — the category.** A known-failing acceptance test and a model that has never
  generated text are precisely the kind of concrete, falsifiable gap this category exists to close.

## Scope

Three deliverables, in order. The first is the hard requirement, the second is the payoff, and the
third records both in the training documentation.

### Deliverable 1 — Beat the unigram baseline (honestly)

Make `CausalLanguageModelTest.trainOnDocumentation` pass its existing assertion
(`finalBits < unigramBits`) by genuinely improving the model's held-out prediction, **not** by
weakening the test. The assertion, the baseline source (`scoredTargetEntropyBits()`), and the
apples-to-apples contract (model loss and unigram baseline must cover the *same* scored bytes) are
the specification and must be preserved or strengthened, never loosened.

**Precondition — representative held-out measurement (required, not a lever).** Score every
held-out window at the current stride (≈82: the held-out region is the last 10% of 52,812 bytes,
windowed at stride 64), versus the current cap of 12 (`HELD_OUT_WINDOWS` in the test, passed as
`maxWindows` to the held-out `NextTokenDataset`; a non-positive `maxWindows` removes the cap).
`scoredTargetEntropyBits()` recomputes over exactly the targets those windows score, so the
comparison stays apples-to-apples, and the estimate describes the whole held-out region instead of
a 12-window sample. This is strictly more evaluation, not a loosening — but it is **not** a way to
pass: it moves both sides of the comparison, and the existing evidence suggests the rest of the
region is harder than the 12 scored windows (in-sample unigram 4.907 on the whole region vs 4.600
on the scored targets; training-fitted unigram 5.15 vs 4.78). The model's all-window number may
therefore end up further from or closer to its baseline than the 12-window gap; reproduce it before
tuning anything.

Two costs to plan for:
- **Runtime.** Today validation runs at every epoch boundary (`ModelOptimizer` with
  `setValidationDataset`), so 82 windows instead of 12 adds 70 forward passes per epoch. The warm
  rate is ~386 s per epoch for 122 windows; even if a validation window costs only a third of a
  training step, five epochs gain several minutes, and at full step cost they gain ~18 minutes —
  past the test's 38-minute timeout. A cheap way to keep the fidelity without the cost: keep a small
  per-epoch validation set for the progress curve and score the full held-out set once, at the end,
  with `ModelOptimizer.evaluate(...)` (already used by the reload check), asserting against that
  set's `scoredTargetEntropyBits()`.
- **The baseline used by the assertion must come from the same dataset whose loss is asserted.** If
  the per-epoch and final sets differ, the assertion compares the final-set loss with the final-set
  baseline; the per-epoch numbers are reporting only.

Evidenced levers, to be applied and measured on that representative measurement (the implementer
chooses the combination that clears the bar with margin; all must keep the comparison honest):

1. **Train further / better schedule.** More epochs and/or a tuned learning-rate schedule, within
   the step budget the timing allows. The doc already found that rotating through the whole training
   region at a decaying rate is what moved held-out loss below the region's unigram rate; push that
   further without tripping `ModelOptimizer`'s post-update check (after an update, the first
   sample's loss must not have increased; its documentation treats a failure as a sign of
   gradient-computation trouble).
2. **More capacity or more corpus, within the memory budget.** The feed-forward input-projection
   Jacobian dominates device memory (grows with the square of the FF width), which is why width is
   128 today. Prefer adding corpus (more `docs/internals` pages, listed in the test's `CORPUS`
   array) and/or modest depth/context before widening the feed-forward. Adding corpus changes the
   90/10 split, so the held-out region and its baseline change too; the 4.691/4.600 starting point
   is not comparable across corpora, and the doc must report the new baseline alongside the new
   loss. Any configuration change must stay within `AR_HARDWARE_MEMORY_SCALE=6` and the test's
   existing timeout: `trainOnDocumentation` is annotated `timeout = 38 * 60000` today, and the
   current run is ~34 min, so the real headroom is about four minutes, not six. Raising the timeout
   to the 40-minute per-invocation cap is the most it could ever move, and any raise is a timeout
   change that review will scrutinise; a recipe that needs more time than that has to buy it back
   elsewhere (fewer validation passes, as above, or cheaper steps).

**Target:** held-out bits/byte **comfortably below** the unigram baseline on the representative
(all-window) measurement — not a hairline pass. The bigram reference (~3.85) is the north star for
how much structure a byte-level model of this corpus can capture; aim to close a meaningful fraction
of the gap between unigram (~4.6–4.9) and bigram (~3.85).

### Deliverable 2 — Generate text from the trained weights (the platform speaks)

Give `CausalLanguageModel` a generation path and prove it produces text.

1. **A generation model builder on `CausalLanguageModel`.** Add a method that builds a model
   suitable for autoregressive decoding from the same `StateDictionary` the training model uses, so
   the trained weights drive generation without re-export. This is a method on `CausalLanguageModel`
   (the type that owns the weights and architecture), not a new utility/exporter class. Two honest
   implementation shapes are acceptable; the implementer picks based on what the attention seam
   already supports and consults before deciding:
   - **Single-position KV-cache decode** via the `AttentionFeatures.attention(...)` family (the
     `/pdsl/attention.pdsl` asset with its `causal_mask(position)` stage, backed by
     `AttentionFeatures.causalMask`; see `docs/internals/ml-inference-pipeline.md`, not the training
     page, which describes only the full-sequence `causalLogitMask`). Preferred in the long run, but
     the trained weights are **not** directly compatible as stored: `CausalLanguageModel` keeps one
     fused `qkv` weight of shape `(3 * dim, dim)` per block and the rotary inverse frequencies
     (`rope_inv_freq`), while the single-position `attention(...)` overloads take separate `wq`,
     `wk`, `wv` and their own rotary inputs. This path needs the fused weight split into views (not
     copies, so training and generation share one `StateDictionary`), the rotary inputs derived from
     `rope_inv_freq`, and a check that the single-position rotation convention matches the
     full-sequence one the model was trained with. It also needs the token embedding applied
     outside the model, which is what `AutoregressiveModel.of(...)` expects.
   - **Full-sequence sliding-window decode** that reuses the existing full-sequence model, feeds the
     growing prefix window, and reads the log-probabilities at the last filled position. Simpler and
     provably consistent with the trained forward pass: the causal mask means positions after the
     prefix (padding) cannot affect earlier rows, and once the text exceeds `seqLen` the window
     slides, which matches training (every window was scored with positions `0..seqLen-1`
     regardless of its offset in the corpus). Each generated byte costs one full `seqLen` forward
     pass. Acceptable as the first generation path if the KV-cache variant needs core work; if this
     path is taken, note the KV-cache follow-up explicitly.

   Reuse `AutoregressiveModel` and `ByteTokenizer`; do not duplicate sampling or tokenization
   logic. Note that the convenience factory `AutoregressiveModel.of(CompiledModel, position,
   tokenEmbed)` does not fit this model as built today: it assumes the compiled model takes one
   token's *embedding* as input and that the model's whole output is one vocabulary row
   (`vocabSize = model.getOutputShape().getTotalSize()`), whereas `CausalLanguageModel.buildModel`
   takes `(seqLen)` token ids, embeds them internally, and outputs `(seqLen, vocabSize)`. The
   sliding-window path therefore goes through the general `AutoregressiveModel` constructor (token
   consumer, forward supplier, sample function), not `of(...)`. The output is log-probabilities
   rather than raw logits; greedy argmax and temperature sampling are unaffected by that shift.

2. **A generation test** (long-running, excluded from the CI pipeline profile, same as the training
   run; or a fast test if a tiny deterministic fixture is feasible). Load trained weights (or train
   briefly), seed with a short documentation prompt, generate **greedily** (deterministic) for a
   fixed number of bytes, and assert:
   - the output is valid UTF-8, checked with a strict decoder (a `CharsetDecoder` for UTF-8 with
     `CodingErrorAction.REPORT` for malformed and unmappable input). `ByteTokenizer.decodeAsLong`
     is **not** such a check: it decodes with standard replacement, turning a malformed sequence
     into `U+FFFD` instead of failing (its class javadoc says so, and
     `ByteTokenizerTest.malformedDecode` pins that behaviour), so a decode or round trip through it
     succeeds on invalid bytes. See "Open questions" on whether this should be an assertion at all,
     and
   - the output is non-trivial (not a constant byte / not the prompt echoed), and
   - a reproducibility check: the same weights + prompt + greedy decode produce identical bytes.
   Optionally, a weak quality signal: greedy continuation of a held-out prefix matches more next
   bytes than a unigram-argmax baseline over the same prefixes. Keep any such assertion robust (a
   margin, not an exact string), to avoid brittleness.

3. **Make the generated text visible.** Log a short generated sample to the test's results file so a
   human (and a future model) can read what the platform wrote about itself.

### Deliverable 3 — Update the training documentation

Update `docs/internals/training-a-language-model.md` to reflect the passing configuration: the new
measured curve, the representative (all-window) held-out number and baseline, the final
configuration, and a new short section on generation (how `CausalLanguageModel` is decoded, with a
small generated sample). Keep the honest-accounting tone of the current page; if any lever did *not*
help, say so. Follow `docs/CLAUDE.md`: no source line-number citations, reference code by stable
identifiers.

## Approach

1. **Consult first** (`mcp__ar-manager__consult`) on `CausalLanguageModel`, `AutoregressiveModel`,
   `NextTokenDataset`, the `causalMask` single-position attention variant, and `ByteTokenizer`; and
   read the ar-docs ml module. Recall this branch's memories and `workstream_context`.
2. **Reproduce the baseline run first** (`trainOnDocumentation`) on Metal with
   `AR_LONG_TESTS=enabled` and `AR_HARDWARE_MEMORY_SCALE=6`, to confirm the 4.691/4.600 starting
   point on this environment before changing anything.
3. **Deliverable 1:** apply the representative-measurement change, then iterate on
   schedule/epochs/corpus/capacity, re-measuring each change. Record the curve. Stop when held-out
   is comfortably below baseline with headroom under the test's existing 38-minute timeout.
4. **Deliverable 2:** add the generation builder and test; verify greedy determinism (and, if
   asserted, strict UTF-8 validity); capture a sample.
5. **Deliverable 3:** rewrite the measured-curve and configuration sections of the training doc and
   add the generation section.
6. **Verify** with the targeted tests (see Success Criteria) and the build validator before
   finishing. Never run the full suite.

### Optional profiling (only if time remains and clean)

With a genuine passing training run in hand, use `ar-profile-analyzer` on a profiled reproduction to
name the dominant per-step backward cost and confirm whether the deferred small-reduction
native-compile lever is worth its own performance plan. This is reporting only; do not undertake the
optimization in this task.

## Success Criteria

- `CausalLanguageModelTest.trainOnDocumentation` **passes** its existing `finalBits < unigramBits`
  assertion on the representative (all-window) held-out measurement, with the model comfortably
  below the unigram baseline — achieved by improving the model, with **no** weakening of the
  assertion, tolerance, dimensions, `@TestDepth`, or timeout (today `38 * 60000`; never above the
  40-minute per-invocation cap), and no `@Disabled`. The asserted loss and the asserted baseline
  come from the same held-out dataset object.
- A new generation path on `CausalLanguageModel` plus a generation test that: loads/produces trained
  weights, generates greedily and deterministically from a documentation prompt, asserts
  non-triviality and reproducibility (and strict-decoder UTF-8 validity, if the open question below
  is settled in favour of asserting it); and logs a readable generated sample.
- `training-a-language-model.md` updated with the new curve, configuration, representative baseline,
  and a generation section (stable identifiers only, no line numbers).
- No code duplication (reuses `AutoregressiveModel`, `ByteTokenizer`, `NextTokenDataset`); the
  generation builder lives on `CausalLanguageModel`, not in a new utility/exporter class.
- `mvn clean install -DskipTests` succeeds; build validator (checkstyle, code_policy,
  test_timeouts, duplicate_code) is clean.

## Integrity notes (read before implementing)

This task sits exactly on top of a known-failing acceptance test, which is the highest-risk place
for the deception patterns in `CLAUDE.md § AGENT INTEGRITY`. The following are **out of bounds** and
would fail `test-integrity-check` and review:

- Loosening the `finalBits < unigramBits` assertion, or changing the baseline so a worse model
  "passes" (e.g. comparing the model's 12-window loss against the whole-region entropy — the
  mismatch the last cycle explicitly corrected).
- Raising `@TestDepth`, inflating the timeout past the cap, shrinking model dimensions to make the
  test trivial, or `@Disabled`.
- Removing the `TODO(review)` comment above the assertion without the assertion actually passing;
  that comment records the known failure and goes only when the failure does.
- Claiming "beats baseline" from a non-representative or mismatched measurement.

The only acceptable way to turn this test green is to make the model genuinely predict held-out
documentation bytes better than their own frequencies, measured apples-to-apples. If, after honest
effort within the memory and time budget, the model cannot clear the bar, the correct outcome is a
precise, evidenced finding (what was tried, the curves, the limiting factor) handed back for a
revised plan — **not** a green test.

## Hardware

The end-to-end training run and its measured curve are validated on Apple-silicon **Metal** (FP32),
with `AR_HARDWARE_MEMORY_SCALE=6` and a cold-step/warm-step timing profile that fits the test's
38-minute timeout (~34 min today; the per-invocation cap is 40 minutes). Tuning the training
recipe and measuring generation need that same backend to reproduce the numbers the plan is judged
against. Hence the workstream requires a macOS node (`requiredLabels: platform: macos`).

## Dependencies

None beyond `master`. All prerequisite primitives (causal sequence attention, trainable embedding,
byte tokenizer, next-token dataset, multi-row cross-entropy, `AutoregressiveModel`,
`StateDictionary` checkpointing) already landed in PR #596. This task does not touch the
PDSL-for-research / Qwen interpretability track (PR #615, already merged to `master`); it is the
continuation of the from-scratch self-hosted LM track. The previous plan for this track is
[`PLAN-20260930-self-hosted-tiny-lm.md`](PLAN-20260930-self-hosted-tiny-lm.md); no other plan in
`docs/plans/` covers generation from `CausalLanguageModel` or the unigram-baseline gap.

## Open questions

These are for the person approving the plan; the implementer should not settle them silently.

1. **Is "comfortably below" achievable inside the budget?** The only evidence of headroom is the
   count-based bigram (~3.85 on the held-out region). A 115k-parameter model trained for 550 steps
   of batch 1 has seen ~35k target bytes — less than one pass over the 47.5k-byte training region —
   and the run is already within four minutes of its timeout. More epochs are the cheapest lever
   but the most time-bound one. If the honest outcome is a hairline pass or a miss, the plan's own
   rule applies (an evidenced finding, not a green test); the approver may prefer to state up front
   what margin counts as "comfortable" (for example a fixed number of bits/byte below the
   all-window baseline) so the result is not judged after the fact.
2. **Should the training run stay one test?** Training, checkpointing, the baseline assertion and
   now generation all depend on one ~34-minute run. The generation test either retrains (another
   long run plus the ~113 s cold first step) or loads `results/causal-language-model/weights.pb`,
   which `trainOnDocumentation` writes but which is not committed and does not exist on a fresh
   checkout, so a test that loads it depends on test ordering. The approver should choose: train
   briefly inside the generation test (weights quality irrelevant to the determinism checks), or
   accept an ordering dependency and say so.
3. **Should UTF-8 validity be asserted?** It is a property of what the model chooses to emit, not
   of the generation code: a byte-level model can legitimately pick a lone continuation byte, and
   the corpus contains multi-byte characters (em dashes and box-drawing characters). Greedy
   decoding of a weak model will usually stay in ASCII, but an assertion that can fail on a
   correct implementation is brittle. An alternative is to report validity (and the number of malformed sequences) in the
   logged sample and assert only determinism and non-triviality.
4. **Greedy degeneration.** A small byte model decoded greedily commonly falls into a short
   repeating loop (`"the the the "`). "Not a constant byte and not the prompt echoed" will pass on
   such output, so it proves the path runs, not that the text is good. That is acceptable for a
   first path, but the plan should not present the sample as evidence of quality; the optional
   "beats unigram-argmax next-byte accuracy" signal is the only quality check proposed.
5. **Which generation shape?** The sliding-window path is cheap to build and provably consistent
   with training; the KV-cache path needs a weight split, a rotary-input derivation and a
   rotation-convention check (see Deliverable 2) and is core work in its own right. The approver
   may prefer to fix the first path to sliding-window now and leave KV-cache decoding for the
   follow-up named in `MANAGER_LOG.md`, rather than leave the choice to the implementer.

## Implementation outcome (2026-10-06)

**Deliverable 1 — not met; evidenced finding.** The representative measurement is in place: the
asserted loss is `ModelOptimizer.evaluate` over all 83 held-out windows, against
`scoredTargetEntropyBits()` of the same dataset (4.912 on the current 53,465-byte corpus); the
12-window set remains as the per-epoch curve only. Four full Metal runs (all-window held-out):

| Recipe | Held-out | Gap |
|--------|----------|-----|
| lr 3e-3 to 3e-4, stride 64 (previous configuration, reproduced) | 5.115 | +0.203 |
| lr 1e-2 to 1e-3, stride 64 | 5.047 | +0.135 |
| lr 1e-2 to 1e-3, `NextTokenDataset.spanningStride` (committed) | 4.957 | +0.045 |
| lr 1e-2 to 3e-3, spanning stride | 5.097 | +0.185 |

The largest lever was coverage: at stride 64 the 550 steps read only the first 35 kB of the
training region and never reached `end-to-end-computation`, the page whose tail is held out (an
ideal count bigram on exactly the bytes read: 4.50 at stride 64 vs 3.86 at the spanning stride).
The limiting factor is the step budget: ~550 batch-1 steps (~35k target bytes) fit the 38-minute
timeout because each step costs ~3.6 s. Profiling (thread dumps + operation shapes) shows the
backward pass is GPU-bound in weight gradients formed from full Jacobians
(`DefaultGradientPropagation` / `GradientFeatures.combineGradient`): ~17G-term iteration spaces
for the 256x64 output projection where the gradient needs ~1M multiply-adds. A matmul-structured
weight gradient is the change that would buy the steps; it is core autodiff work and was not
undertaken here, per this plan's scope. Capacity and corpus levers were not tried: the model is
data-starved, not capacity-bound, and more corpus does not add steps. The assertion and its
`TODO(review)` comment remain (comment updated with the current numbers); nothing was loosened.

**Deliverable 2 — done (sliding-window shape).** `CausalLanguageModel.generator(CompiledModel,
Random)` returns an `AutoregressiveModel<Integer>` through the general constructor; the fast
test `slidingWindowGenerationMatchesFullForward` proves every greedy token (including after the
window slides) equals the argmax of an independent full forward with different padding, and that
greedy decoding is reproducible after `reset()`. `trainOnDocumentation` generates from the
reloaded checkpoint and logs the sample with strict-decoder UTF-8 validity (reported, not
asserted — open question 3). With the current model the greedy continuation is 96 spaces (the
corpus's most frequent byte). The generation checks run before the baseline assertion, so they
are evaluated: the continuation is reproducible, and the run stops at the non-triviality check
("generation is a single repeated byte") before reaching the baseline assertion. Both criteria
stay unmet until the model improves. KV-cache decoding remains a follow-up.
Open question 2 was settled without an ordering dependency: generation runs inside
`trainOnDocumentation` on the checkpoint that run just saved and reloaded, and the determinism of
the generation path itself is proven on random weights by the fast test. The generator owns its
device buffers and releases them when it is destroyed (`AutoregressiveModel` is `Destroyable`), and
`generator(...)` requires the inference model's exact single input and output shapes.

**Deliverable 3 — done.** `docs/internals/training-a-language-model.md` has the new
configuration, curve, recipe comparison, step-cost analysis, and a Generation section.
