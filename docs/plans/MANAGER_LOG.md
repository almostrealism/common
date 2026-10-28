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

### 2026-10-28 (later) — Give the attention key/value caches an owner the caller can clear and release

**Category:** Code Quality (correctness)
**Branch:** `project/plan-20261028-165007`
**Plan:** [`PLAN-20261028-attention-cache-ownership.md`](PLAN-20261028-attention-cache-ownership.md)

#### What changed since last cycle

The decode-throughput measurement landed (PR #768, merged): `CausalLanguageModelDecodeThroughputTest`
now measures both decode paths on the documentation-run configuration, the Generation section of
`training-a-language-model.md` states the measured figures, and `OperationProfileNode.getRunCount()`
and `ByteTokenizer.isValidUtf8()` are new. The measurement cashed the arc — but with an honest and
unexpected result: the key/value-cache decode is **~4× slower** than the sliding window on Metal
(~50 vs ~190 bytes/second), dispatch- and host-wait-bound on ~30 host-blocking `mtlCopy` waits per
byte. That overturned the premise the whole KV-cache cycle rode on (that the cache is cheaper per
byte), so the benchmark asserts only path agreement and reports the rest. Two follow-ups were filed:
`d8ad5001` (remove the host-blocking copies so the cache is actually faster — Performance, Metal) and
`9825196c` (the decode model's caches have no owner — Code Quality).

#### Category assessment (priority order)

- **Documentation — excellent and current.** I verified the newest surface rather than taking it on
  faith: the Generation section was just rewritten from measurement, `ml-inference-pipeline.md` has
  the single-position KV-cache decode section, and `consult` finds no flagship gap. The 2026-09 doc
  caveat about cache zero-initialization is long resolved (caches are `calloc`-backed). Moved on.
- **Code quality — a real correctness defect, and it decided the cycle.** `9825196c`:
  `CausalLanguageModel.decodeModel` allocates per-layer key/value caches and hands them back, but the
  `attention(...)` overload it builds takes no cache arguments — the caches the compiled decode model
  reads and writes are the ones `AttentionFeatures.attentionArguments` allocates internally. So the
  generator's `reset()` clears orphan buffers (the real caches are never cleared; it passes its guard
  only because the causal mask hides unwritten rows and decode overwrites in order), `destroy()`
  leaks the real caches, and the javadoc/training-doc describe a mechanism that does not exist. The
  root cause is general — `attentionArguments` allocates caches with no owner, so every cached-attention
  caller (Qwen3, SkyTnt) is in the same position — which is what lifts it above a one-line patch.
- **Performance — live, deliberately deferred.** `d8ad5001` (the ~4× slowdown) is the sharpest
  performance lever on the board, but it is Metal-gated and ranks below an open correctness defect
  under the priority order. It is next cycle's work, and fixing ownership first hands it a decode
  graph whose cache buffers have a clear owner.
- **Proof of value — not reachable this cycle.** The connectome proof-of-value steps still wait on
  the sparse product merging and the owner's MaleCNS tables; conditional diffusion training is heavy.

#### Why this task

It is the disciplined pick: the earliest category (after documentation, which is covered) with
genuine, reachable, high-leverage work. The defect sits directly beneath the flagship LM generation
path — a `reset()` that is a no-op by accident and a `destroy()` that leaks — and its fix is a
general improvement to `AttentionFeatures`, not a local patch: give the caller a way to own the
caches the attention block actually uses, the honest form of "own your collaborators." It is
additive and backward compatible (every self-allocating caller is untouched), and its drift guard
asserts the ownership invariant structurally so it cannot be satisfied by the causal-mask accident
that masks the bug today — the test is written to **fail on `master`** first. Small–Medium, and it
**runs on any backend** (no Metal, no workstream settings file), unlike the performance follow-up.

#### Balance across categories

The last cycle was proof of value (the throughput measurement); the two before it documentation. This
returns to the foundation for one cycle to close the correctness defect that same measurement exposed
before building the performance work on top of it. That is sequencing, not drift: fix what the
benchmark found is wrong, then make the path fast. The next cycle should take `d8ad5001` on a Metal
node.

#### What comes next

1. This plan: the caller-supplied-cache `attentionArguments`/`attention` overload, its use from
   `decodeModel`, the orphan-allocation deletion, the javadoc/doc correction, and the fail-on-master
   ownership guard.
2. **Performance, Metal (`d8ad5001`):** remove the ~30 host-blocking `mtlCopy` waits per byte from the
   decode graph so the cache path is actually faster, then add the strictly-fewer-kernels-per-byte
   assertion to `CausalLanguageModelDecodeThroughputTest` and update the Generation-section figures.
3. **Proof of value, if the cache then beats the sliding window but the `seqLen` cap binds long-form
   speed:** a sliding (rolling, position-remapped) key/value cache — filed from `d8ad5001`'s numbers,
   not before.
4. **Proof of value, connectome:** once the CSR sparse product merges and the owner provides the
   MaleCNS tables and a Brian2 reference, the ingest into CSR form and the Shiu et al. reproduction.
5. **Proof of value:** conditional diffusion training.
6. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000).
7. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2; the `io.almostrealism.collect`
   package split; the unbuilt PDSL research/education features; letting a trained model read the
   platform's own *source*, the deepest self-understanding aim.

