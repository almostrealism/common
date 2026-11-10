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

### 2026-11-10 — Fix the reflected-ray shading path so a mirror surface renders the scene

**Category:** Code Quality (correctness)
**Branch:** `project/plan-20261110-032517`
**Plan:** [`PLAN-20261110-reflected-ray-shape.md`](PLAN-20261110-reflected-ray-shape.md)
**Tracker:** `ec549648-4d2f-4515-b65e-cbb875098d3f`

#### What changed since last cycle

A whole new proof-of-value track opened and a large migration landed. The rings `raytracer`
(`ar-raytracer`) and `photonfield` (`ar-photonfield`) **libraries** were moved into `common` over
the past week (PRs #869, #875 and the commits around them: photon-absorber volumes, BRDFs,
`SpecularAbsorber`, `RayTracedAnimation`/`PhotonFieldAnimation` into `engine/render`; correctness
fixes for detach-identity, parallel-ray `Plane.MISS`, fps rounding, and a seeded instruction-cache
benchmark PR #874). Around them a **ferrofluid simulation** roadmap was written
([`PLAN-20261106-ferrofluid-simulation.md`](PLAN-20261106-ferrofluid-simulation.md), proposed) that
inventories exactly what an end-to-end ferrofluid (sim → mesh → glossy ray-trace → frame sequence)
needs and where the gaps are, and a `feature/simulation-frame-driver` branch added
`SimulationRunner.runFrames`, `RGBFeatures.heatmap`/`saveHeatmap`, and `RayTracedFrameSequence`.
QA rounds (`qa/pdsl`, `qa/coverage`) kept the foundation current.

#### Category assessment (priority order)

- **Documentation — adequately covered, checked not assumed.** The migration landed *library*
  code into the already-documented `engine/render` module; `consult` confirms `engine/render/README.md`
  covers `RayTracedScene`, material shading, `ReflectionShader`/`RefractionShader`, and `llms.txt`
  lists the render module. No brand-new subsystem arrived needing a flagship internals page, and the
  reflection render path is *broken* (below), so documenting it as a worked example would be
  premature. No fresh reachable prose gap. Moved on.
- **Code quality — a concrete, test-pinned correctness defect, and it decided the cycle.** The
  migration's only two end-to-end render tests (`ReflectionRefractionRenderTest`, `engine/render`)
  are both `@TestProperties(knownIssue = true)`, so the engine ships with **no asserting render test
  in CI**. The reflection case fails in `RayTracedScene.realize` — before any pixel — for two
  verified reasons: (a) `ReflectedRay` (`compute/geometry`) is a host-Java `ProducerComputation<Ray>`
  whose output carries no `TraversalPolicy`, so `RayFeatures.origin == subset(shape(3), r, 0)` throws
  "Subset cannot be performed without a TraversalPolicy"; (b) the `shadeFront` branch of
  `ReflectionShader` then calls `LightingEngineAggregator.getAccelerated()`, which reaches
  `RankedChoiceEvaluableForMemoryData.getAccelerated()` and throws `UnsupportedOperationException` —
  while the `shadeBack` branch and the primary ray already render through the aggregator's working
  host selection path. A real, measured, reachable defect on the critical path of a proof-of-value
  track. Chosen.
- **Performance — no PM-level blocker this cycle.** The copy-in/copy-back aggregate levers from the
  prior cycles are landed or standing; kernelizing the ray-tracer's per-pixel selection is itself a
  named follow-up (ferrofluid §5 item 2 / Phase 4), explicitly *after* correctness. Moved on.
- **Proof of value — the category this serves, but gated on exactly this fix and on owner choices.**
  The ferrofluid glossy look (Phase 2) is blocked on the reflection path; Phase 0/1 depend on
  `[OWNER]` fluid-method decisions (§6.1/§6.2) not yet made. Removing a correctness gate that is
  owner-independent outranks starting a phase that waits on an owner call.

#### Why this task

It is the disciplined pick under the priority order: documentation is covered, and the first
reachable code-quality item is a correctness defect pinned by an existing test. The fix is clean and
evidence-based: rebuild `ReflectedRay` as a shape-carrying Producer graph —
`ray(point, reflect(incident, normal))` = `concat(shape(6), origin, direction)` — which both fixes
gap (a) and removes a `FUNDAMENTAL RULE` violation (host-Java per-element `evaluate`, throwing
`getScope`); then route the `shadeFront` reflected-ray aggregation through the same working selection
path the `shadeBack` branch and the primary ray already use, resolving gap (b) without implementing
or weakening anything. Clearing the `knownIssue` on `reflectiveSphereShowsFloor` restores a CI render
assertion — additive, since the assertion already exists. Refraction (a separate `ClassCastException`)
and kernelizing the selection (performance) are explicitly scoped out. Backend-independent — a
graph-construction error rendered on the host — so it is verifiable on any node with no workstream
settings file.

#### Balance across categories

The last several cycles were performance (copy-in/copy-back) and documentation. Returning to code
quality now is not drift: a large migration landed library code whose flagship render path cannot
build its graph, and the proof-of-value track that migration was *for* is blocked precisely here. It
is a medium task touching two files plus one `knownIssue` flag, runs on any node.

#### What comes next

1. This plan: the `ReflectedRay` Producer-graph rewrite, the `shadeFront` selection-path switch, and
   the cleared `knownIssue` on `reflectiveSphereShowsFloor`. **Already implemented on an open PR
   (#886, `feature/glossy-reflection`), which also covers item 2 below** — so the live decision is to
   review/merge that PR rather than dispatch this plan fresh (see the plan's *Relationship to existing
   work* section).
2. **Code quality, sibling:** fix refraction (`RefractionShader` / `GeneratedColorProducer.getShape`
   `ClassCastException`) and clear `refractiveSphereShowsFloor` (ferrofluid §5 item 3). PR #886
   rewrites `RefractionShader` as a graph too, though it leaves `refractiveSphereShowsFloor` pinned
   `knownIssue` (the render assertion is not yet enabled).
3. **Proof of value, ferrofluid Phase 0:** the end-to-end vertical slice (height field → mesh →
   static render), once the owner settles the fluid-method / surface-representation choices
   (§6.1/§6.2). The plan recommends height-field + tessellation.
4. **Performance:** kernelize the ray-tracer's per-pixel selection and supersampling, and add a
   triangle acceleration structure (ferrofluid §5 item 2 / Phase 4), plus the standing
   aggregate-copy follow-ups.
5. Still open from prior cycles: the reduced `matmul`/convolution-gradient forms;
   `TransformerResamplingFeatures`' buffered K/V; the `io.almostrealism.collect` split; letting a
   trained model read the platform's own source.

### 2026-11-04 (later) — Skip the per-dispatch copy-in of an unread folded aggregate slice (condensed)

**Category:** Performance · **Branch:** `project/plan-20261104-182220` ·
**Plan:** [`PLAN-20261104-aggregate-copy-in-skip-unread.md`](PLAN-20261104-aggregate-copy-in-skip-unread.md)

Applied the copy-back arc symmetrically to the copy-in: the rolling KV cache (PR #818) landed
correct but dispatch-bound, issuing a device copy-in before every dispatch even for a folded slice
the compiled kernel never reads. Copy-once-read-only (PR #825) had removed read-only weights from
that count; this cycle added the dual — a position-bounded *read* query (`getReadSlices(Scope)`
mirroring `getWrittenSlices`/`containsAssignment`) so `ensureCopyOperations` skips the copy-in of any
unread slice, inferred off the compiled scope and never a declared property
(`INVESTIGATION-20261031-declared-property-shortcut.md`). Localized to `MemoryDataArgumentMap` with a
structural regression test pinning the partition. Needed a Metal node for the dispatches/byte
measurement; correctness on any backend.

### 2026-11-04 — Document text tokenization, from a string to the token ids a model consumes (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261104-133539` ·
**Plan:** [`PLAN-20261104-text-tokenization-docs.md`](PLAN-20261104-text-tokenization-docs.md)

The input layer of every language model was undocumented: no internals page on tokenization,
`llms.txt`'s only tokenizer line pointed at the wrong class (the `extern/ml-djl` wrapper, not
`engine/ml`'s `SentencePieceBPETokenizer`), the package Javadoc omitted `ByteTokenizer` and
`SentencePieceBPETokenizer`, and `engine/ml/README.md`'s tokenization examples did not compile. The
cycle wrote `docs/internals/text-tokenization.md` with `TextTokenizationExamplesTest` (exact token-id
assertions over a synthetic `ARTK` binary, `ByteTokenizer`, and `Qwen3Tokenizer.createTestTokenizer()`,
no download), corrected the README, completed the `package-info`, and added the `llms.txt` entry.
Ran on any node. Merged (PR #838).

### 2026-11-03 (later) — Document the vector-index subsystem (HNSW + disk store + model-keyed rebuild) (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261103-172701` ·
**Plan:** [`PLAN-20261103-vector-index-docs.md`](PLAN-20261103-vector-index-docs.md)

A whole vector similarity-search subsystem reached `master` (PR #827 and surrounding commits) —
`engine/ml`'s `org.almostrealism.persist.index` (`HnswIndex`, `ProtobufDiskStore`, `SimilarityMetric`)
and `studio/compose`'s model-keyed `ProtobufWaveDetailsStore` rebuild — with only README + Javadoc,
and `llms.txt` named it nowhere. The cycle wrote `docs/internals/vector-index.md` (the index *is* a
computation graph — normalize/similarities as compiled Producers — a clean non-training exemplar of
THE FUNDAMENTAL RULE, plus the model-keyed rebuild contract), drift-guarded by
`VectorIndexExamplesTest` (top-1 recall, save/reload identity, model-change exclusion-then-inclusion;
no download), with the `llms.txt`/README cross-links. Ran on any node.

### 2026-11-03 — Copy read-only aggregate slices once per argument map instead of before every dispatch (condensed)

**Category:** Performance · **Branch:** `project/plan-20261103-041617` ·
**Plan:** [`PLAN-20261103-read-only-aggregate-copy-once.md`](PLAN-20261103-read-only-aggregate-copy-once.md)

The rolling KV cache (PR #818) was correct but dispatch-bound: the argument-aggregation copy-in ran
before every dispatch, including for read-only arguments (weights, file-backed tensors) whose
contents cannot change and whose slice the kernel never writes. The cycle copied read-only slices
*once* per argument map (strongest invariant: a read-only source the scope does not write stays
correct after the first copy, read off the same `getWrittenSlices` evidence that governs copy-back),
made that first copy `unordered()` to drop the Metal bridged command buffer, localized to
`MemoryDataArgumentMap` + one `AcceleratedOperation.apply` site, with a structural regression test.
Merged (PR #825). Needed a Metal node for the bridged-commit measurement; correctness on any backend.

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

### 2026-11-02 — Document the neuron-level connectome simulation pipeline (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261102-014251` ·
**Plan:** [`PLAN-20261102-neuron-level-connectome-simulation-docs.md`](PLAN-20261102-neuron-level-connectome-simulation-docs.md)

The neuron-level connectome subsystem had merged (PRs #806, #809) — `NeuronConnectome` (CSR
synapse counts through `StateDictionary`, synaptic input as one sparse `matmul`), `ConnectomeNetwork`
(the Shiu et al. (2024) whole-brain model in `/pdsl/connectome.pdsl`, stepped by `SimulationRunner`),
the `domain/biology` neuron `Block`s and the `studio/bioscience` ingest/oracle scripts — reproducing a
published experiment's spikes, spike for spike, with only READMEs and Javadoc to explain it, and
`llms.txt` naming only the older `CellTypeConnectome`. The cycle wrote
`docs/internals/neuron-level-connectome-simulation.md`, drift-guarded by
`NeuronLevelConnectomeSimulationExamplesTest` over the committed synthetic connectome (PR #814,
merged). Ran on any node.

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

### 2026-10-28 — Measure the self-hosted LM's key/value-cache decode speed (condensed)

**Category:** Proof of Value (self-hosted LM generation) · **Branch:** `project/plan-20261028-004435` ·
**Plan:** [`PLAN-20261028-lm-decode-throughput.md`](PLAN-20261028-lm-decode-throughput.md)

The KV-cache decode had landed byte-identical to the full forward but its throughput — the claim
the whole cycle was justified by — was never measured. The cycle added
`CausalLanguageModelDecodeThroughputTest`, asserting only the robust structural property (fewer
kernels per decoded byte) and reporting wall-clock figures without a host-dependent threshold. The
measurement (PR #768) found the cache ~4× *slower* on Metal and exposed the cache-ownership defect,
which set up the 10-28 (later) and 10-29 cycles. Needed a Metal node.

### 2026-10-27 — Document simulating a spiking neural population, from state to recorded spikes (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261027-081706` ·
**Plan:** [`PLAN-20261027-neural-population-simulation-docs.md`](PLAN-20261027-neural-population-simulation-docs.md)

A whole simulation substrate had arrived through the connectome Phase-1 tracker tasks with only
Javadoc — `CounterRandom`/`AR_RANDOM`, `SimulationRunner`, `LeakyIntegrateAndFirePopulation`, the
PDSL move to `domain/graph`, and the new `domain/biology`/`studio/bioscience` modules, none in any
Markdown file — and `shape-and-traversal.md` still predated `TraversalOrdering.bind`. The reference
existed, the connective tissue did not. The cycle wrote `docs/internals/neural-population-simulation.md`
(one spiking population from device-resident state through the LIF step, ring-buffer delay,
connectivity, in-kernel Poisson drive and a `SimulationRunner` run with a device-resident spike
recording), drift-guarded by `NeuralPopulationSimulationExamplesTest`, and brought
`shape-and-traversal.md`, `llms.txt` and the graph/biology READMEs current. Ran on any node.

### 2026-10-26 (later) — Teach PDSL model authoring with a worked tutorial (condensed)

**Category:** Documentation · **Branch:** `project/plan-20261026-165814` ·
**Plan:** [`PLAN-20261026-pdsl-authoring-tutorial.md`](PLAN-20261026-pdsl-authoring-tutorial.md)

The reference corpus was excellent and current (40+ internals pages; `pdsl-language.md` the complete
normative PDSL reference), but the tutorial series (`01`–`09`) taught no PDSL — the declarative
language every trained model states its computation in. With the KV-cache decode landed and the next
proof-of-value steps Metal-gated or heavy, the long-deferred teaching gap was the first category with
reachable work. The cycle wrote `docs/tutorials/10-pdsl-model-definitions.html` with
`PdslTutorialExamplesTest` (every complete PDSL program parses; the final model builds via
`PdslLoader.buildModel` from a synthetic `StateDictionary`), staying inside what already ships and
depending on none of the unbuilt `PDSL_FOR_RESEARCH_AND_EDUCATION.md` features. Ran on any node.

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
**Output:** [`docs/internals/pdsl-language.md`](../internals/pdsl-language.md)

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
