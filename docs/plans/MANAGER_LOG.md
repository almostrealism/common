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

### 2026-11-03 — Copy read-only aggregate slices once per argument map instead of before every dispatch

**Category:** Performance
**Branch:** `project/plan-20261103-041617`
**Plan:** [`PLAN-20261103-read-only-aggregate-copy-once.md`](PLAN-20261103-read-only-aggregate-copy-once.md)

#### What changed since last cycle

The rolling (sliding) key/value cache merged (PR #818): the self-hosted LM now decodes past
`seqLen` and matches the sliding window byte-for-byte past the trained context. But the cycle's
own measurement recorded that its throughput success criterion was **not met** — on Metal both
paths run ~200–260 B/s, and the rolling decode issues ~245 dispatches/byte against the sliding
window's ~123. The cache is correct but dispatch-bound, and the implementation memory named the
binding lever explicitly: argument-aggregation copy dispatches, tracker task `38f78778`. Alongside
it, the tokenizer byte-fallback / added-token-reachability work merged (PRs #819, #820) and a docs
QA round (PR #821). The rolling-cache docs (`training-a-language-model.md`, `ml-inference-pipeline.md`)
were corrected as the feature landed.

#### Category assessment (priority order)

- **Documentation — strong and current, checked not assumed.** The newest capability (the rolling
  cache) was documented as it merged, with a dedicated correction commit for ring addressing,
  rotation input and the length bound; the tokenizer byte-fallback work was correctness/QA work that
  landed with its own tests. `consult` surfaced no fresh flagship prose gap. 50 internals pages, 11
  tutorials, PDSL reference + tutorial, two simulation pages. Moved on.
- **Code quality — strong and owned by continuous QA.** The attention-correctness and cache-ownership
  arcs are closed; the standing items (`io.almostrealism.collect` split; `TransformerResamplingFeatures`
  buffered K/V, deferred to that autoencoder's first fine-tune) are real but not reachable flagships.
  Moved on.
- **Performance — a sharp, freshly-evidenced, high-leverage lever, and it decided the cycle.** The
  proof-of-value feature that *just landed* is dispatch-bound on exactly one mechanism: the
  argument-aggregation copy-in runs before every dispatch, including for read-only arguments (model
  weights, file-backed tensors) whose contents cannot change and whose aggregate slice the kernel
  never writes. Two tracker tasks cover it — `38f78778` (cut the device-copy *count*) and the newer,
  more self-contained `faca1b4d` (copy read-only slices *once*, from the 2026-11-02 performance QA
  round). The copy-back path is already minimized this way (`writtenSlices`, `isCopiedBack`); the
  copy-in is not. This is the category's definition: a known, measured bottleneck on the critical path.
- **Proof of value — the category these two tracks serve, but gated here.** Long-form self-description
  at usable speed and connectome-scale simulation are both capped by this copy path; the connectome
  MaleCNS reproduction still waits on owner-provided data; conditional diffusion is heavy. The highest-
  leverage move toward proof of value this cycle is removing the gate, not adding a feature behind it.

#### Why this task

I chose `faca1b4d` (copy read-only aggregate slices once) over the broader `38f78778`: it has the
strongest correctness invariant — a read-only source cannot change, and a slice the compiled scope
does not write is never overwritten, so after the first copy the aggregate slice stays correct — read
off the same compiled-scope evidence (`getWrittenSlices`) that already governs copy-back, never a
declared property. The first copy is made `unordered()`, mirroring the proven
`MemoryReplacementManager` read-only pattern, which is what removes the Metal bridged command buffer
(the host round trip), not merely the device copy. It is localized to `MemoryDataArgumentMap` plus one
`AcceleratedOperation.apply` call site, with existing aggregation tests pinning correctness on any
backend and a new structural regression test pinning the copy-once/per-dispatch split so it cannot be
gamed by a timing threshold. It unblocks **both** proof-of-value tracks at once — the LM decode and the
synthetic-connectome simulation — and shrinks the set `38f78778` must still address.

#### Balance across categories

The last cycle was proof of value (rolling KV cache). Returning to performance now is the disciplined
move the measurement forced, not a detour: the foundation is covered and current, the proof-of-value
feature is already landed and correct, and its promised win is blocked precisely here. It is a
small–medium task needing a Metal node for the throughput and bridged-commit measurements (workstream
settings file present), as the decode-throughput and rolling-cache cycles did; correctness is
verifiable on any backend.

#### What comes next

1. This plan: the copy-once partition in `MemoryDataArgumentMap`, the once/per-dispatch submit in
   `AcceleratedOperation.apply`, the structural regression test, and the Metal measurements.
2. **Performance, complementary:** `38f78778` — cut the *count* of per-dispatch device copies for
   slices that must still be copied every dispatch (aggregate-only-when-over-limit; skip copy-in of a
   fully-overwritten destination; one multi-region copy dispatch on Metal), so the cache beats the
   sliding window on dispatches/byte outright.
3. **Proof of value, self-hosted LM:** attention sinks (pin the first `k` tokens) if the rolling
   cache's long-generation quality degrades.
4. **Proof of value, connectome:** the MaleCNS whole-brain ingest and the Shiu et al. reproduction,
   once the owner provides the ~2 GB tables and a Brian2 reference.
5. **Proof of value:** conditional diffusion training.
6. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs > 1000) and a
   reduced form for the convolution input gradient.
7. Still open: `TransformerResamplingFeatures`' buffered K/V; the `io.almostrealism.collect` package
   split; the unbuilt PDSL research/education features; letting a trained model read the platform's
   own *source*, the deepest self-understanding aim.

### 2026-11-02 (later) — Decode the self-hosted LM past its trained context with a rolling key/value cache

**Category:** Proof of Value (self-hosted LM generation)
**Branch:** `project/plan-20261102-174749`
**Plan:** [`PLAN-20261102-sliding-kv-cache.md`](PLAN-20261102-sliding-kv-cache.md)

#### What changed since last cycle

The neuron-level connectome documentation landed (PR #814, merged today), with its drift-guard test
and the `llms.txt`/README cross-links, so the platform's most distinctive new science capability is
now covered connectively, not just by Javadoc. The two QA rounds that merged alongside it
(`qa/performance`, `qa/pdsl`) and the earlier `qa/defect` round keep the foundation current. Nothing
foundational broke.

#### Category assessment (priority order)

- **Documentation — strong and current, checked not assumed.** Two documentation cycles just landed
  (the connectome pipeline today, the PDSL authoring tutorial before it), and `consult` surfaced only
  weak or stale gaps (a `SimulationRunner` internals page — already covered by `compute/time/README.md`;
  the September SA3-prep classes — a staleness item owned by docs QA). The self-hosted LM's generation
  docs are detailed and current. No fresh flagship gap. Moved on.
- **Code quality — closed arc, owned by continuous QA.** The whole attention backward-pass correctness
  arc (cross-attention K/V, differential lambda, the full-Jacobian output-projection gradient) is
  closed, and the KV-cache ownership defect was repaired with its own guard. The standing items
  (`io.almostrealism.collect` split; `TransformerResamplingFeatures` buffered K/V, deferred until that
  autoencoder is first fine-tuned) are real but not reachable flagships this cycle. Moved on.
- **Performance — no PM-level blocker.** The decode cache now beats the sliding window; the standing
  levers (weighted-sum `matmul` reduced form for outputs > 1000, convolution input-gradient reduced
  form) are real but non-blocking. Moved on.
- **Proof of value — the category, with a sharp reachable instance whose gate is now met.** The
  KV-cache decode is now the *faster* path (PR #783), but it is capped at `seqLen` by design — fixed
  `(seqLen, dim)` caches, and `KeyValueCacheAutoregressiveModel.load` throws at a step past `seqLen` —
  so the moment the model writes more than its 64-byte trained context about itself, it falls back to
  the slower sliding window. The Manager Log has named a rolling (sliding) key/value cache as the next
  proof-of-value step for three cycles, each time conditioned on the cache first being faster than the
  sliding window. That condition is now met, and no tracker task exists for it yet.

#### Why this task

It cashes the arc the whole self-hosted LM track points at: making self-description *usable at length*,
not just for 64 bytes. The investigation established the capability is tractable without a kernel
redesign, from one property of RoPE: attention scores depend only on the *relative* position of query
and key (`R(m)ᵀR(n) = R(n − m)`), so a ring-buffer cache that stores each key rotated **once** at its
absolute position reproduces sliding-window attention for the newest token exactly — no re-rotation,
ever — because the relative distance between the newest query and each retained key is identical under
both schemes, and the newest row is the only one the generator samples from. That reduces the work to
two mechanical, shared-primitive changes (ring-buffer `cache_write` indexing by `position mod seqLen`;
rotary rotation for positions beyond the fixed `(seqLen, …)` frequency table, computed from the stored
inverse frequencies), plus a sibling `SlidingKeyValueCacheAutoregressiveModel` and one correctness
test. Crucially the oracle already exists and is itself verified against a full forward pass —
`slidingWindowGenerationMatchesFullForward` — so the new generator must match the sliding window
**byte-for-byte past `seqLen`**, a robust greedy-argmax assertion that needs no loosened tolerance and
cannot be gamed. The two primitive changes touch kernels every attention model shares, so the plan
requires proving them inert for the bounded path (`position mod seqLen == position` within `seqLen`).

*Correction (implementation and review):* the byte-for-byte claim holds only for a single block. The
sliding window recomputes the retained tokens through every block each step, so in a deeper model their
deeper keys and values change as tokens leave the window, while the ring keeps the vectors each token was
decoded with. The rolling cache is per-layer window attention, and its test pins exact agreement for one
block and the eviction property for two. The plan's "Corrections found during implementation" section
has the details.

#### Balance across categories

The last three cycles were performance (KV-decode copies) then two documentation cycles (PDSL tutorial,
connectome pipeline). Returning to proof of value now is the disciplined move the log has telegraphed,
not a jump to the exciting category: the foundation is covered and current, the gate this step waited on
is met, and the connectome proof-of-value step (the MaleCNS whole-brain reproduction) is still blocked on
owner-provided data, so it is not the reachable one this cycle. It is a medium–large task that needs a
Metal node for the throughput comparison (workstream settings file present), as the readable-generation
and decode-throughput cycles did.

#### What comes next

1. This plan: the arbitrary-position rotary on `RotationFeatures`, the ring-buffer `cache_write`, the
   `SlidingKeyValueCacheAutoregressiveModel` generator, `slidingKeyValueCacheMatchesSlidingWindowPastSeqLen`,
   the long-run throughput measurement, and the Generation-section rewrite.
2. **Proof of value, quality:** attention sinks (pin the first `k` tokens) if the rolling cache's
   match-the-sliding-window quality proves to degrade on long generations — a follow-up, not this cycle.
3. **Proof of value, connectome:** the MaleCNS whole-brain ingest and the Shiu et al. reproduction, once
   the owner provides the ~2 GB tables and a Brian2 reference.
4. **Proof of value:** conditional diffusion training.
5. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs > 1000) and a
   reduced form for the convolution input gradient.
6. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2; the `io.almostrealism.collect`
   package split; the unbuilt PDSL research/education features; letting a trained model read the
   platform's own *source*, the deepest self-understanding aim.

### 2026-11-02 — Document the neuron-level connectome simulation pipeline

**Category:** Documentation
**Branch:** `project/plan-20261102-014251`
**Plan:** [`PLAN-20261102-neuron-level-connectome-simulation-docs.md`](PLAN-20261102-neuron-level-connectome-simulation-docs.md)

#### What changed since last cycle

Two large things landed in the last two days. The decode-faster-than-sliding-window work
merged (PR #783): the KV-cache decode's host-blocking aggregate copy-back is now read off the
compiled scope instead of a declared shortcut (`AssignmentSupport.containsAssignment` inferred
from the `Scope`, after the owner rejected a `getWrittenDestination()` declaration — recorded in
`INVESTIGATION-20261031-declared-property-shortcut.md`), and `training-a-language-model.md` carries
the measured figures. CUDA joined automatic backend selection (PR #810). Larger for this cycle: the
**neuron-level connectome** subsystem merged (PRs #806, #809) — `NeuronConnectome` (a signed
synapse-count matrix in CSR, read through `StateDictionary`, whose weights carry a
`CompressedRowTraversalOrdering` so synaptic input is one sparse `matmul`), `ConnectomeNetwork` (the
Shiu et al. (2024) whole-brain model composed in `/pdsl/connectome.pdsl`, stepped by
`SimulationRunner`, with replicas and `meanRates`), the `ExponentialSynapseNeurons`/`PoissonInput`
`Block`s in `domain/biology`, and the `connectome_csr.py`/`brian2_reference.py` ingest-and-oracle
scripts in `studio/bioscience` — with the synthetic connectome and the FlyWire "sugar circuit" plus
their Brian2 reference spikes committed as test resources. The platform now reproduces a published
whole-brain experiment's spikes, spike for spike.

#### Category assessment (priority order)

- **Documentation — a real, fresh gap, and it decided the cycle.** The neuron-level connectome is
  the most distinctive capability the platform added this month, and the only prose explaining it is
  two module READMEs and the classes' Javadoc. No internals page walks the pipeline (CSR ingest →
  `NeuronConnectome` → `ConnectomeNetwork` step in PDSL → `SimulationRunner` run → Brian2 match). The
  one existing simulation page, `neural-population-simulation.md`, is about the *aggregate* LIF/rate
  **population** path — a different, coarser model — and `llms.txt` still names only the older
  `CellTypeConnectome` loader, so a reader following the index never discovers the neuron-level
  subsystem. This is the reference-exists/connective-tissue-missing situation again, on the newest
  high-fidelity layer.
- **Code quality — strong and owned.** The KV-decode shortcut was caught and repaired in review, with
  its own investigation document; the attention-correctness and cache-ownership arcs are closed. No
  reachable flagship defect.
- **Performance — no PM-level blocker.** The decode cache now beats the sliding window; the standing
  levers (weighted-sum `matmul` reduced form, convolution input gradient) are real but non-blocking.
- **Proof of value — live, deliberately deferred.** The MaleCNS whole-brain reproduction and its
  benchmark are open for Common 0.77 and build directly on the pipeline this page documents, so
  documenting it first is the cheaper ordering; conditional diffusion is heavy; the sliding KV cache
  waits on confirming the decode cap binds.

#### Why this task

It is the disciplined pick: the earliest category with genuine, reachable, high-leverage work. It is
the 2026-10-27 move (which documented the aggregate population path) applied to the newer
neuron-level path that reproduces a published result. It is reachable today **on any backend** — the
synthetic connectome (40 neurons) and its Brian2 reference ship as committed test resources, so the
page's worked example and its drift-guard (`NeuronLevelConnectomeSimulationExamplesTest`, modeled on
`NeuralPopulationSimulationExamplesTest`) need no curated download and no macOS node. Every complete
excerpt the page shows is exercised by the test, asserting robust backend-independent properties
without reducing dimensions, loosening tolerances or gating skips. For the self-understanding aim it
is the page that lets a model trained on the corpus reason about the platform simulating a living
brain at single-neuron resolution — the most distinctive thing it can now do on the science side.

#### Balance across categories

The last cycle was performance (the KV-decode copy-back). Returning to documentation now is not drift
toward the exciting category: a whole subsystem arrived in two days with only READMEs, the index does
not mention it, and the proof-of-value step that builds on it is next. It is a medium task touching no
production code and running on any node, so no workstream settings file accompanies it.

#### What comes next

1. This plan: `docs/internals/neuron-level-connectome-simulation.md`, the
   `NeuronLevelConnectomeSimulationExamplesTest` drift-guard, and the `llms.txt` /
   `neural-population-simulation.md` / README cross-links.
2. **Proof of value, connectome:** the MaleCNS whole-brain ingest and the Shiu et al. reproduction
   gate (open for Common 0.77), now with its substrate documented.
3. **Proof of value, self-hosted LM:** a sliding (rolling) key/value cache for long-form generation
   past `seqLen`, if the decode cap binds now that the cache path is faster than the sliding window.
4. **Proof of value:** conditional diffusion training.
5. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider than 1000)
   and a reduced form for the convolution input gradient.
6. Still open: `TransformerResamplingFeatures`' buffered K1/V/Q2/K2; the `io.almostrealism.collect`
   package split; the unbuilt PDSL research/education features; letting a trained model read the
   platform's own *source*, the deepest self-understanding aim.

### 2026-10-29 — Make the key/value-cache decode faster than the sliding window (condensed)

**Category:** Performance (self-hosted LM generation arc) · **Branch:** `project/plan-20261029-151904` ·
**Plan:** [`PLAN-20261029-kv-cache-decode-host-copies.md`](PLAN-20261029-kv-cache-decode-host-copies.md)

The decode-throughput measurement (PR #768) had found the cache path ~4× slower than the sliding
window on Metal: the single-position decode issued ~30 host-blocking aggregate copy-backs per byte
where the sliding path issued one, so it was dispatch/host-wait bound. The fix makes the copy-back a
property read off the compiled `Scope` rather than something copied back unconditionally —
`AssignmentSupport.containsAssignment` asks each assignment whether it may write an element of an
aggregated argument, so arguments a kernel does not write are not copied back. An initial version
declared this through a new `Computation.getWrittenDestination()`; the owner rejected it (it
duplicated `OutputSupport.getOutputVariable()` and let an implementor contradict the scope) and it was
replaced by the scope-inferred form, with the episode recorded in
`INVESTIGATION-20261031-declared-property-shortcut.md`. Landed as PR #783 with the measured figures in
`training-a-language-model.md`; the decode now issues strictly fewer kernels per byte than the sliding
window. Needed a Metal node for the measurement.

### 2026-10-28 (later) — Give the attention key/value caches an owner the caller can clear and release (condensed)

**Category:** Code Quality (correctness) · **Branch:** `project/plan-20261028-165007` ·
**Plan:** [`PLAN-20261028-attention-cache-ownership.md`](PLAN-20261028-attention-cache-ownership.md)

The decode-throughput measurement (PR #768) had cashed the KV-cache arc with an unexpected result
and exposed a correctness defect beneath the flagship LM generation path: `CausalLanguageModel.decodeModel`
allocated per-layer caches and handed them back, but the `attention(...)` overload it built took no
cache arguments, so the compiled decode read and wrote the caches `AttentionFeatures.attentionArguments`
allocated internally. The generator's `reset()` therefore cleared orphan buffers (passing its guard
only because the causal mask hides unwritten rows and decode overwrites in order) and `destroy()`
leaked the real caches. The root cause was general — `attentionArguments` allocated caches with no
owner, so every Java `attention(...)`/`transformer(...)` caller (`Llama2`, `MoonbeamMidi`) was in the
same position. The plan added a caller-supplied-cache `attentionArguments`/`attention` overload, used
it from `decodeModel`, deleted the orphan allocation, corrected the javadoc/doc, and added a
fail-on-`master` ownership guard (PR #773, merged). It runs on any backend and hands the performance
follow-up (`d8ad5001`) a decode graph whose cache buffers have a clear owner.

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

### 2026-10-21 → 10-22 — Make the attention family's backward pass honest (condensed)

Two Code-Quality (correctness) cycles that closed the attention-gradient arc. **10-21** (PR #697):
`sequenceCrossAttention` (the `DiffusionTransformer` conditioning mechanism) and
`TransformerResamplingFeatures.resamplingLayerBlock` buffered their K/V operands through fixed tensors
read back as constants, so their key/value projection weights received zero gradient — a conditional
model could not learn what in its context is relevant. The plan fed K and V as graph operands and made
the auxiliary-input path trainable (`BranchBlock implements Learning`; `Model.setParameterUpdate`
propagates to `Learning` auxiliary inputs), proved by `SequenceCrossAttentionGradientTest`. **10-22**
(PR #716): the three `differential_attention*` layers applied the learned per-head λ with
`scale(lambda, 1)`, freezing the `differentialLambda` vectors at initialization — the one parameter
that makes differential attention *differential* never trained; made trainable on the PR #697 template
and pinned per-vector by finite difference (`DifferentialAttentionAssetTest`). With both, every
learnable operand of every attention variant the platform ships now receives its gradient — closing the
arc, apart from the SAME resampling site deferred to the cycle that first fine-tunes that autoencoder.

### 2026-10-17 → 10-19 — Autodiff docs, the reference-gradient fix, and readable generation (condensed)

Three cycles that made the reference gradient trustworthy and cashed the first readable
self-description. **10-17 (Documentation,** PR #670**)** wrote `docs/internals/automatic-differentiation.md`
after the derivative API consolidated onto `CollectionProducer.delta(target, axis, weight)`, naming the
silent full-Jacobian fallback as a known limit — the explicit prerequisite for the next cycle.
**10-18 (Code Quality,** PR #675**)** fixed that fallback: `GradientFeatures.jacobianGradient`, the
reference every reduced-delta test checks against, produced a nondeterministically wrong weight gradient
for the 256×64 output projection (hoisted kernel-index variables now take a 64-bit type via
`Expression.getDeclarationType`); `DenseVectorJacobianProductTest.outputProjection` and a new
`ConvolutionJacobianGradientTest` guard it. **10-19 (Proof of Value,** PR #685**)** then landed the first
readable artifact of the self-understanding goal: `trainOnDocumentation` had beaten its unigram baseline
but greedy-decoded 96 spaces, and the investigation established the collapse was the *model*, not the
decoder, so the only acceptable fix was a better-generalizing model decoded exactly as the generator
already does — not a weakened assertion. Its "what comes next" set the sampling-quality and KV-cache-decode
steps the 10-25 → 10-29 cycles cashed.

### Earlier (Sep–Oct 2026, condensed) — the self-hosted LM track from feasibility to readable generation

The current self-hosted LM arc was set up by a run of cycles now fully cashed (plans since removed;
see git history):

- **2026-09-26 (log restart), native-runtime-lifecycle docs** (`project/plan-20260926-172935`, PR #562)
  closed the back half of the execution story — what happens to a compiled kernel and its memory after
  compilation — completing the internals corpus from Producer to released memory. It restarted this log
  (the prior one was deleted 2026-04-06) and named performance then a self-hosted LM run as the next
  priorities. (Context: March 2026 landed the compilation-pipeline and ML-inference internals docs and
  deferred the `io.almostrealism.collect` split; April–September 2026 was heavy agent-infrastructure
  investment — FlowTree, ar-manager, enforcement/test-integrity — plus the SA3 / Qwen tracks.)
- **2026-09-29, LoRA fine-tune compile re-baseline** (`project/plan-20260929-043714`) overturned
  `FINE_TUNE_FAIL.md`'s February "infeasible" verdict, which two compile-time fronts had made stale, and
  measured the transformer backward pass against reality so the next cycle could scope a training run.
- **2026-09-30, train a tiny byte-level causal transformer from scratch** (`project/plan-20260930-180401`,
  PR #596) turned "training is feasible" into the first self-understanding artifact. The blocker was
  missing capability, not performance: it added causal sequence attention, a trainable embedding, a byte
  tokenizer, a text dataset and the `1/rows` NLL normalization — all general and reused since — and
  surfaced the K/V-gradient-through-fixed-buffers defect the 10-21/10-22 cycles later finished.
- **2026-10-05, beat the unigram baseline and generate** (`project/plan-20261005-185050`) built the
  generation path (`SlidingWindowAutoregressiveModel`, verified against a full forward pass) and
  established that per-step backward cost — not the model — held the run at the unigram rate, which
  became the 2026-10-09 performance plan.
- **2026-10-09, matmul weight-gradient VJP** (`project/plan-20261009-171224`, PR #628) replaced the
  full-Jacobian weight gradient (~17 billion terms for the 256×64 output projection) with a
  structure-aware vector-Jacobian product behind a capability check with a byte-identical fallback: a
  training step went 3.6 s → 0.35 s and `trainOnDocumentation` beat its unigram baseline for the first
  time (4.194 vs 4.912 bits/byte). It also surfaced the full-Jacobian output-projection defect the
  2026-10-18 cycle fixed.
