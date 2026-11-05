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

### 2026-11-04 (later) — Skip the per-dispatch copy-in of a folded aggregate slice the kernel never reads

**Category:** Performance
**Branch:** `project/plan-20261104-182220`
**Plan:** [`PLAN-20261104-aggregate-copy-in-skip-unread.md`](PLAN-20261104-aggregate-copy-in-skip-unread.md)
**Tracker:** `cfffa36b-bb97-4350-b15d-bc7bfc2ce48a`

#### What changed since last cycle

The text-tokenization documentation merged (PR #838), closing the documentation gap at the input
layer of every language model that the earlier 2026-11-04 cycle opened. Around it, the **MaleCNS
simulation** work merged (PR #829) — not a plan cycle but a project branch that advanced the
connectome proof-of-value track (run the Shiu et al. model on any connectome, record each neuron's
side, compare with Brian2 from the reference manifest, and a `CacheRowAddressingTests` correctness
fix for cache rows past 2²⁴) and **updated its own internals docs as it landed**
(`neural-population-simulation.md`, `neuron-level-connectome-simulation.md`, the bioscience README).
The copy-once-read-only aggregate work (PR #825) and the vector-index docs (PR #834) are both in.

#### Category assessment (priority order)

- **Documentation — strong and current, checked not assumed.** The last two cycles were
  documentation (text tokenization, vector index), and the one fresh subsystem to land since
  (MaleCNS, PR #829) carried its own connective internals docs rather than Javadoc only. The
  `consult` corpus (built 2026-11-01) predates these merges, so it cannot adjudicate currency; I
  relied on the Manager Log's record that each fresh subsystem got connective prose as it merged,
  and on the log's own standing direction — *if the corpus is current, return to performance
  (`38f78778`) or proof of value rather than look for a fourth gap.* No fresh flagship prose gap.
  Moved on.
- **Code quality — owned by continuous QA.** The attention-gradient and cache-ownership arcs stay
  closed; the standing items (`io.almostrealism.collect` split; `TransformerResamplingFeatures`
  buffered K/V) are real but not reachable flagships. Moved on.
- **Performance — the telegraphed, freshly-evidenced, high-leverage lever, and it decided the
  cycle.** The rolling KV cache (PR #818) landed correct but *missed its throughput criterion*:
  on Metal it issues ~245 argument-aggregation copy dispatches per byte against the sliding
  window's ~123, dispatch-bound on the per-dispatch copy-ins. Copy-once-read-only (PR #825) removed
  the read-only weights from that count; what remains is one device copy per other folded slice,
  each a command-buffer blit on Metal. That per-dispatch copy *count* is the category's definition
  of a bottleneck: known, measured, on the critical path of two proof-of-value tracks at once.
- **Proof of value — the category these levers serve, gated or heavier.** MaleCNS just advanced but
  is itself capped by this copy path (every connectome tensor is folded, not replaced); long-form
  self-description at speed is capped the same way; conditional diffusion is heavy. The highest-
  leverage move toward proof of value is removing the gate, not adding a feature behind it.

#### Why this task

I verified against source that the copy path already makes two of its three decisions by reading
the **compiled kernel**, never a declared property: copy-*back* is limited to the slices
`getWrittenSlices(Scope)` finds written (via `Scope.containsAssignment(array, start, end)`), and
read-only slices are copied in once (`isCopiedOnce` + `OnceSubmittable.unordered()`). The missing
piece is the **dual**: a folded slice the kernel never *reads* needs no copy-in at all — its staged
input is dead. The read machinery already exists (`ExpressionReferences.containsReference`), and
`containsAssignment` is exactly its position-bounded *write* case; the task adds the position-bounded
*read* case, a `getReadSlices(Scope)`, and a `limitCopyInTo`/`setReadSlices` pair mirroring the
copy-back API, then skips the copy-in of any unread slice in `ensureCopyOperations`. It is the
copy-back arc applied symmetrically to the copy-in, on the same evidence source and with the same
rule — infer off the scope, never declare (`INVESTIGATION-20261031-declared-property-shortcut.md`).
Localized to `MemoryDataArgumentMap` + one read-query method + the already-wired `apply` prepare
site, with a backend-independent structural regression test that pins the partition (not a timing
threshold) so it cannot be gamed. The old tracker id `38f78778` is no longer resolvable, so this
cycle files a fresh, precisely-scoped task and the two remaining levers (one coalesced multi-region
Metal copy; aggregate-only-over-limit) stay as follow-ups.

#### Balance across categories

The last two cycles were documentation; the two before that were performance (copy-once) and proof
of value (rolling cache). Returning to performance now is the disciplined move the measurement
forces, not a detour to an easier category — the foundation is covered and current, the proof-of-
value feature is landed and correct, and its promised win is blocked precisely here. Small–medium
task needing a Metal node for the dispatches/byte and command-buffer commit-count measurement that
`CausalLanguageModelDecodeThroughputTest` profiles (workstream settings file present), as the
copy-once and rolling-cache cycles did; correctness is verifiable on any backend.

#### What comes next

1. This plan: the position-bounded read query, `getReadSlices(Scope)`,
   `limitCopyInTo`/`setReadSlices`, the `ensureCopyOperations` partition, the structural regression
   test, and the Metal measurement.
2. **Performance, same arc:** coalesce the slices that must still be copied every dispatch into a
   single multi-region copy on Metal (one blit, not N); aggregate only when the folded-argument
   count is over a threshold.
3. **Proof of value, self-hosted LM:** attention sinks (pin the first `k` tokens) if the rolling
   cache's long-generation quality degrades.
4. **Proof of value, connectome:** build on the merged MaleCNS work toward the fuller whole-brain
   reproduction, now that the Shiu et al. model runs on any connectome and the Brian2 comparison is
   manifest-driven.
5. **Proof of value:** conditional diffusion training.
6. Still open: the reduced `matmul`/convolution-gradient forms; `TransformerResamplingFeatures`'
   buffered K/V; the `io.almostrealism.collect` split; letting a trained model read the platform's
   own *source* — its retrieval substrate (vector index) and input layer (tokenization) are now
   documented.

### 2026-11-04 — Document text tokenization, from a string to the token ids a model consumes

**Category:** Documentation
**Branch:** `project/plan-20261104-133539`
**Plan:** [`PLAN-20261104-text-tokenization-docs.md`](PLAN-20261104-text-tokenization-docs.md)

#### What changed since last cycle

The vector-index documentation merged (PR #834) with its `VectorIndexExamplesTest` drift-guard and
the `llms.txt` entry, and the copy-once read-only aggregate work (PR #825) is in. Around them,
smaller correctness work landed on `master`: `CompiledModelAutoEncoder` clip-length validation,
structural expression hashing to speed compiles (`IndexChild` equality/hash), the Fourier feature
projection moving into `fourier_features.pdsl`, and — two days earlier — the tokenizer exporter's
added-token and reachable-byte-fallback work for Gemma vocabularies (PR #820).

#### Category assessment (priority order)

- **Documentation — a real gap at the input layer of every language model, and it decided the
  cycle.** I checked the subsystem PR #820 just changed rather than assuming the corpus covers it.
  It does not: no internals page covers tokenization; `llms.txt`'s only tokenizer line is the
  `extern/ml-djl` DJL wrapper — a *different* class from `engine/ml`'s `SentencePieceBPETokenizer`,
  so a reader following the index finds the wrong tokenizer; the `tokenization` package Javadoc
  omits `ByteTokenizer` (the self-hosted LM's vocabulary) and `SentencePieceBPETokenizer` (Gemma /
  Stable Audio 3 conditioning); and `engine/ml/README.md`'s tokenization examples ("5. Tokenization"
  and "Pattern 5: Manual Token Loop") **do not compile** — they call `encodeAsInt`/`decodeAsInt` on
  `Qwen3Tokenizer`, which does not implement `Tokenizer`, and the `protected` `getBOSToken()` /
  `getEOSToken()`. The `ARTK` exporter format and its validation
  rules live only in a docstring and a Javadoc block.
- **Code quality — strong and owned by continuous QA.** Recent commits are themselves QA output
  (clip-length validation, `KdTree.removeOld`, `CompleteIdentifierSet` concurrency). One small API
  inconsistency surfaced (`Qwen3Tokenizer` encodes and decodes through `int[]` methods of its own
  but does not implement `Tokenizer`); the plan documents it as-is and routes the decision to a tracker task.
  Several plans whose work merged still read `in-progress` — a header sweep for docs QA, not a cycle.
- **Performance — no PM-level blocker.** `38f78778` (per-dispatch copy count) and the reduced
  `matmul`/convolution-gradient forms remain real and non-blocking.
- **Proof of value — gated or heavier.** Attention sinks are conditional on long-generation quality
  degrading; the MaleCNS reproduction waits on owner data; conditional diffusion is heavy.

#### Why this task

It is the disciplined pick: the earliest category with genuine, reachable work, and it fixes
README examples that mislead anyone (or any model) who copies them. It is the reference-exists /
connective-tissue-missing move of the last three documentation cycles applied to the boundary every
language-model capability crosses first. It is reachable **on any backend**: the drift-guard
(`TextTokenizationExamplesTest`) runs on `SentencePieceTokenizerFixture`'s synthetic `ARTK` binary,
`ByteTokenizer` and `Qwen3Tokenizer.createTestTokenizer()`, with exact token-id assertions and no
download. For the self-understanding aim it documents how the platform turns text into the symbols
its own model learns from — and why that model deliberately uses a vocabulary-free byte tokenizer.

#### Balance across categories

Two of the last four cycles were documentation (vector index, connectome), with performance and
proof of value between. That is not drift: each documented a subsystem that had just landed with
Javadoc only, and the proof-of-value steps remain gated. If the next cycle finds the corpus current,
it should return to performance (`38f78778`) or proof of value rather than look for a fourth gap.
Medium task, no production behaviour change, any node — no workstream settings file.

#### What comes next

1. This plan: `docs/internals/text-tokenization.md`, `TextTokenizationExamplesTest`, the README
   correction, `package-info`, and the `llms.txt` entry.
2. **Performance:** `38f78778` — cut the per-dispatch device-copy count for slices still copied
   every dispatch, so the rolling KV cache beats the sliding window on dispatches/byte outright.
3. **Proof of value, self-hosted LM:** attention sinks if the rolling cache degrades on long runs.
4. **Proof of value, connectome:** the MaleCNS ingest and Shiu et al. reproduction, once the owner
   provides the tables and a Brian2 reference.
5. **Proof of value:** conditional diffusion training.
6. Still open: the reduced `matmul`/convolution-gradient forms; `TransformerResamplingFeatures`'
   buffered K/V; the `io.almostrealism.collect` split; letting a trained model read the platform's
   own *source* — its retrieval substrate (vector index) is documented, and its input layer
   (tokenization) will be once this plan lands.

### 2026-11-03 (later) — Document the vector-index subsystem (HNSW + disk store + model-keyed rebuild)

**Category:** Documentation
**Branch:** `project/plan-20261103-172701`
**Plan:** [`PLAN-20261103-vector-index-docs.md`](PLAN-20261103-vector-index-docs.md)

#### What changed since last cycle

The copy-once read-only aggregate work merged (PR #825), cashing the performance lever the
2026-11-03 cycle chose. Alongside it, a whole **vector similarity-search subsystem** reached
`master` (PR #827 and the commits around it: serialize HNSW index access + relax the
model-declaration API, rebuild the index from stored records on a model change, rebuild when the
model marker outlives the index, clarify the same-model rebuild contract in `WaveDetailsStore`).
It spans `engine/ml`'s `org.almostrealism.persist.index` (`HnswIndex`, `ProtobufDiskStore`,
`SimilarityMetric`, `SearchResult`, `DiskStore`) and `studio/compose`'s `ProtobufWaveDetailsStore`
(the model-keyed rebuild). The QA rounds that merged alongside (`qa/pdsl`, `qa/performance`,
`qa/consolidate`) keep the foundation current.

#### Category assessment (priority order)

- **Documentation — a real, fresh gap, and it decided the cycle.** The reference material exists
  (`engine/ml/README.md` has a thorough "HnswIndex — Vector Search" section; every class carries
  careful Javadoc), but `consult` confirmed this is the recurring *reference-exists /
  connective-tissue-missing* situation on the newest subsystem: `llms.txt` has **zero** mention
  of the vector index, so a reader (or a model trained on the corpus) following the index never
  discovers it; and two things live only in Javadoc and nowhere connective — (1) the index *is*
  a computation graph (`normalize` and `similarities` as Producers, compiled once and reused;
  normalize-into-row single dispatch; whole-store `matmul` scoring single dispatch; host-side
  top-K and graph construction), a clean **non-training** exemplar of THE FUNDAMENTAL RULE, and
  (2) the model-keyed rebuild contract that the recent commits were entirely about.
- **Code quality — strong and owned by continuous QA.** The copy-once aggregate work merged with
  its structural regression test and `OnceSubmittable` unit coverage; the attention-correctness
  and cache-ownership arcs stay closed. The standing items (`io.almostrealism.collect` split;
  `TransformerResamplingFeatures` buffered K/V, deferred to that autoencoder's first fine-tune)
  are real but not reachable flagships. Moved on.
- **Performance — no PM-level blocker this cycle.** The read-only copy-in is now once-per-map;
  the complementary `38f78778` (cut the per-dispatch copy *count* for slices that must still be
  copied every dispatch) remains real and non-blocking, as do the weighted-sum `matmul` reduced
  form and the convolution input-gradient reduced form. Moved on.
- **Proof of value — the category these tracks serve, gated or heavier than the doc gap.** The
  self-hosted LM and connectome tracks both benefit from the just-landed copy path; the next
  proof-of-value steps are either conditional (attention sinks, only if long-generation quality
  degrades), blocked on owner data (MaleCNS whole-brain reproduction), or heavy (conditional
  diffusion). None outranks a fresh, reachable documentation gap under the priority order.

#### Why this task

It is the disciplined pick: the earliest category with genuine, reachable, high-leverage work.
It is the 2026-10-27 / 2026-11-02 move again — the reference exists, the connective tissue and
the index entry do not — applied to the newest subsystem. It is reachable **on any backend**: the
drift-guard (`VectorIndexExamplesTest`, modeled on the existing `HnswSearchTest`) needs no curated
download and no macOS node, and asserts robust backend-independent properties (top-1 recall of a
planted neighbor, save/reload identity, model-change exclusion-then-inclusion) without reducing
dimensions, loosening tolerances or gating skips. For the self-understanding aim it documents the
platform's own **retrieval substrate** — the native-accelerated vector index beneath any
retrieval-augmented reasoning over the corpus (and eventually the source), expressed in the
computation-graph idiom rather than as host loops.

#### Balance across categories

The last cycle was performance (copy-once aggregate). Returning to documentation now is not drift
toward an easier category: a whole subsystem arrived with only README + Javadoc, the index does
not mention it, and the proof-of-value steps that would build on retrieval are either gated or
heavier. It is a medium task touching no production code and running on any node, so no workstream
settings file accompanies it.

#### What comes next

1. This plan: `docs/internals/vector-index.md`, the `VectorIndexExamplesTest` drift-guard, and the
   `llms.txt` / `engine/ml/README.md` cross-links.
2. **Performance, complementary:** `38f78778` — cut the *count* of per-dispatch device copies for
   slices that must still be copied every dispatch, so the rolling KV cache beats the sliding
   window on dispatches/byte outright.
3. **Proof of value, self-hosted LM:** attention sinks (pin the first `k` tokens) if the rolling
   cache's long-generation quality degrades.
4. **Proof of value, connectome:** the MaleCNS whole-brain ingest and the Shiu et al. reproduction,
   once the owner provides the ~2 GB tables and a Brian2 reference.
5. **Proof of value:** conditional diffusion training.
6. **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs > 1000) and a
   reduced form for the convolution input gradient.
7. Still open: `TransformerResamplingFeatures`' buffered K/V; the `io.almostrealism.collect` package
   split; the unbuilt PDSL research/education features; letting a trained model read the platform's
   own *source*, the deepest self-understanding aim — a natural consumer of the vector index this
   cycle documents.

### 2026-11-03 — Copy read-only aggregate slices once per argument map instead of before every dispatch

**Category:** Performance
**Branch:** `project/plan-20261103-041617`
**Plan:** [`PLAN-20261103-read-only-aggregate-copy-once.md`](PLAN-20261103-read-only-aggregate-copy-once.md)

#### What changed since last cycle

The rolling (sliding) key/value cache merged (PR #818): the self-hosted LM now decodes past
`seqLen` with sliding-window semantics — token-for-token identical to the sliding window for a
single-block model, and for a deeper model per-layer window (Mistral-style) attention matched on
the eviction property, as that cycle's own corrections record. But the cycle's
own measurement recorded that its throughput success criterion was **not met** — on Metal both
paths run ~200–260 B/s, and the rolling decode issues ~245 dispatches/byte against the sliding
window's ~123. The cache is correct but dispatch-bound, and the implementation memory named the
binding lever explicitly: argument-aggregation copy dispatches, tracker task `38f78778`. Alongside
it, the tokenizer byte-fallback / added-token-reachability work merged (PR #820) and a docs
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