### 2026-10-28 — Measure the self-hosted LM's key/value-cache decode speed, and generate prose about the platform at that speed

**Category:** Proof of Value (self-hosted LM generation)
**Branch:** `project/plan-20261028-004435`
**Plan:** [`PLAN-20261028-lm-decode-throughput.md`](PLAN-20261028-lm-decode-throughput.md)

#### What changed since last cycle

The neural-population-simulation documentation landed (PR #762, merged):
`docs/internals/neural-population-simulation.md` walks one spiking population from device-resident
state through the LIF step, ring-buffer delay, connectivity, in-kernel Poisson drive and a
`SimulationRunner` run, drift-guarded by `NeuralPopulationSimulationExamplesTest`, and brought
`shape-and-traversal.md`, `llms.txt` and the graph/biology READMEs up to date. Independently
verified this cycle: the rate model (`RatePopulation`, `CellTypeConnectome`) merged with it and is
documented in the same page; `CounterRandom`, `SimulationRunner` and the new `domain/biology` /
`studio/bioscience` modules are all in `llms.txt`. The simulation substrate is now covered
connectively, not just by Javadoc. On the connectome track, the CSR sparse matrix and
segmented-reduction product (PR #758) and a simulation run-ahead performance change (PR #764) are
still open; the connectome's own proof-of-value gate (MaleCNS ingest, Shiu et al. reproduction)
waits on that sparse product merging **and** on the project owner providing the ~2 GB MaleCNS tables
and a Brian2 reference — so it is not reachable in a self-contained session this cycle.

#### Category assessment (priority order)

- **Documentation — genuinely excellent and current.** Checked the newest substrate against the
  durable corpus rather than taking the last two cycles on faith: the rate model, the spiking
  population, `CounterRandom`/`AR_RANDOM`, `SimulationRunner` and both new modules are documented and
  indexed. Tutorial 10 closed the PDSL teaching gap. `consult` finds no flagship gap. Moved on.
- **Code quality — strong and owned.** The KV-decode `seqLen` out-of-bounds guard a reviewer flagged
  on the KV-cache branch is in fact present on `master` now (`KeyValueCacheAutoregressiveModel.load`
  throws `IllegalStateException` at or beyond `seqLen`), so that correctness item is closed, not
  open. The attention-correctness arc is closed; the remaining items are owned by continuous QA and
  the open cleanup PRs (#766 PdslBuiltins instance type). No flagship defect. Moved on.
- **Performance — no PM-level blocker.** The standing lever (weighted-sum `matmul` reduced form,
  outputs > 1000) is real but non-blocking, and simulation per-step dispatch overhead is already in
  flight as PR #764. Moved on.
- **Proof of value — the category, and a sharp, reachable instance.** The KV-cache decode landed
  byte-identical to the full forward, but **its throughput was never measured.** The Generation
  section of `training-a-language-model.md` states the sliding-window cost (~100 bytes/second for the
  trained model) and then says only that the cache decode "avoids it" — no figure, and no benchmark
  anywhere compares the two. The one performance claim the whole KV-cache cycle was justified by is
  unquantified.

#### Why this task

It cashes the arc. The "what comes next" list has named this step five cycles running — measure the
cache's throughput improvement and compare greedy-vs-nucleus generation now that decoding is cheap
enough to run at length. It is bounded and low-risk: one benchmark test modeled on the existing
`CausalLanguageModelForwardProfileTest` harness (same `OperationProfileNode`, Metal dispatch/commit
counters, wall-clock loop), measuring both decode paths on one small model and reporting the speedup
and per-byte dispatch reduction, plus a greedy-vs-top-p sample over a documentation prompt. The
documentation's unquantified sentence is replaced with the measured figure, drift-guarded by the new
benchmark the way the correctness claim is already guarded by `keyValueCacheDecodeMatchesFullForward`.

Crucially the benchmark **asserts only the robust structural property** — the cache issues strictly
fewer kernels per decoded byte than the sliding window — and *reports* wall-clock bytes/second
without asserting a host-dependent threshold, so it is not a flaky timing gate and cannot be gamed by
weakening a tolerance. For the self-understanding aim this is the measured confirmation that the
software can now describe its own architecture at a usable rate, which is the first time that is both
true and quantified.

The fast path is capped at `seqLen` positions by design. If the measurement shows that usable
long-form generation past the trained context is therefore still stuck at sliding-window speed, the
honest next capability is a **sliding** key/value cache (rolling, position-remapped — sliding-window
semantics at cache cost). That is explicitly out of scope here and becomes an evidenced tracker task,
not work on this branch.

#### Balance across categories

Two documentation cycles preceded this (Tutorial 10, then the simulation page) after a long
proof-of-value run, and the log said the next cycle should return to proof of value unless something
foundational broke. Nothing did: the foundation is covered and current, so returning to proof of
value is the disciplined move, not a jump to the exciting category. It is a medium task that needs a
Metal node for comparable numbers (workstream settings file present), exactly as the readable-
generation and the deferred throughput measurement always were.

#### What comes next

1. This plan: `CausalLanguageModelDecodeThroughputTest`, the Generation-section rewrite with the
   measured cache throughput and speedup, and the greedy-vs-nucleus sample.
2. **Proof of value, if the cap binds:** a sliding (rolling) key/value cache so long-form generation
   runs at cache speed past `seqLen`, filed from this cycle's measurement.
3. **Proof of value, connectome:** once PR #758 (CSR sparse product) merges and the owner provides
   the MaleCNS tables and a Brian2 reference, the ingest into CSR form and the Shiu et al.
   reproduction (the Phase 1 gate), then the throughput benchmark on every backend.
4. **Proof of value:** conditional diffusion training, on an attention backward pass now honest end
   to end for both conditioning (K/V) and differential cancellation (λ).
5. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than
   1000), now with connectome-scale evidence behind it.
6. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2; the `io.almostrealism.collect`
   package split; the unbuilt PDSL research/education features; letting a trained model read the
   platform's own *source*, the deepest self-understanding aim.

### 2026-10-27 — Document simulating a spiking neural population, from state to recorded spikes

**Category:** Documentation
**Branch:** `project/plan-20261027-081706`
**Plan:** [`PLAN-20261027-neural-population-simulation-docs.md`](PLAN-20261027-neural-population-simulation-docs.md)

#### What changed since last cycle

The PDSL tutorial landed (PR #757, merged): `docs/tutorials/10-pdsl-model-definitions.html` with
`PdslTutorialExamplesTest`, and its first-mistake messages pinned by fixtures. That closes the PDSL
teaching gap. Separately, and much larger, a whole simulation substrate reached `master` this week
through the connectome model organism's Phase 1 tracker tasks, outside the manager-log cycles:
ordered collections as kernel inputs (`TraversalOrdering.bind`, absent elements reading as zero),
integer-exact split indices, integer safetensors/protobuf payloads, `CounterRandom` (in-kernel
counter-based random numbers, `AR_RANDOM=counter`), `SimulationRunner` (the simulation loop owner)
and `LeakyIntegrateAndFirePopulation`. The PDSL core moved to `domain/graph`
(`org.almostrealism.dsl`, `PdslExtension`, `WeightGroup`) and two modules appeared,
`domain/biology` and `studio/bioscience`. Two branches are still open on the same track: the
sparse matrix–vector product (`SparseMatrix`, `SegmentedWeightedSumComputation`) and the cell-type
rate model (`RatePopulation`, `CellTypeConnectome`).

#### Category assessment (priority order)

- **Documentation — a real gap, and it decided the cycle.** The new substrate is well covered by
  Javadoc, and `SimulationRunner` and split indices have good internals sections. But I checked
  each piece against the durable corpus: `AR_RANDOM` and `CounterRandom` appear in no Markdown
  file at all; `LeakyIntegrateAndFirePopulation` is documented nowhere outside its own class;
  `shape-and-traversal.md` still sketches a two-method `TraversalOrdering` that predates `bind`,
  signatures and the absent-element contract; `llms.txt` lists neither biology module nor
  `simulation-loop-examples.md`, and `domain/graph/README.md` never mentions PDSL, which now lives
  there. Worse, the only example of driving a LIF population (`LeakyIntegrateAndFirePopulationTest`)
  steps it with a hand-written host loop, because the population exposes `step(Producer)` rather
  than implementing `Temporal`. The next connectome agents will learn the wrong pattern from it.
  Nothing puts the pieces together as one simulation.
- **Code quality — one real item found, owned elsewhere.** `PLAN-20261025-spiking-lif-population.md`
  has no lifecycle header, so `RepositoryPlansTest.test_every_plan_has_a_valid_header` fails on
  `master`. The open rate-model branch adds that header; plan review on this branch added the
  identical header (byte for byte, so the two merge cleanly), because this plan's own validation
  step runs that test. The `io.almostrealism.collect` split is unchanged.
- **Performance — no blocker.** The weighted-sum `matmul` reduced form remains real and
  non-blocking; the rate-model branch measured that a dense 11,751-wide step on Metal is bound by
  its looped reduction kernel, which is useful evidence for that lever.
- **Proof of value — live, deliberately not chosen.** The KV-cache throughput measurement is
  Metal-gated, conditional diffusion training is heavy, and the connectome's proof-of-value steps
  (Shiu et al. reproduction) wait on ingest and the sparse product.

#### Why this task

It is the 2026-10-26 move again, applied to the newest domain: the reference material exists, the
connective material does not. One internals page walks a spiking population from device-resident
state through the LIF step, the ring-buffer delay, connectivity, reproducible in-kernel Poisson
drive and a `SimulationRunner` run with a device-resident spike recording, and states the known
limits from evidence. A drift-guard test runs the page's example through `SimulationRunner` and
asserts its rate, silence, delay and reproducibility claims, so the page cannot rot, and it also
tests whether the adapter pattern works. If it does not, that becomes an evidenced tracker task,
not a production change in this cycle. Plan review found that, in a two-population tick, the
effective delay depends on the order of the two steps, and recorded that with the other open
questions in the plan. The cycle also brings `shape-and-traversal.md`, `llms.txt`
and the graph and biology READMEs up to date. It runs on any node.

For the self-understanding aim, this is the page that lets a model trained on the corpus reason
about the platform's simulations of living systems, which is the most distinctive thing the
platform added this month.

#### Balance across categories

Two documentation cycles in a row (tutorial, now this) after a long proof-of-value run. That is
not drift. The substrate arrived fast through tracker tasks, and documenting it now, before the
connectome's proof-of-value steps build on it, is cheaper than repairing the patterns later. The
next cycle should go back to proof of value unless something foundational breaks.

#### What comes next

1. This plan: `docs/internals/neural-population-simulation.md`,
   `NeuralPopulationSimulationExamplesTest`, and the `shape-and-traversal.md`, `llms.txt` and
   README corrections.
2. **Proof of value, connectome:** once the sparse product merges, the MaleCNS ingest into CSR
   form and the Shiu et al. reproduction on its own FlyWire inputs (tracker tasks 2218ffdf, d72c5ca4).
3. **Proof of value, Metal:** KV-cache decode throughput vs full forward, and greedy-vs-top-p over
   long samples.
4. **Proof of value:** conditional diffusion training.
5. **Performance:** the reduced form for the weighted-sum `matmul` lowering, now with a
   connectome-scale measurement behind it.
6. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2; the `io.almostrealism.collect`
   split; the PDSL research/education features; letting a trained model read the platform's own
   source.

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

### 2026-10-26 — Decode the self-hosted LM one token at a time with a key/value cache (condensed)

**Category:** Proof of Value (self-hosted LM generation) · **Branch:** `project/plan-20261026-022005` ·
**Plan:** [`PLAN-20261026-kv-cache-single-position-decode.md`](PLAN-20261026-kv-cache-single-position-decode.md)

With sampling unified, the remaining ceiling on generation was the full-forward-per-byte decode. The
cached single-position `attention(...)` family and `causalMask(shape, position)` already existed; what
the self-hosted LM lacked was a decode path for its **weight layout** — each block stores a fused
`qkv` `(3·dim, dim)` weight and the model stores `rope_inv_freq` once, while the cached overloads want
separate `wq`/`wk`/`wv` and a position-indexed `freqCis`. The plan bridged those shapes (fused→split
projection, inverse-frequencies→position-indexed rotary), built `CausalLanguageModel.decodeModel` over
the same `StateDictionary`, and wrapped it in `KeyValueCacheAutoregressiveModel` sharing the top-p
sampling path, pinned by a byte-identical-to-full-forward assertion at every position (PR #753,
merged). The *measurement* was deferred to Metal — cashed, with a surprise, by the 2026-10-28 cycle,
which found the cache ~4× slower and exposed the cache-ownership defect the 2026-10-28 (later) cycle
plans to fix.

### 2026-10-25 — Expose nucleus (top-p) sampling through the autoregressive generators (condensed)

**Category:** Proof of Value (self-hosted LM generation) · **Branch:** `project/plan-20261025-022810` ·
**Plan:** [`PLAN-20261025-nucleus-sampling-generators.md`](PLAN-20261025-nucleus-sampling-generators.md)

`AutoregressiveModel.sampleToken` already implemented nucleus filtering, but no public API could set
`topP`, and `AutoregressiveModel.of(...)` applied a redundant second softmax that flattened the
tempered distribution. The plan measured the two paths' distributions first, then unified both
generators on one `sampleToken` call with the configured temperature and top-p (PR #741, merged).
That settled the sampling strategy and set up the KV-cache decode cycle.

### 2026-10-23 — Document the PDSL language (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261023-025021` ·
**Plan:** [`PLAN-20261023-pdsl-language-reference.md`](PLAN-20261023-pdsl-language-reference.md)

The three attention-correctness cycles all turned on what PDSL builtins do with their operands, yet
`consult` reported no complete PDSL reference. The cycle wrote `docs/internals/pdsl-language.md`
(grammar from `PdslParser`, definition kinds, resolution, imports, a builtin catalog with a
per-operand gradient column) with `PdslLanguageReferenceExamplesTest` parsing every example, and
completed the package Javadoc (merged 2026-10-24). Its teaching companion, Tutorial 10, followed on
2026-10-26.

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
