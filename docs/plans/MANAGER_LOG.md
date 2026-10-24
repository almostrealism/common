# Project Manager Log

The living journal of the Almost Realism project management process: current thinking about
where the platform is heading, why specific tasks are chosen, and how each piece of work
connects to the larger vision. This is a snapshot of current thinking, not an archive — older
entries are condensed or pruned to keep it under roughly 50,000 characters.

---

## Current State

### Strategic Direction

1. **Foundation** — documentation, clean code, comprehensive tests
2. **Performance** — hardware-accelerated computation that rivals dedicated frameworks
3. **Proof of Value** — model replication, audio generation, distributed training
4. **Self-Understanding** — training modest language models locally that understand the
   platform itself, so the software grows alongside its own capabilities

### Task Categories (priority order)

1. Documentation — can the platform be understood by humans and AI systems?
2. Code Quality — is the codebase clean, consistent, well-tested?
3. Performance — can it handle real workloads efficiently?
4. Proof of Value — can we demonstrate compelling capabilities?

---

## Planning History

### 2026-10-23 — Document the PDSL language

**Category:** Documentation
**Branch:** `project/plan-20261023-025021`
**Plan:** [`PLAN-20261023-pdsl-language-reference.md`](PLAN-20261023-pdsl-language-reference.md)

#### What changed since last cycle

