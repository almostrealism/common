# Make the self-hosted language model beat its unigram baseline and generate text

**Category:** Proof of Value
**Branch:** `project/plan-20261005-185050` (created from `master`)
**Status:** Proposed — awaiting approval
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
   exists (`AutoregressiveModel`, with device-resident position, temperature/greedy sampling, and
   a reusable token loader), but `CausalLanguageModel` only knows how to build the full-sequence
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

Two deliverables, in order. The first is the hard requirement; the second is the payoff.

### Deliverable 1 — Beat the unigram baseline (honestly)

Make `CausalLanguageModelTest.trainOnDocumentation` pass its existing assertion
(`finalBits < unigramBits`) by genuinely improving the model's held-out prediction, **not** by
weakening the test. The assertion, the baseline source (`scoredTargetEntropyBits()`), and the
apples-to-apples contract (model loss and unigram baseline must cover the *same* scored bytes) are
the specification and must be preserved or strengthened, never loosened.

Evidenced levers, to be applied and measured (the implementer chooses the combination that clears
the bar with margin; all must keep the comparison honest):

1. **Representative held-out measurement.** Score every held-out window at the current stride
   (≈82, versus the current cap of 12) by removing/raising the `maxWindows` cap on the held-out
   `NextTokenDataset`. `scoredTargetEntropyBits()` recomputes over the same larger target set, so
   the comparison stays apples-to-apples; this makes the held-out estimate representative of the
   whole held-out region instead of a 12-window sample. This is a measurement-fidelity improvement
   (strictly more evaluation), not a loosening.
2. **Train further / better schedule.** More epochs and/or a tuned learning-rate schedule, within
   the step budget the timing allows. The doc already found that rotating through the whole training
   region at a decaying rate is what moved held-out loss below the region's unigram rate; push that
   further without tripping the `ModelOptimizer` memorization guard.
3. **More capacity or more corpus, within the memory budget.** The feed-forward input-projection
   Jacobian dominates device memory (grows with the square of the FF width), which is why width is
   128 today. Prefer adding corpus (more `docs/internals` pages) and/or modest depth/context before
   widening the feed-forward. Any configuration change must stay within `AR_HARDWARE_MEMORY_SCALE=6`
   and a total test runtime under the 40-minute cap (the current run is ~34 min; keep headroom).

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
   - **Single-position KV-cache decode** via the `causalMask` single-position attention variant the
     training doc describes, wired through `AutoregressiveModel.of(...)` (device-resident position,
     greedy/temperature sampling). Preferred if the trained weights are directly compatible with the
     single-position causal path.
   - **Full-sequence sliding-window decode** that reuses the existing full-sequence model, feeds the
     growing prefix window, and reads the last position's logits. Simpler and provably consistent
     with the trained forward pass; acceptable as the first generation path if the KV-cache variant
     needs core work. If this path is taken, note the KV-cache follow-up explicitly.
   Reuse `AutoregressiveModel` and `ByteTokenizer`; do not duplicate sampling or tokenization logic.

2. **A generation test** (long-running, excluded from the CI pipeline profile, same as the training
   run; or a fast test if a tiny deterministic fixture is feasible). Load trained weights (or train
   briefly), seed with a short documentation prompt, generate **greedily** (deterministic) for a
   fixed number of bytes, and assert:
   - the output decodes as valid UTF-8 (via `ByteTokenizer` round-trip semantics), and
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
   is comfortably below baseline with runtime headroom under 40 min.
4. **Deliverable 2:** add the generation builder and test; verify greedy determinism and valid
   decode; capture a sample.
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
  below the unigram baseline — achieved by improving the model/measurement, with **no** weakening of
  the assertion, tolerance, dimensions, `@TestDepth`, or timeout (beyond the 40-min cap), and no
  `@Disabled`.
- A new generation path on `CausalLanguageModel` plus a generation test that: loads/produces trained
  weights, generates greedily and deterministically from a documentation prompt, asserts valid-UTF-8
  decode, non-triviality, and reproducibility; and logs a readable generated sample.
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
- Claiming "beats baseline" from a non-representative or mismatched measurement.

The only acceptable way to turn this test green is to make the model genuinely predict held-out
documentation bytes better than their own frequencies, measured apples-to-apples. If, after honest
effort within the memory and time budget, the model cannot clear the bar, the correct outcome is a
precise, evidenced finding (what was tried, the curves, the limiting factor) handed back for a
revised plan — **not** a green test.

## Hardware

The end-to-end training run and its measured curve are validated on Apple-silicon **Metal** (FP32),
with `AR_HARDWARE_MEMORY_SCALE=6` and a cold-step/warm-step timing profile that fits the 40-minute
test cap (~34 min today). Tuning the training recipe and measuring generation need that same
backend to reproduce the numbers the plan is judged against. Hence the workstream requires a macOS
node (`requiredLabels: platform: macos`).

## Dependencies

None beyond `master`. All prerequisite primitives (causal sequence attention, trainable embedding,
byte tokenizer, next-token dataset, multi-row cross-entropy, `AutoregressiveModel`,
`StateDictionary` checkpointing) already landed in PR #596. This task does not touch the in-flight
PDSL-for-research / Qwen interpretability track (PR #615); it is the continuation of the
from-scratch self-hosted LM track.
