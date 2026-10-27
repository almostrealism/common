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

### 2026-10-26 (later) — Teach PDSL model authoring with a worked tutorial

**Category:** Documentation
**Branch:** `project/plan-20261026-165814`
**Plan:** [`PLAN-20261026-pdsl-authoring-tutorial.md`](PLAN-20261026-pdsl-authoring-tutorial.md)

#### What changed since last cycle

The KV-cache single-position decode landed (PR #753, merged): `CausalLanguageModel.decodeModel`
builds the single-position decode `Model` over its own `StateDictionary`, and
`KeyValueCacheAutoregressiveModel` (sibling of `SlidingWindowAutoregressiveModel`) owns the
compilation, per-layer caches and position, sharing the top-p `sampleToken` path. Five new tests
pin it, chief among them a byte-identical-to-full-forward assertion at every position. I verified
one thing the stale review-followup memory (6c8764a3) got wrong: the merged `load(Integer)` already
throws `IllegalStateException` when the step reaches `config.seqLen`, so the out-of-bounds guard that
memory flagged as deferred is in fact present — that correctness item is closed, not open. So the
single step the "what comes next" lists had telegraphed five cycles running is cashed, and no
same-class defect trails it.

#### Category assessment (priority order)

- **Documentation — a real teaching gap, and it decided the cycle.** The *reference* corpus is
  excellent and current (40+ internals pages; `pdsl-language.md` is the complete normative PDSL
  reference, guarded by `PdslLanguageReferenceExamplesTest`). But the *tutorial* series
  (`docs/tutorials/01`–`09`) covers vectors, `PackedCollection`, hardware, computation graphs, ML
  inference, signal processing, testing, process optimization, FlowTree setup and secrets — and
  **nothing teaches PDSL**, the declarative language in which every model the platform trains states
  what it computes. For several cycles I logged this as low-priority precisely because there was a
  proof-of-value step to cash *right now*; with the KV-cache decode landed and the next proof-of-value
  steps either Metal-gated (throughput measurement) or heavy (conditional diffusion), the long-deferred
  teaching gap is now the first category with genuine, reachable, high-leverage work. It is not a
  second reference — it is the pedagogical companion the reference has lacked, and the owner has
  explicitly flagged PDSL education as a direction (`PDSL_FOR_RESEARCH_AND_EDUCATION.md`).
- **Code quality — strong and owned by continuous QA.** The attention-correctness arc is closed; the
  KV-cache decode merged with a clean OOP review and its one flagged guard turned out to be present.
  No flagship defect. The standing minor item (`io.almostrealism.collect` split) is unchanged.
- **Performance — no blocker.** The named lever (weighted-sum `matmul` reduced form, outputs > 1000)
  is real but blocks nothing.
- **Proof of value — the category's momentum, deliberately not chosen this cycle.** The telegraphed
  next steps (measure KV-cache throughput and compare greedy-vs-top-p over long samples on Metal;
  conditional diffusion training) are live and strong, but the first is hardware-gated and the
  documentation gap outranks them under the priority order now that it is the earliest category with
  real work.

#### Why this task

PDSL is the most generative page we can add to the corpus the self-hosted LM trains on — the same
reasoning that drove the 2026-10-23 reference cycle. The reference answers "what does this construct
mean"; a tutorial answers "how do I read a model, then write one," progressively, in the register of
the existing series. That is what a human reaches for first and what a model learning from prose and
worked examples learns from best, so it is simultaneously human onboarding and training material for
the self-understanding aim. Crucially it is **reachable today on any backend**: it stays inside what
already ships — the parser, `PdslLoader`, and the already-tested `.pdsl` assets — and depends on none
of the unbuilt `PDSL_FOR_RESEARCH_AND_EDUCATION.md` features (`embed` token-embedding primitive,
`transformer(block, position)`, stack slicing, runner, a Qwen checkpoint the agent cannot download).
Hierarchical weight paths and `stack weights.model.layers as block` already ship and are taught.

To hold the project's standard that shipped doc examples cannot drift, the plan pairs the page with
`PdslTutorialExamplesTest`, modeled on the reference's examples test: it extracts every PDSL program
the page shows as complete and asserts each parses (the final model also builds via
`PdslLoader.buildModel` from a synthetic `StateDictionary`). Any
rough edge the tutorial surfaces in the language is recorded as a follow-up, not fixed here — this is
a documentation cycle, not a language change.

#### Balance across categories

The last five cycles were proof-of-value (KV-cache decode, nucleus sampling), documentation (the PDSL
reference) and two correctness cycles (differential lambda, cross-attention K/V). Returning to the
foundation now is disciplined, not a retreat from momentum: the proof-of-value step those cycles were
building toward is cashed, and the earliest category with genuine reachable work is the teaching gap.
It is a medium task that **runs on any node** — no macOS requirement, no workstream settings file.

#### What comes next

1. This plan: `docs/tutorials/10-pdsl-model-definitions.html`, the `PdslTutorialExamplesTest`
   drift-guard, and the index/reference/README/`llms.txt` cross-links.
2. **Proof of value, Metal:** measure KV-cache decode throughput vs full-forward and compare
   greedy-vs-top-p decoding over long samples now that decoding is cheap enough to run at length
   (the one Metal-gated step, with a workstream settings file).
3. **Proof of value:** conditional diffusion training, on an attention backward pass now honest end
   to end for both conditioning (K/V) and differential cancellation (λ).
4. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2 (with the SAME fine-tune); the
   `io.almostrealism.collect` package split; the unbuilt PDSL research/education features
   (embedding primitive, runner, interventions); letting a trained model read the platform's own
   *source*.

### 2026-10-26 — Decode the self-hosted LM one token at a time with a key/value cache

**Category:** Proof of Value (self-hosted LM generation)
**Branch:** `project/plan-20261026-022005`
**Plan:** [`PLAN-20261026-kv-cache-single-position-decode.md`](PLAN-20261026-kv-cache-single-position-decode.md)

#### What changed since last cycle

The nucleus-sampling plan landed (PR #741, merged): `AutoregressiveModel` now carries a
`getTopP`/`setTopP` threshold (validated `(0, 1]`, default `1.0`), both generators route the same
tempered logits through `sampleToken` once, and the `of(...)` double-softmax is gone — so
`SlidingWindowAutoregressiveModel` and `AutoregressiveModel.of(...)` sample identically at a given
temperature, top-p and seed. The nucleus implementation that shipped unreachable is now reachable,
and the two internals pages describe it. That cashes the first "what comes next" item of the last
five entries. The sampling strategy for short continuations is settled.

#### Category assessment (priority order)

- **Documentation — excellent.** The internals corpus is 40 pages and current at `master`; the
  PDSL reference (2026-10-24) closed the last flagship gap, and I re-read `training-a-language-model.md`
  and `ml-inference-pipeline.md` this cycle — both are accurate and current, the training doc even
  states the exact blocker this task removes. `consult` finds no durable gap. The one standing item
  — a PDSL *tutorial* in `docs/tutorials/` (01–09 never mention PDSL) — is a real teaching asset but
  has been the low-priority, continuous-QA item for several cycles; it is not a missing reference and
  does not outrank cashing a proof-of-value step. Moved on.
- **Code quality — strong and owned by continuous QA.** The three-cycle attention-correctness arc is
  closed; enforcement is mechanical. The standing minor item (`io.almostrealism.collect` split) is
  unchanged and not a flagship. Moved on.
- **Performance — no blocker.** The named lever (the weighted-sum `matmul` reduced form for outputs
  wider than 1000) is real but blocks nothing. Note that *this* task is itself a performance step
  dressed as proof of value: a KV-cache decode is how generation stops being an order of magnitude
  too slow to use. Moved on from the standalone perf item.
- **Proof of value — the category, and a sharp instance.** Generation re-runs a full
  `(seqLen, vocabSize)` forward pass per byte (~35–45 bytes/s), so the model cannot speak at length
  about itself in reasonable time, and the long-sample sampling comparisons the last cycle set up are
  gated behind it. The fix is the standard KV cache.

#### Why this task

It is the disciplined next step the log has telegraphed five times running, now reachable: with
sampling unified, the remaining ceiling on generation is the full-forward-per-byte decode. The gap is
precise and evidenced, not vague. The cached single-position `attention(...)` family and
`causalMask(shape, position)` already exist and are exercised by the SkyTnt MIDI model's per-layer
cache tests — so the platform is not missing a KV cache in general. What it lacks is a decode path for
the self-hosted LM's **weight layout**: the training doc names it, and the config confirms it — each
block stores a fused `qkv` `(3·dim, dim)` weight and the model stores `rope_inv_freq` once, while the
cached overloads want separate `wq`/`wk`/`wv` and a position-indexed `freqCis`. Bridging those two
shapes (fused→split projection, inverse-frequencies→position-indexed rotary) is the whole of the
missing capability.

Because this touches a numerically exact path, the plan is evidence-led: establish that the decode's
rotary at position `p` equals the full-sequence path's rotary at row `p` before building on it, pin
one decoded row against one full-forward row before decoding a sequence, then bring up the
byte-identical correctness test modeled on `slidingWindowGenerationMatchesFullForward`. The committed
proof is that **every** greedily decoded byte matches the full forward — the one honest way to show a
KV cache is correct. Weakening that assertion to go green is out of bounds and the plan says so.

#### Balance across categories

The last five cycles were proof-of-value (nucleus sampling), documentation (PDSL reference), two
correctness cycles (differential lambda, cross-attention K/V) and proof-of-value (readable
generation). Both foundation arcs are closed and the sampling API is complete, so staying on the
proof-of-value track to remove the decode ceiling is the move the "what comes next" lists have been
building toward, not a jump to the exciting category. It is a medium–large capability task whose
committed deliverable is correctness-pinned and **runs on any backend** — no Metal, no workstream
settings file. The throughput *measurement* is a deliberate Metal follow-up, exactly as the
nucleus-sampling cycle deferred its measurement.

#### What comes next

1. This plan: bridge the fused-QKV + `rope_inv_freq` layout to the cached single-position attention,
   build a decode model over the same `StateDictionary`, wire it into an `AutoregressiveModel` so it
   shares the top-p sampling path, add the byte-identical correctness and cache-causality tests, and
   rewrite the training-doc generation-cost paragraph.
2. **Measure** on a macOS/Metal node: the throughput improvement over full-forward decode, and a
   greedy-vs-top-p comparison over long samples now that decoding is cheap enough to run at length.
3. **Proof of value:** conditional diffusion training, on an attention backward pass that is now
   honest end to end for both conditioning (K/V) and differential cancellation (λ).
4. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2 (with the SAME fine-tune); the
   `io.almostrealism.collect` package split; a PDSL tutorial in `docs/tutorials/`; letting a trained
   model read the platform's own *source*.

### 2026-10-25 — Expose nucleus (top-p) sampling through the autoregressive generators

**Category:** Proof of Value (self-hosted LM generation)
**Branch:** `project/plan-20261025-022810`
**Plan:** [`PLAN-20261025-nucleus-sampling-generators.md`](PLAN-20261025-nucleus-sampling-generators.md)

#### What changed since last cycle

The PDSL language reference landed (merged 2026-10-24): `docs/internals/pdsl-language.md` is the
single complete reference (grammar, definition kinds, name resolution, import semantics, and a
40-builtin catalog with a per-operand gradient column), `PdslLanguageReferenceExamplesTest` reads
the page and parses every fenced example so it cannot drift from the parser, the
`org.almostrealism.ml.dsl` Javadoc is complete, and `pdsl-audio-dsp.md` no longer duplicates the
core table. That closes the flagship documentation gap the last cycle opened. With it, both
foundation arcs the recent cycles were on are closed: the attention backward pass is honest end to
end (K/V projections, then the differential lambda), and the language the models are written in is
documented.

#### Category assessment (priority order)

- **Documentation — excellent, and the one flagship gap just closed.** `consult` finds no durable
  gap: the internals corpus is current at `master` and now includes the PDSL reference. The one
  standing item — a PDSL *tutorial* in `docs/tutorials/` (01–09 never mention PDSL) — is real but
  has been the low-priority, continuous-QA item for several cycles; it is a teaching asset, not a
  missing reference, and does not outrank cashing a proof-of-value step. Moved on.
- **Code quality — strong and owned by continuous QA.** The correctness arc (three cycles) is
  closed; enforcement is mechanical. The standing minor item (`io.almostrealism.collect` split) is
  unchanged and not a flagship. Moved on.
- **Performance — no blocker.** The named lever (the weighted-sum `matmul` reduced form for outputs
  wider than 1000) is real but blocks nothing and ranks below cashing the proof-of-value step.
  Moved on.
- **Proof of value — the category.** The self-hosted LM can decode greedily or with
  full-distribution temperature, and neither is good: greedy collapses to the most-frequent byte,
  temperature draws from the unreliable tail. Nucleus (top-p) sampling is the standard remedy and
  is the first "what comes next" item of the last four entries.

#### Why this task

The survey turned up a sharp, high-confidence instance of the category. `AutoregressiveModel.sampleToken`
**already implements nucleus filtering in full**, but **no public API lets a caller set `topP`** —
both generator call sites hardcode it to `1.0`. The platform ships a nucleus-sampling implementation
that is unreachable from a consumer. Reading the two paths together also surfaced a second defect in
the same code: `AutoregressiveModel.of(...)` pre-applies `rescale` (`logits / temperature`) and
`softmax` on the device — so the configured temperature *is* applied — and then calls `sampleToken`
with a **hardcoded temperature of `1.0`**, which re-exponentiates and renormalizes the
already-softmaxed values: a redundant second softmax that flattens the already-tempered distribution.
It therefore samples from a different (flatter) distribution than `SlidingWindowAutoregressiveModel`,
which passes raw logits and the configured temperature straight through for a single softmax. The
fix for both is one unification: route raw logits through `sampleToken` once, with the configured
temperature and configured top-p, from both generators. It is the smallest change that
materially improves what the self-hosted LM can produce, and a prerequisite for the sampling-quality
comparison the training doc already promises.

Because the change touches a documented sampling path, the plan is evidence-led: step one is a host
test that *measures* the two distributions the two paths sample from today, turning the
double-softmax reading from inspection into an asserted fact before anything changes. Top-p defaults
to `1.0`, so behavior is unchanged unless a caller opts in; the only intended default-case change is
`of(...)` ceasing to double-softmax, which a new two-generators-agree test pins. Existing acceptance
assertions are untouched; the new behavior is pinned by new tests, never by weakening an existing
one.

#### Balance across categories

The last four cycles were correctness (three) then documentation (the PDSL reference). Both
foundation arcs are now closed, so returning to proof of value is the disciplined move the log has
been telegraphing, not a jump to the exciting category. It is a small–medium task whose committed
deliverable is a host-side API change plus deterministic sampling tests — **no training, no Metal,
runs on any backend, no workstream settings file.**

#### What comes next

1. This plan: `getTopP`/`setTopP` on `AutoregressiveModel`, wire top-p through both generators,
   unify the `of(...)` path with the `SlidingWindow` path, add the nucleus and cross-generator
   tests, and update the two internals pages.
2. **Measure** generation quality under top-p on a macOS/Metal node (a separate cycle: re-run the
   documented training run and compare greedy vs. top-p decoding), then a single-position KV-cache
   decode, which would make per-token sampling cheap enough to compare at length.
3. **Proof of value:** conditional diffusion training, now that both its conditioning (K/V) and its
   differential cancellation (λ) train honestly.
4. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
5. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2 (with the SAME fine-tune); the
   `io.almostrealism.collect` package split; a PDSL tutorial in `docs/tutorials/`; letting a trained
   model read the platform's own *source*.

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

### 2026-10-22 — Train the learned lambda of differential attention (condensed)

**Category:** Code Quality (correctness) · **Branch:** `project/plan-20261022-134809` ·
**Plan:** [`PLAN-20261022-differential-lambda-gradients.md`](PLAN-20261022-differential-lambda-gradients.md)

The three `differential_attention*` layers applied the learned per-head λ with `scale(lambda, 1)`,
reading it as a constant, so the four `differentialLambda` vectors (`λ = exp(λq1·λk1) − exp(λq2·λk2) +
λinit`) were frozen at initialization — the one parameter that makes differential attention
*differential* never trained. The plan diagnosed where the gradient was lost by finite difference,
made the vectors trainable on the PR #697 trainable-operand template, and pinned them with a
per-vector finite-difference test; the three `TODO(review)` markers in `differential_attention.pdsl`
are gone and `DifferentialAttentionAssetTest` checks them (PR #716, merged). With PR #697 before it,
every learnable operand of every attention variant the platform ships now receives its gradient —
closing the correctness arc, apart from the SAME resampling site deferred to the cycle that first
fine-tunes that autoencoder.

### 2026-10-21 — Train the K/V projections of cross-attention and transformer resampling (condensed)

**Category:** Code Quality (correctness) · **Branch:** `project/plan-20261021-012939` ·
**Plan:** `PLAN-20261021-cross-attention-kv-gradients.md` (since removed; see git history)

`sequenceCrossAttention` (the `DiffusionTransformer` conditioning mechanism) and
`TransformerResamplingFeatures.resamplingLayerBlock` buffered their K/V operands through fixed tensors
read back as constants, so their key/value projection weights received exactly zero gradient — a
conditional model could not learn what in its context is relevant. The plan fed K and V to the score
and context products as graph operands, made the auxiliary-input path trainable (`BranchBlock
implements Learning`; `Model.setParameterUpdate` propagates to `Learning` auxiliary inputs), and
proved it with a per-slice `SequenceCrossAttentionGradientTest` (PR #697, merged). The one residual
(the branch aggregator not forwarding gradient upstream to `condEmbed`, which nothing consumes) and
the resampling site's SAME kernel-bound concern were documented as evidenced follow-ups, not papered
over. This began the "make the whole attention family's backward pass honest" arc the 10-22 cycle
finished.

### 2026-10-19 — Make the self-hosted LM write a readable sentence about itself

**Category:** Proof of Value
**Branch:** `project/plan-20261019-194959`
**Plan:** `PLAN-20261019-self-hosted-lm-nontrivial-generation.md` (since removed; see git history)

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

### 2026-10-18 — Fix the nondeterministic full-Jacobian weight gradient for the output projection (condensed)

**Category:** Code Quality (correctness) · **Branch:** `project/plan-20261018-174738` ·
**Plan:** `PLAN-20261018-full-jacobian-gradient-correctness.md` (since removed; see git history)

Once the autodiff internals page landed (the prerequisite the prior entry set), this cycle fixed the
most consequential foundation defect of its moment: `GradientFeatures.jacobianGradient` — the
full-Jacobian fallback that is the *reference every reduced-delta test is checked against* — produced
a nondeterministically wrong weight gradient for the 256×64 output projection from row 64 onward
(~1/10 of entries, varying run to run). The plan refused the `TODO(review)`'s 32-bit-overflow guess
(a deterministic overflow would miscompute the same entries every run; this did not) and followed
evidence with single-variable experiments and `ar-profile-analyzer` on the generated kernel. The fix
landed as PR #675 (hoisted kernel-index variables now take a 64-bit type via
`Expression.getDeclarationType`), and `DenseVectorJacobianProductTest.outputProjection` now asserts
agreement over repeated evaluations, with a new `ConvolutionJacobianGradientTest` for the same class
of path. That made the reference gradient trustworthy — the explicit gate for the readable-generation
push that followed.

### 2026-10-17 — Document automatic differentiation from `delta` to the backward pass (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261018-013621` ·
**Plan:** `PLAN-20261018-automatic-differentiation-internals.md` (since removed; see git history)

After the derivative API was consolidated (PRs #661, #666 — `CollectionProducer.delta(target, axis,
weight)` as the only public derivative method, customized through `deltaDependent`, `deltaElement`,
`deltaPartial` and `deltaMatrix`), `consult` confirmed there was no internals page for the backward
half of the execution story. The cycle wrote `docs/internals/automatic-differentiation.md` (PR #670):
extension points and their order, `ExpressionMatrix`, `DefaultGradientPropagation` and the backward
cells, and a "known limits" section naming the silent full-Jacobian fallback. That page was the
explicit prerequisite for the 2026-10-18 correctness cycle.

### 2026-10-09 — Compute matrix-multiply weight gradients without materializing the Jacobian (condensed)

**Category:** Performance (unblocking Proof of Value) · **Branch:** `project/plan-20261009-171224` ·
**Plan:** `PLAN-20261009-matmul-weight-gradient-vjp.md` (since removed; see git history)

The training doc itself named the lever: the generic backward pass formed each weight gradient from
the operator's full Jacobian (~17 billion terms for the 256×64 output projection). The plan replaced
that with a structure-aware vector-Jacobian product for the matrix-multiply family only, behind a
capability check with a byte-identical fallback (the `feature/lora-gradients` history made a narrow
scope mandatory). It landed as PR #628: a training step went from 3.6 s to 0.35 s and
`trainOnDocumentation` beat its unigram baseline for the first time (4.194 vs 4.912 bits/byte). It
also surfaced the full-Jacobian output-projection defect fixed on 2026-10-18.

### 2026-10-05 — Make the self-hosted LM beat its unigram baseline and generate text (condensed)

**Category:** Proof of Value · **Branch:** `project/plan-20261005-185050` ·
**Plan:** `PLAN-20261005-self-hosted-lm-beat-baseline-and-generate.md` (since removed; see git history)

After the from-scratch LM merged (PR #596) it still scored above its unigram baseline and had never
produced a byte of text. This cycle built the generation path (`SlidingWindowAutoregressiveModel`,
verified against a full forward pass) and all-window held-out scoring, and established that per-step
backward cost — not the model or recipe — was what held the run at the unigram rate. That finding
became the 2026-10-09 performance plan; the baseline and readable-generation milestones were cashed
by PRs #628 and #685.

### 2026-09-30 — Train a tiny byte-level causal transformer on the platform's own docs (condensed)

**Category:** Proof of Value · **Branch:** `project/plan-20260930-180401` ·
**Plan:** `PLAN-20260930-self-hosted-tiny-lm.md` (since removed; see git history)

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
**Plan:** `PLAN-20260929-finetune-compile-scaling-rebaseline.md` (since removed; see git history)

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
**Plan:** `PLAN-20260926-native-runtime-lifecycle-docs.md` (since removed; see git history)

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