The differential-lambda plan landed (PR #716): the four `differentialLambda` vectors now train, the
three `TODO(review)` markers in `differential_attention.pdsl` are gone, and
`DifferentialAttentionAssetTest` checks them by finite difference. With PR #697 before it, every
learnable operand of every attention variant the platform ships now receives its gradient — the
correctness arc of the last three cycles is closed, apart from the SAME resampling site deliberately
deferred to the cycle that first fine-tunes that autoencoder.

#### Category assessment (priority order)

- **Documentation — a real, flagship gap, and it decided the cycle.** Those three cycles all turned on
  PDSL: the attention family, the transformer layer, the Qwen model files, the Oobleck codec, the MIDI
  decoders and the real-time mixdown are all PDSL assets (19 shipped `.pdsl` assets in `engine/ml`
  plus 29 test fixtures, ~6,200 lines of interpreter), and the defects fixed were questions of what a PDSL builtin does with its operands
  (`scale` reading λ as a constant; `scaled_dot_product` taking a branch as an auxiliary operand). But
  `consult` answers plainly: "There is no single complete language reference for PDSL." The only durable
  page is `pdsl-audio-dsp.md` (audio primitives, with the ML builtins in one parenthesized list); the
  grammar, the definition kinds, parameter types, composition statements, imports, name resolution and
  the builtins' gradient behavior are documented nowhere outside the interpreter source. No tutorial
  mentions PDSL. No tracker task covers it. Smaller doc items seen and left to continuous QA: four
  small `flowtree/*` submodules have no README (the parent `flowtree/README.md` and `runtime/docs/`
  cover them), and the ONNX-pressure note.
- Code quality, performance and proof of value all keep live candidates (below); none was chosen,
  because documentation comes first and this gap is genuine.

#### Why this task

PDSL is the form in which the platform states *what its models compute* — compact, readable,
declarative. That makes its reference the most generative page we can add to the corpus the
self-hosted LM trains on: a model that can read the language can read the model files, and later
propose edits to them — a concrete step toward software that understands its own architecture. It is
also the page every future PDSL conversion and the PDSL-for-research track (whose teaching phase has
nothing to build on) need first. The language is stable enough to document: imports, `stack`,
program scope and the attention gradient fixes have all landed.

The plan asks for a reference derived from source, not memory: the keyword table from `PdslLexer`,
an EBNF grammar from `PdslParser`, every `PdslNode` subclass, the block structure each composition
statement builds, resolution and shadowing rules, the import semantics pinned to the
`pdsl/imports/*.pdsl` fixtures, and a catalog of all 40 builtins with shapes, lowering and a
**per-operand gradient column** backed by code or tests (or marked "not verified"). It removes the
duplicated core-primitive table from `pdsl-audio-dsp.md`, completes the `org.almostrealism.ml.dsl`
Javadoc, and adds a small parse-only test whose snippets are the page's own examples, so the
reference cannot silently drift from the parser. Defects found are recorded, not fixed.

#### Balance across categories

The last four cycles were correctness (three) and proof of value (one); the last documentation cycle
was 2026-10-17. Returning to the foundation now is not rote: the correctness work showed that the
semantics of PDSL builtins are where gradients get lost, and that knowledge exists only in the code
and in session notes. It is a medium task that **runs on any node** — no macOS requirement, no
workstream settings file.

#### What comes next

1. This plan: `docs/internals/pdsl-language.md`, the examples test, the Javadoc pass and cross-links.
2. **Proof of value**, now on a fully honest attention backward pass: top-p through the sliding-window
   generator (`SlidingWindowAutoregressiveModel` calls `sampleToken` with a top-p of `1.0`), a
   single-position KV-cache decode, and conditional diffusion training. Adding the new PDSL page (and
   the model files it explains) to the LM's training corpus is a natural, cheap follow-on.
3. Whatever the reference's "known limits" section records about builtin operand handling becomes the
   next PDSL consistency item.
4. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2 (with the SAME fine-tune); the
   `io.almostrealism.collect` package split; a PDSL tutorial in `docs/tutorials/`; letting a trained
   model read the platform's own *source*.

### 2026-10-22 — Train the learned lambda of differential attention

**Category:** Code Quality (correctness — a silent zero-gradient defect in the attention backward pass)
**Branch:** `project/plan-20261022-134809`
**Plan:** [`PLAN-20261022-differential-lambda-gradients.md`](PLAN-20261022-differential-lambda-gradients.md)

#### What changed since last cycle

The cross-attention K/V plan landed (PR #697). `sequenceCrossAttention` now feeds K and V to the score
and context products as graph operands instead of buffering them through `into(kTensor)`/`into(vTensor)`
and reading them back as constants, so both halves of `toKvWeight` and the K normalization weights train;
a new `SequenceCrossAttentionGradientTest` proves it per slice. The work also made the auxiliary-input
path trainable (`BranchBlock implements Learning`; `Model.setParameterUpdate` propagates to auxiliary
inputs that are `Learning`), and the review confirmed per-step K/V training is correct — the one residual
(the branch aggregator never forwarding gradient further upstream to `condEmbed`, which nothing consumes)
was documented, not papered over. That cycle closed with a named list of what it deferred; two items on it
are correctness defects of the same class, and the **learned differential lambda** is the cleaner and more
fundamental of the two.

#### Category assessment (priority order)

- **Documentation — excellent, and tracking reality.** The internals corpus is current at `master`;
  `training-a-language-model.md`'s "Gradient wiring in sequence attention" section honestly records the
  attention-gradient state (the five projection sections, and `TransformerResamplingFeatures` as the one
  remaining buffered site), and the frozen lambda this cycle fixes is recorded plainly in the
  `differential_attention.pdsl` asset header itself (the "receives no gradient" paragraph and a
  `TODO(review)` at each of the three stages). `consult` finds no flagship doc gap; the standing
  low-priority items (the ONNX-pressure note) are owned by continuous QA. Moved on.
- **Code quality — the category, because of a specific, evidenced correctness defect.** It is not a style
  or coverage gap. The three `differential_attention*` layers of `/pdsl/differential_attention.pdsl` apply
  the learned per-head lambda with `scale(lambda, 1)`, which reads it as a constant factor; the asset's own
  header states that "when lambda is a learned re-parameterization its parameters therefore receive no
  gradient," and a `TODO(review)` marks each of the three stages. `differentialLambda` computes
  `λ = exp(λq1·λk1) − exp(λq2·λk2) + λinit` from four learned vectors, and those four vectors are **frozen
  at initialization** — the one parameter that makes differential attention *differential* never trains.
  This is the deterministic, total zero-gradient defect the cross-attention cycle named as its first
  deferred item.
- Performance (the weighted-sum `matmul` reduced form) and proof of value (top-p / KV-cache / scale for the
  LM) both have live candidates. Not chosen — a silent wrong-gradient defect in the differentiation engine
  outranks both, and this is a concrete, falsifiable instance of one.

#### Why this task

Differential attention subtracts a second attention map from the first in proportion to a learned per-head
λ, so the attention both maps pay to irrelevant positions cancels. `differential_attention_qk_layernorm` is
the Stable Audio diffusion transformer's attention, so this architecture is on the audio-diffusion
proof-of-value track. With λ frozen, a differential-attention model trained on this platform cannot learn
*how much* of the second map to subtract — the defining mechanism stays at initialization, invisibly,
because the projections compensate and the loss still falls. Nothing on `master` trains differential
attention yet (it is exercised only by tests), so the defect is preventive today — but it is cheaper and
safer to repair now than to discover a dead lambda mid-training-run, and it is the honest completion of the
"make the whole attention family's backward pass honest" arc PR #697 began. After this, every learnable
operand of every attention variant the platform ships receives its gradient.

The defect is subtler than the K/V case and the plan says so: the forward applies λ as a graph operand
(`scale` lowers to `multiply(input, broadcast(…, factors))`), so the first step is a minimal
finite-difference experiment to establish *where* the gradient is lost — most likely that `differentialLambda`
hands the block an opaque producer whose underlying vectors are never registered as trainable (no backward
cell, no `ParameterUpdate`), the same structural gap `BranchBlock implements Learning` closed for the K/V
branch — before any attention code changes. The committed deliverable is a new per-vector finite-difference
gradient test modeled on `everySectionOfTheProjectionReceivesItsGradient`. Weakening a correctness test to
go green is out of bounds.

#### Balance across categories

The last cycle was code-quality correctness (cross-attention K/V), preceded by proof-of-value (readable
generation) and two foundation cycles before that. This cycle stays on the correctness thread deliberately:
the cross-attention cycle opened a short, finite list of same-class defects, and finishing the cleanest of
them now — rather than leaving it to surface mid-training-run — is the disciplined move. It is a medium task
gated by small finite-difference tests, so it **runs on any backend** — no macOS requirement, no workstream
settings file.

#### What comes next

1. This plan: diagnose where the lambda gradient is lost, make the four `differentialLambda` vectors
   trainable (following the PR #697 trainable-operand template, placing any general construct at its most
   general home), add the per-vector finite-difference test, remove the three `TODO(review)` markers, and
   update the training-doc gradient-wiring section.
2. The other deferred correctness item — `TransformerResamplingFeatures.resamplingLayerBlock`'s buffered
   K1/V/Q2/K2 — best taken in the cycle that first fine-tunes the SAME autoencoder (its `into(...)` leaves
   bound kernel size at full SAME dimensions, so a naive rewiring may reintroduce the compile blow-up
   `SAMEResamplingParityTest` guards against).
3. **Proof of value**, on a now fully-honest attention backward pass: top-p through the sliding-window
   generator (`SlidingWindowAutoregressiveModel` calls `sampleToken` with a top-p of `1.0`), a
   single-position KV-cache decode, and conditional diffusion training that can learn both its conditioning
   (K/V) and its differential cancellation (λ).
4. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: the ONNX-pressure doc note; the `io.almostrealism.collect` package split; letting a trained
   model read the platform's own *source*, the deeper self-understanding aim.

### 2026-10-21 — Train the K/V projections of cross-attention and transformer resampling

**Category:** Code Quality (correctness — a silent zero-gradient defect in the attention backward pass)
**Branch:** `project/plan-20261021-012939`
**Plan:** [`PLAN-20261021-cross-attention-kv-gradients.md`](PLAN-20261021-cross-attention-kv-gradients.md)

#### What changed since last cycle

The non-trivial-generation plan landed (PR #685): the self-hosted LM now generates words instead of
96 spaces. With `LEARNING_RATE` 5e-4 / final 5e-5 over 40 epochs (dimensions unchanged),
`CausalLanguageModelTest.trainOnDocumentation` passes both assertions — all-83 held-out 3.426
bits/byte against a 4.904 unigram baseline — and the run is deterministic and reproduced bit-for-bit
across two machines. The milestone the LM track had been one step short of since September is cashed:
the first readable artifact of the self-understanding goal. The "what comes next" list it left
(top-p through the sliding-window generator, a KV-cache decode, scale) is proof-of-value work in
category 4.

#### Category assessment (priority order)

- **Documentation — excellent, and tracking reality.** The internals corpus is current at `master`,
  and `training-a-language-model.md` honestly records the very gradient gaps this cycle fixes (its
  "Gradient wiring in sequence attention" section). `consult` finds no flagship doc gap; the standing
  low-priority items (the ONNX-pressure note) are owned by continuous QA. Moved on.
- **Code quality — the category, because of a specific, evidenced correctness defect.** It is not a
  style or coverage gap. Two attention paths — `sequenceCrossAttention` and
  `TransformerResamplingFeatures.resamplingLayerBlock` — still buffer their K/V operands through fixed
  tensors (`k.andThen(into(kTensor))` / `v.andThen(into(vTensor))` in cross-attention; `k1Tensor`,
  `vTensor`, `q2Tensor` and `map2Tensor` in resampling) and read them back as constants, so their
  key/value projection weights receive **exactly zero** gradient. The self-attention paths were
  already fixed (`sequenceAttention` and `differentialSequenceAttention` build PDSL asset layers whose
  K/V are branch operands; `DifferentialAttentionAssetTest` covers all five differential sections);
  the two remaining sites kept the original defect, flagged with a `TODO(review)` at the
  `sequenceCrossAttention` site and documented in the training page. (The plan as first written also
  listed `DifferentialAttentionFeatures`; review found it already fixed on `master`.)
  `SequenceAttentionGradientTest` already proved this class of bug (the buffered key slice had an
  analytic gradient of exactly 0 against a large numeric gradient). This is a deterministic, total
  version of the same defect the 2026-10-18 cycle treated as top-priority.
- Performance and proof of value both have live candidates (the weighted-sum `matmul` reduced form;
  top-p / KV-cache / scale for the LM). Not chosen — a silent wrong-gradient defect in the
  differentiation engine outranks both, and this is a concrete, falsifiable instance of one.

#### Why this task

Cross-attention is how a model reads its *conditioning*. `sequenceCrossAttention` is the conditioning
mechanism of the `DiffusionTransformer` family and `TransformerResamplingFeatures` is the resampling
autoencoder of the audio diffusion track (inference-only today). With K/V gradients dropped, a
conditional model trained on this platform cannot learn *what in its context is relevant* — the
query projection adapts while the key/value projections stay frozen at initialization, invisibly,
because the loss still falls as the rest of the network compensates. Repairing it makes the
attention backward pass honest across the whole family, not just the one path the language model
exercised first — the "fix the general mechanism, not the one shape" standard the
gradient-correctness cycle set.

`sequenceCrossAttention` is the committed deliverable, with a new per-slice finite-difference
`SequenceCrossAttentionGradientTest`. Review of the plan found it is not as clean as first assumed:
its K/V are computed on a branch of a separate block (`condEmbed`, an auxiliary `Model` input), and
`Model.backward()` is the main chain's backward only, so the first step is to establish whether a
gradient can reach an auxiliary input at all — possibly a `domain/graph` change. A later review split
that question: the K/V layers sit inside a `branch()` child, so they may train once the products push
into their backward cells, while `condEmbed`'s own weights upstream of the `BranchBlock` look
unreachable without a `domain/graph` change (inferred from the code, not observed). The resampling site
reads one V into two attention maps; `differential_attention.pdsl` already expresses exactly that with
`accum_blocks` and correct gradients, so it is the template, not a new branch fan-out mechanism. Its
`into(...)` leaves also bound kernel size at full SAME dimensions, and nothing trains SAME today, so
the plan lets that site stop at an evidenced follow-up rather than regress `SAMEResamplingParityTest`.
Weakening a correctness test to go green is out of bounds.

#### Balance across categories

The last two cycles went to proof of value (the matmul VJP that made training cheap, then readable
generation), preceded by two foundation cycles (the autodiff page, the reference-gradient fix). This
cycle returns to the foundation — and not by rote: the proof-of-value track is about to lean on
conditional attention (diffusion conditioning, resampling), and that backward pass is quietly broken
for K/V. Fixing it now is cheaper and safer than discovering a dead projection mid-training-run later.
It is a medium task gated by small finite-difference tests, so it **runs on any backend** — no macOS
requirement, no workstream settings file.

#### What comes next

1. This plan: wire K/V gradients through `sequenceCrossAttention` (committed), rewire the resampling
   site on the `differential_attention.pdsl` template if its kernel bounds allow, add per-slice
   finite-difference tests, update the training-doc gradient-wiring section.
2. Whatever this plan defers (backward through auxiliary inputs if it proves to be a `domain/graph`
   change; the resampling site) is the next correctness item, alongside the learned differential
   lambda, which `scale(lambda, 1)` reads as a constant.
3. **Proof of value**, on a now-trustworthy conditional backward pass: top-p through the
   sliding-window generator (`sampleToken` already takes a top-p argument hardcoded to `1.0`), a
   single-position KV-cache decode, and conditional diffusion training that can learn its conditioning.
4. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: the ONNX-pressure doc note; the `io.almostrealism.collect` package split; letting a
   trained model read the platform's own *source*, the deeper self-understanding aim.

### 2026-10-19 — Make the self-hosted LM write a readable sentence about itself

**Category:** Proof of Value
**Branch:** `project/plan-20261019-194959`
**Plan:** [`PLAN-20261019-self-hosted-lm-nontrivial-generation.md`](PLAN-20261019-self-hosted-lm-nontrivial-generation.md)

#### What changed since last cycle

The gradient-correctness plan landed (PR #675): the nondeterministic full-Jacobian weight gradient
for the 256×64 output projection is fixed — the kernel hoisted parts of the flattened position into
32-bit variables, so a hoisted row times a 1,048,512 stride overflowed where the inline 64-bit
kernel index did not, and hoisted variables that refer to the kernel index now take a 64-bit type
(`Expression.getDeclarationType`). `DenseVectorJacobianProductTest.outputProjection` now asserts the
full-Jacobian gradient against the definition over several evaluations, and a new
`ConvolutionJacobianGradientTest` checks the same class of path for convolution. The reference
gradient the whole reduced-delta suite trusts is trustworthy again. That was the explicit
prerequisite the 2026-10-18 entry set for the generation push.

#### Category assessment (priority order)

- **Documentation — excellent, and complete where it matters.** The internals corpus (38+ pages)
  tells the whole story; `automatic-differentiation.md` and `training-a-language-model.md` are both
  current, and the latter documents the failing generation honestly. `consult` finds no flagship
  gap. The one standing low-priority item (the ONNX-pressure note) is owned by continuous QA. Moved
  on.
- **Code quality — strong and owned.** The continuous QA pipeline merges fixes daily and enforcement
  is mechanical. The one standing minor item (`io.almostrealism.collect` split) is unchanged and not
  a flagship. Moved on.
- **Performance — no blocker.** The named lever two cycles spent (the matmul vector-Jacobian product)
  landed and made a training step about ten times cheaper. The remaining perf item (a reduced form
  for the weighted-sum `matmul` lowering, outputs wider than 1000) is real but blocks nothing and
  ranks below cashing the proof-of-value milestone. Moved on.
- **Proof of value — the category.** A known-failing generation assertion and a model that repeats a
  single byte are precisely the concrete, falsifiable gaps this category exists to close.

#### Why this task

It is the honest completion of the milestone the LM track has been one step short of since
September, now unblocked. The model beats its unigram baseline (4.194 vs 4.912 bits/byte over all 83
held-out windows) but `CausalLanguageModelTest.trainOnDocumentation` still fails: greedy decoding of
`"The computation graph is compiled to "` produces 96 spaces, and `assertNonTrivialGeneration` (which
runs before the baseline assertion) rejects a single repeated byte.

The investigation established, from source and the existing tests, that the collapse is the *model*,
not the decoder: `slidingWindowGenerationMatchesFullForward` already proves the generator decodes
exactly the full forward pass, and the run reaches a training loss near the bigram rate (~3.5) but a
held-out loss of 4.19 — a weakly generalizing model asked to continue an unseen prompt collapses its
argmax to the unconditional most-frequent byte (a space). So the honest fix is a better-generalizing
model via recipe and scale, decoded exactly as the generator already decodes — not a change to what
the test decodes with or asserts. The headroom is evidenced: a count-based bigram scores 3.78 and a
trigram 3.07 on the held-out targets, so the corpus has a great deal left to model.

Because this sits on a known-failing acceptance test, the plan is explicit that the only acceptable
way to turn it green is a genuinely better model measured apples-to-apples. Weakening
`assertNonTrivialGeneration` (greedy→sampled, lowering the distinct-byte bar), the baseline, the
dimensions, `@TestDepth` or the timeout is out of bounds and would fail `test-integrity-check`. If
honest effort within the memory/time budget cannot clear the greedy bar, the correct outcome is an
evidenced finding for a revised plan, not a green test.

#### Balance across categories

The last two cycles went to the foundation (the autodiff page, then the reference-gradient fix),
which is what made this milestone safe to attempt. This cycle cashes it: the first readable artifact
of the self-understanding goal — the software writing a sentence about its own architecture. The
foundation categories stay covered by continuous QA. It is a medium task bounded by the ~21-minute
training run and runs on a macOS/Metal node.

#### What comes next

1. This plan, if approved: re-measure the documented run with the corrected reference gradient,
   diagnose the greedy collapse with `ar-profile-analyzer`, sweep recipe then capacity (and batch > 1
   only if it is a narrow change), turn the generation assertion green honestly or stop at an
   evidenced finding, and rewrite the training doc's generation section from measurement.
2. **Sampling quality and longer generation**: expose top-p (nucleus) through the sliding-window
   generator — `sampleToken` already takes a top-p argument the generator hardcodes to 1.0 — and a
   proper single-position KV-cache decode (needs the fused `qkv` split into Q/K/V views and the
   rotary inputs derived from `rope_inv_freq`).
3. **Scale**: batch > 1 in `scaledDotProductAttention` if deferred here, larger context/depth, and
   the K/V gradient wiring still missing in `sequenceCrossAttention`, `TransformerResamplingFeatures`
   and `DifferentialAttentionFeatures`.
4. **Performance**: the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: the ONNX-pressure doc note; the `io.almostrealism.collect` package split; letting a
   trained model read the platform's own *source*, the deeper self-understanding aim.

### 2026-10-18 — Fix the nondeterministic full-Jacobian weight gradient for the output projection

**Category:** Code Quality (correctness — a silent wrong-gradient defect in the autodiff engine)
**Branch:** `project/plan-20261018-174738`
**Plan:** [`PLAN-20261018-full-jacobian-gradient-correctness.md`](PLAN-20261018-full-jacobian-gradient-correctness.md)

#### What changed since last cycle

The autodiff internals page landed (PR #670, with follow-ups #667/#673): the backward half of the
execution story is now documented end to end, and the prior entry named that page as the
**prerequisite** for the correctness work below. So the gate it set is now open. Everything else the
last entry promised is still queued behind it: non-degenerate LM generation (greedy decoding still
repeats one byte), the weighted-sum `matmul` reduced form, the `io.almostrealism.collect` split.

#### Category assessment (priority order)

- **Documentation — excellent, and newly complete where it mattered.** The internals corpus is 38
  pages and now includes `automatic-differentiation.md`; `consult` finds no flagship gap. The only
  standing items (the ONNX-pressure note, line-number hygiene) are low-priority and owned by
  continuous QA. Moved on.
- **Code quality — the category, because of a specific, evidenced correctness defect.** It is not a
  style or coverage gap: `GradientFeatures.jacobianGradient`, the full-Jacobian fallback that is the
  *reference every reduced-delta test is checked against*, produces a **nondeterministically wrong**
  weight gradient for the 256×64 output projection over 64 rows — ~1/10 of 16,384 entries, 1,728 on
  one profiled Metal run and 1,536 on another, always from weight row 64 onward. It is recorded
  honestly today (a `TODO(review)`, the "known limits" prose, and
  `DenseVectorJacobianProductTest.outputProjection` logging the disagreement instead of asserting
  it), and it was explicitly deferred to "its own plan" by the VJP review. This is that plan.

#### Why this task

A learning system whose *reference gradient* is silently, intermittently wrong is the most
consequential kind of foundation defect: the platform's first self-hosted training runs trained the
output projection from this path, every operator without a structural reduced form still falls back
to it, and it is the ground truth the whole reduced-delta suite trusts. Correctness of the
differentiation engine sits in the second category and ranks above performance and proof of value —
and this is a concrete, falsifiable instance of it, now unblocked by the documentation it needed.

The plan follows evidence, not the first hypothesis. The `TODO(review)` guesses a 32-bit index
overflow (2³⁴ iteration space, errors beginning at flat index 2³²), but the profiling already done
*contradicts* that: a deterministic overflow would miscompute the same entries every run, and this
does not. The nondeterminism, and the ~1M-element `(64,256,64)` repeat buffer the sum kernel reads,
point instead at argument binding / aggregation or at the ordering of the kernel that writes that
buffer and the kernel that reads it. The layernorm "invalid kernel" journal
(`INVALID_KERNEL_AGGREGATION.md`) is cited only as an untrusted lead: `docs/journals/CONCESSION.md`
retracts its central claim (the "divergent" kernels were a mismatched comparison), so no aggregation
defect is established and the plan does not reason from one. So the plan separates the leads with
cheap single-variable experiments — disabling aggregation via `AR_HARDWARE_ARGUMENT_AGGREGATION`,
then forcing the write/read ordering — reads the generated kernel with `ar-profile-analyzer` before
theorizing, fixes the general mechanism (not the one shape), and tightens the existing test to
*assert* agreement on repeated evaluation. Weakening the test to go green is out of bounds and the
plan says so.

#### Balance across categories

The last several cycles alternated foundation documentation with proof-of-value milestones, and the
proof-of-value track (a trained LM that beats its baseline, PR #628) is one honest step from
speaking. But it cannot speak on a gradient that is sometimes wrong — and the degenerate single-byte
generation may itself be a symptom of an output projection trained on this very defect. Fixing the
reference gradient is the right move before the next generation push: it is cheaper now (the
failing kernel is already profiled and the candidate mechanisms named), it protects every later LM
step, and it keeps the platform honest about its own learning machinery.

#### What comes next

1. This plan: diagnose the nondeterministic output-projection fallback on Metal via
   `ar-profile-analyzer`, fix it at the general argument-mapping/ordering (or index-typing) layer,
   assert the agreement in `DenseVectorJacobianProductTest`, check convolution for the same defect
   with a new test against a host reference (the existing convolution-delta tests only log shapes),
   and update the "known limits" docs and the `TODO(review)`.
2. **Proof of value — non-degenerate generation**, revisited with a now-trustworthy gradient:
   temperature/top-p in the sliding-window generator, a KV-cache decode, more steps/larger context.
   Re-run `trainOnDocumentation` to see whether the corrected gradient moves the held-out score.
3. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000),
   the last Jacobian-contracting path on a dense layer.
4. Still open: the ONNX-pressure doc note; the `io.almostrealism.collect` package split; letting a
   trained model read the platform's own *source*.

### 2026-10-17 — Document automatic differentiation from `delta` to the backward pass (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261018-013621` ·
**Plan:** [`PLAN-20261018-automatic-differentiation-internals.md`](PLAN-20261018-automatic-differentiation-internals.md)

After the derivative API was consolidated (PRs #661, #666 — `CollectionProducer.delta(target, axis,
weight)` as the only public derivative method, customized through `deltaDependent`, `deltaElement`,
`deltaPartial` and `deltaMatrix`), `consult` confirmed there was no internals page for the backward
half of the execution story. The cycle wrote `docs/internals/automatic-differentiation.md` (PR #670):
extension points and their order, `ExpressionMatrix`, `DefaultGradientPropagation` and the backward
cells, and a "known limits" section naming the silent full-Jacobian fallback. That page was the
explicit prerequisite for the 2026-10-18 correctness cycle.

### 2026-10-09 — Compute matrix-multiply weight gradients without materializing the Jacobian (condensed)

**Category:** Performance (unblocking Proof of Value) · **Branch:** `project/plan-20261009-171224` ·
**Plan:** [`PLAN-20261009-matmul-weight-gradient-vjp.md`](PLAN-20261009-matmul-weight-gradient-vjp.md)

The training doc itself named the lever: the generic backward pass formed each weight gradient from
the operator's full Jacobian (~17 billion terms for the 256×64 output projection). The plan replaced
that with a structure-aware vector-Jacobian product for the matrix-multiply family only, behind a
capability check with a byte-identical fallback (the `feature/lora-gradients` history made a narrow
scope mandatory). It landed as PR #628: a training step went from 3.6 s to 0.35 s and
`trainOnDocumentation` beat its unigram baseline for the first time (4.194 vs 4.912 bits/byte). It
also surfaced the full-Jacobian output-projection defect fixed on 2026-10-18.

### 2026-10-05 — Make the self-hosted LM beat its unigram baseline and generate text (condensed)

**Category:** Proof of Value · **Branch:** `project/plan-20261005-185050` ·
**Plan:** [`PLAN-20261005-self-hosted-lm-beat-baseline-and-generate.md`](PLAN-20261005-self-hosted-lm-beat-baseline-and-generate.md)

After the from-scratch LM merged (PR #596) it still scored above its unigram baseline and had never
produced a byte of text. This cycle built the generation path (`SlidingWindowAutoregressiveModel`,
verified against a full forward pass) and all-window held-out scoring, and established that per-step
backward cost — not the model or recipe — was what held the run at the unigram rate. That finding
became the 2026-10-09 performance plan; the baseline and readable-generation milestones were cashed
by PRs #628 and #685.

### 2026-09-30 — Train a tiny byte-level causal transformer on the platform's own docs (condensed)

**Category:** Proof of Value · **Branch:** `project/plan-20260930-180401` ·
**Plan:** [`PLAN-20260930-self-hosted-tiny-lm.md`](PLAN-20260930-self-hosted-tiny-lm.md)

With the fine-tuning re-baseline done (PR #582) proving training feasible, this cycle turned "training is
feasible" into "the platform trained a language model on itself" — the first concrete artifact of the
self-understanding goal. The survey found performance no longer blocked a self-hosted LM; missing
capability did. The platform had never trained a transformer from scratch, and the pieces next-token
training needs were missing or unverified: full-sequence attention had no causal mask; `sequenceAttention`
routed K/V through fixed buffers read as constants, so gradients reached only the Q slice (the defect the
2026-10-21 cycle later finished for cross-attention, and the 2026-10-22 cycle for the differential lambda);
there was no trainable embedding, no byte tokenizer, no text dataset; and `NegativeLogLikelihood.gradient`
was unnormalized where `loss` averaged over rows (64× the gradient at context 64). The plan put the risky
gradient checks first and added the missing primitives (causal sequence attention, trainable embedding,
byte tokenizer, text dataset, the `1/rows` NLL normalization), all general and reused by every later
LM-training step. It landed as PR #596; the baseline-beating and generation milestones it set up were
cashed by the 2026-10-09 → 2026-10-19 cycles.

### 2026-09-29 — Re-baseline LoRA fine-tuning backward-pass compile scaling (condensed)

**Category:** Performance · **Branch:** `project/plan-20260929-043714` ·
**Plan:** [`PLAN-20260929-finetune-compile-scaling-rebaseline.md`](PLAN-20260929-finetune-compile-scaling-rebaseline.md)

`FINE_TUNE_FAIL.md` (February 2026) declared production-scale LoRA fine-tuning **infeasible** on
super-linear backward-pass compile time. By September two separate compile-time fronts had moved —
the `ExplicitExpressionMatrix` cache work against `expressionCacheMatch` and the
`feature/cl-profile-perf` kernel-series rebuild against `kernelSeries` (`convDeltaSmall`
172 s → 1.15 s) — so the gating verdict was very likely stale in the pessimistic direction. The task
re-measured the transformer backward pass with the existing `AggressiveFineTuningTest` harness
against `master`, scoped tightly to measure → profile → re-baseline → name the next lever, staying
clear of the in-flight `feature/lora-gradients` autodiff work to avoid the add/revert collision that
has bitten this area. This re-baseline is what let the next cycle scope the first self-hosted
training run against reality.

### 2026-09-26 — Native Runtime Lifecycle Documentation (condensed; log restart)

**Category:** Documentation · **Branch:** `project/plan-20260926-172935` ·
**Plan:** [`PLAN-20260926-native-runtime-lifecycle-docs.md`](PLAN-20260926-native-runtime-lifecycle-docs.md)

This entry restarted the log (the prior log was deleted 2026-04-06 with a batch of resolved plans).
Carried over, condensed: in **March 2026** the compilation-pipeline and ML-inference internals docs
were planned and landed, TODO/FIXME work pivoted to individual fixes, and `docs/MODULE_REVIEW.md`
deferred the `io.almostrealism.collect` split; from **April–September 2026** the project invested
heavily in agent infrastructure (FlowTree, ar-manager, enforcement hooks, test-integrity), kernel
correctness journals, audio/PDSL work, and the Stable Audio 3 / Qwen tracks. The task itself
documented the back half of the execution story — what happens to a compiled kernel and the memory
it reads after compilation — closing the most consequential remaining conceptual gap and completing
the internals corpus from Producer to released memory. It named the next priorities as performance
(compile-time/kernel reuse) and then a small self-hosted LM training run — the trajectory the
cycles since have followed.
