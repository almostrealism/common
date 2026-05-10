# AudioScene Rendering Pipeline Redesign

> **Status (May 2026):** Plan revised to reflect two developments since the original
> was written: (1) the PDSL audio DSP substrate has landed on master — it is the path
> forward for the DSP half of the redesign, not one option among several; (2) profiling
> on `feature/rings-realtime-generation` found that **pattern generation** accounts for
> ~278ms of 311ms total per-tick cost, making it the dominant bottleneck — the DSP
> mixdown this plan originally focused on is a small fraction of total cost.
>
> The revised scope covers both: DSP cutover (substrate ready, mostly mechanical) and
> pattern generation (bottleneck identified, strategy evaluative until profiling clarifies).

---

## 1. The Corrected Scope

The original plan framed AudioScene redesign as a DSP/mixdown performance problem. It
was written before the PDSL audio DSP work landed and before recent profiling identified
where render time actually goes. Both of those have changed the picture materially.

**Reality after profiling (32-measure render, M1 Ultra, 4096 frames):**

| Component | Time | Fraction |
|-----------|------|---------|
| Pattern generation (PatternLayerManager.sumInternal + PatternFeatures.render) | ~278ms | ~89% |
| DSP kernel (Loop, monolithic) | ~33ms | ~11% |
| **Total per tick** | **311ms** | — |

The DSP mixdown — the subject of the original plan's entire analysis and Options A–D
architecture section — accounts for roughly 11% of total cost. Pattern generation
accounts for the other 89%. A redesign that only fixes DSP leaves the dominant cost
untouched.

The revised scope covers both halves:
- **DSP cutover:** Replace the Cell-based DSP path with the PDSL substrate. The substrate
  exists and is tested. The remaining work is mechanical: production cutover, EfxManager
  migration, one deferred primitive (`delay_network`), and variable channel count support.
- **Pattern generation:** Profile-and-fix. The 278ms bottleneck is identified but not yet
  decomposed into its contributors. The strategy is: profile first, fix what the data
  points to, then evaluate whether a structural change is needed.

---

## 2. What Has Already Landed

### 2A: The PDSL Audio DSP Substrate (merged to master)

The `feature/pdsl-audio-dsp` branch, now merged, implemented the full declarative
substrate for the DSP pipeline. This is the foundation the DSP cutover builds on.

**Audio primitives** — all implemented and tested:
`fir`, `scale`, `identity`, `lowpass`, `highpass`, `biquad`, `delay`, `lfo`

**Multi-channel constructs:**
- `channels: int` header parameter — declares N-channel layer
- `for each channel { }` — iterates a body over each channel independently
- `repeat(N)` — replicates a `[1, signal_size]` input to `[N, signal_size]`
- `route(matrix)` — cross-channel routing via `[in, out]` matrix (square or rectangular)
- `sum_channels()` — collapses `[N, signal_size]` to `[1, signal_size]`
- `accum_blocks(...)` — branches, applies each arm to the input, sums outputs

**Producer-valued arguments:**
Every audio primitive that the live system drives with a `Producer<PackedCollection>`
(volume, HP/LP cutoff, delay time, automation modulation) accepts `producer([shape])`.
Constant-folded literal calls compile identically to before — no regression for
fixed-parameter PDSL files. `bindProducerArg` is the single dispatch point.

**PDSL files:**
| File | What it covers |
|------|---------------|
| `pdsl/audio/efx_channel.pdsl` | EFX channel: `efx_wet_chain`, `efx_lowpass_wet`, `efx_highpass_wet`, `efx_dry_path`, `efx_delay`, `efx_wet_dry_mix` |
| `pdsl/audio/mixdown_channel.pdsl` | Mixdown layers: `mixdown_main` (HP→scale→LP), `mixdown_channel` (full with wet/delay) |
| `pdsl/audio/delay_feedback_bank.pdsl` | Multi-channel delay bank: `delay_feedback_bank` (repeat → per-channel delay → route → sum_channels) |
| `pdsl/audio/mixdown_manager.pdsl` | Top-level mixdown: `mixdown_main_bus`, `mixdown_efx_bus`, `mixdown_master` |

**Adapter and verification:**
- `MixdownManagerPdslAdapter` — bridges genome state to PDSL args (`buildArgsMap`) and
  exposes a compiled Block's Cell as a CellList (`wrapBlockAsCellList`).
- `MixdownManagerPdslVerificationTest` — verifies the PDSL path matches the Cell path
  with reverb disabled (reverb path depends on `delay_network`, which is deferred).

**Key design note:** Per-channel kernel splitting — the central goal of the original
plan's Phase 1 — happens automatically as a property of how PDSL compiles the
`for each channel` construct. No graph-analysis pass, no CellList restructuring.
The original plan's Options A–D and their associated difficulty/tradeoff analysis are
moot: PDSL is the answer to all of them.

### 2B: Structural Changes on the Branch

Since the original plan was written, the following have landed:

- **`AudioSceneLoader` extracted** (master, April 2026): Loading, saving, and JSON
  serialization moved out of `AudioScene`. `AudioScene` now focuses on the rendering
  pipeline only.
- **Alternate sample rates** (branch, April 2026): `BufferedAudioPlayer` honors the
  `sampleRate` parameter consistently.
- **Package reorganization**: Key classes are now in semantically appropriate packages:
  - `org.almostrealism.studio.*` — `AudioScene`, `AudioSceneLoader`, `Mixer`
  - `org.almostrealism.studio.arrange.*` — `MixdownManager`, `EfxManager`, `AutomationManager`
  - `org.almostrealism.music.pattern.*` — `PatternSystemManager`, `PatternAudioBuffer`

---

## 3. The DSP Path: What Remains

The PDSL substrate is in place. The remaining DSP work is:

### 3A: Production Cutover (MixdownManager)

The Cell-based `MixdownManager.createCells()` body is still the production path.
`MixdownManagerPdslAdapter.buildArgsMap()` and `wrapBlockAsCellList()` exist and are
tested. The cutover means replacing (or A/B-flagging) the `createCells()` body with
calls to the adapter.

The adapter applies a shared producer to all channels for genome-driven parameters
(volume, HP cutoff, LP cutoff, transmission) — this is a known simplification relative
to the per-channel-distinct envelopes in the Java path, documented in the adapter's
javadoc. `MixdownManagerPdslVerificationTest` captures the current scope of equivalence.

### 3B: `delay_network` Primitive

The multi-tap feedback reverb (`org.almostrealism.audio.filter.DelayNetwork`) has no
PDSL equivalent yet. This is explicitly deferred to this workstream
(`PDSL_AUDIO_DSP.md`, Section 12.5). `MixdownManagerPdslVerificationTest` runs both
paths with reverb disabled to keep the comparison meaningful until this lands.

Implementing `delay_network` unblocks the full reverb path in the PDSL rendition and is
a prerequisite for a complete MixdownManager cutover. It is sized as weeks of work.

### 3C: EfxManager Migration

`EfxManager` is fully Cell-based. Its per-channel processing (FIR filtering, adjustable
delay, automation-modulated wet/dry mix, self-feedback) overlaps substantially with what
the existing `efx_channel.pdsl` describes. The migration is expected to be mostly
mechanical once `delay_network` is available (EfxManager also uses DelayNetwork for its
reverb tail).

### 3D: Variable Channel Count

The `channels` parameter in PDSL is fixed at block-build time. Dynamic channel count —
channel activation via gene — remains Java CellList code. Supporting this in PDSL is
deferred; the current PDSL rendition targets a fixed channel count configuration.

### 3E: CellList Retirement

`CellList` and the Cell/CachedStateCell/Receptor model are being deprecated in favor of
Block-based execution. `wrapBlockAsCellList` is an intentionally narrow bridge that lets
the PDSL Block slot into AudioScene without changing AudioScene's interface. The CellList
interface can be retired once all callers migrate to Block.

---

## 4. The Pattern Generation Bottleneck

### 4A: Where the 278ms Goes (Current Understanding)

The profiling finding is: Java orchestration in `PatternLayerManager.sumInternal` and
`PatternFeatures.render` accounts for ~278ms of 311ms total per tick at 32 measures.

Reading the code makes the source visible:

**`PatternLayerManager.sumInternal`** iterates over pattern repetitions (`IntStream.range`)
and, for each repetition, over `NoteAudioChoice` entries. For each (repetition, choice)
pair, it creates a `NoteAudioContext` and calls `PatternFeatures.render()`.

**`PatternFeatures.render`** iterates over `PatternElement` instances via streams. For
each element it calls `getNoteDestinations()`, which produces `RenderedNoteAudio` objects.
For each note not in cache, it calls `note.getProducer(-1).evaluate()` — a JNI round-trip
that evaluates one note's audio against the GPU (or CPU) and returns a `PackedCollection`.
It then sums the result into the destination buffer via `AudioProcessingUtils.getSum()`.

The per-note evaluation pattern is the structural source of cost: each note is an
independent `evaluate()` call. If a 32-measure arrangement has many pattern elements
across many repetitions and many layers, this accumulates to many separate kernel
dispatches in sequence.

**Note:** We do not yet have a breakdown of the 278ms by contributor (allocation, JNI
dispatch, sum-to-destination, Java loop overhead). The profiling finding is at the method
level. Phase 2 exists to get that breakdown.

### 4B: Candidate Directions

These are candidate directions only. Which ones are worth pursuing depends on what Phase 2
profiling finds. The plan does not commit to any of them now.

**Reduce allocation churn.** `PatternElement.getNoteDestinations()` creates
`RenderedNoteAudio` objects on every render call. If these allocations are frequent and
short-lived, GC pressure could contribute materially. This is the cheapest hypothesis to
test: measure allocation rate and GC pause time during a render.

**Batch note evaluation.** Rather than calling `evaluate()` per note, compose a single
`CollectionProducer` that evaluates all notes for a given choice in one kernel dispatch,
then scatter-accumulate the results. This is a Producer-pattern fix: express "evaluate N
notes" as one computation graph rather than N sequential graphs.

**Cache more aggressively.** `NoteAudioCache` already caches by frame offset. If pattern
content is stable across ticks (no genome change), the cache avoids re-evaluation. The
question is what fraction of notes actually hit the cache in real-time rendering — if
cache hit rate is low, improving it could close a large gap with small code changes.

**Batch-render across pattern variants.** The vector-space exploration UX uses a compact
fixed-size pattern representation with similarity-driven sampling. This suggests a
possible reorganization: rather than "render this one pattern," render a neighborhood of
similar patterns in a batched forward pass. This aligns naturally with how ML inference
batches inputs. This is speculative and would require significant restructuring; it is
not a Phase 2 candidate but a framing for Phase 3 evaluation.

**Move pattern assembly to PDSL.** Patterns as tensor operations: `PatternElement`
positions become a tensor, note audio becomes a learned embedding, assembly becomes a
matrix operation. This is the long-term direction (Phase 4 / trajectory). It requires
PDSL to support dynamic-shape operations not currently on the roadmap. Not a near-term
option.

---

## 5. Phasing

### Phase 1: DSP Cutover (Mostly Mechanical)

The PDSL substrate is in place. Phase 1 delivers the production cutover.

**1A — `delay_network` primitive.** Implement the PDSL equivalent of
`org.almostrealism.audio.filter.DelayNetwork`. This is the prerequisite for completing
the MixdownManager and EfxManager migrations. Deferred from PDSL audio DSP; sized as
weeks.

**1B — MixdownManager production cutover.** Replace (or A/B-flag with a runtime switch)
`MixdownManager.createCells()` with calls through `MixdownManagerPdslAdapter`. Enable
the reverb path in `MixdownManagerPdslVerificationTest` once `delay_network` is
available. Verify acoustic match.

**1C — EfxManager migration.** Migrate EfxManager's per-channel Cell chain to the PDSL
`efx_channel.pdsl` definition. Expected to be mostly mechanical once `delay_network` is
available.

**1D — Variable channel count.** If runtime-variable channel count via gene is needed,
design the bridge. Current position: PDSL uses fixed channel count; variable channel
activation remains in Java. Evaluate whether this is a blocker for the cutover.

**Expected outcome of Phase 1:** The DSP path runs through PDSL-compiled Block kernels
instead of the monolithic CellList kernel. Per-channel splitting happens automatically.
The expected improvement to DSP time (~33ms today) is secondary to pattern generation;
Phase 1's primary value is architectural foundation.

Phase 1 is characterised as "mostly mechanical" because the substrate exists and is
tested. The hard engineering work is `delay_network`; the rest follows from it.

### Phase 2: Pattern Profiling and First-Pass Fixes (Evaluative)

Phase 2's gate question is: **where within the 278ms does the time actually go?**

The goal is not an architectural rewrite. It is targeted fixes, guided by profiling data,
that close as much of the gap as the data indicates is closeable without restructuring.

**2A — Profile sumInternal and render.** Use JFR or a similar profiler to get a
breakdown of the 278ms by call site. Candidates: `getNoteDestinations()` allocation,
`evaluate()` JNI dispatch, `sumToDestination()` copy cost, Java loop overhead. The
profiling must distinguish between "time inside evaluate()" and "time orchestrating
around evaluate()."

**2B — Implement targeted fixes.** Based on profiling findings, implement the fixes that
the data supports. No assumption about which ones matter until 2A is complete. Likely
candidates (see Section 4B) include cache hit-rate improvements, allocation reduction,
and potentially batch evaluation of multiple notes in one Producer composition.

**2C — Measure and decide.** After fixes: re-profile. Compare total per-tick time to
the 92.9ms real-time threshold. If pattern generation is now below a usable threshold,
Phase 3 becomes optional. If not, Phase 3 becomes necessary.

Phase 2 is characterised as "evaluative" because the right interventions are not known
until the profiling data exists.

### Phase 3: Pattern Restructuring (Conditional, Evaluative)

Phase 3 is entered only if Phase 2's targeted fixes are insufficient. It addresses the
question: **if per-note evaluate() calls are the structural bottleneck, what is the
restructuring that removes them?**

Candidate restructurings (to evaluate, not commit to):

- **Block-based pattern rendering.** Compose pattern-element audio as a single
  `CollectionProducer` graph per pattern layer, compile it once, and execute it once per
  tick. This eliminates per-note `evaluate()` calls in favor of one larger kernel. The
  challenge: note positions and lengths vary dynamically; expressing this as a static
  graph requires either padding to max-note-count or a different rendering model.

- **Batch generation across pattern variants.** If the vector-space exploration loop
  generates many similar patterns, restructure rendering to evaluate a batch of pattern
  variants in one forward pass. This aligns with how ML inference batches work. The
  challenge: requires the pattern representation to be tensorized, which is a larger
  structural change.

- **Hybrid: Java orchestration for scheduling, PDSL for per-note audio.** Keep the
  Java loop structure for repetition and note selection, but compile note audio
  evaluation into PDSL layers that execute as a batch per tick. This is a narrower
  change than full pattern → PDSL conversion.

Phase 3 is a decision point, not a commitment. The specific path is chosen after Phase 2
results are in hand.

### Phase 4: Convergence (Aspirational, No Timeline)

The long-term direction is AudioScene as a single PDSL computation graph — DSP,
pattern generation, and the whole rendering pipeline. Patterns become tensor operations
(element positions → index tensors, note audio → learned embeddings, assembly →
matrix-multiply-accumulate). The framework compiles the entire graph to a single native
kernel per tick.

This is stated as the trajectory, not as a near-term deliverable. The trajectory matters
for design decisions in earlier phases: choices in Phases 1–3 should preserve the option
to keep moving toward Phase 4. Specifically:

- The DSP cutover should use Block-based execution (not invent new CellList abstractions)
  because Block composes directly with PDSL.
- Pattern generation fixes should not entrench the per-note-evaluate pattern in new ways
  that make batch compilation harder to add later.
- New data structures introduced for pattern optimization should be expressible as
  tensor shapes, even if they aren't yet evaluated as tensors.

The endpoint is not "PDSL for DSP only." It is "PDSL for everything that benefits from
being a tensor operation" — which, over time, means the full pipeline.

---

## 6. Scope

### In Scope

- `delay_network` PDSL primitive (Phase 1 prerequisite)
- MixdownManager production cutover from Cell-based to PDSL-based (Phase 1)
- EfxManager migration to PDSL (Phase 1)
- Variable channel count support in the PDSL rendition (Phase 1, if needed for cutover)
- CellList retirement as callers migrate (ongoing with Phase 1)
- Pattern generation profiling: decompose the 278ms by contributor (Phase 2)
- Pattern generation first-pass targeted fixes based on profiling data (Phase 2)

### Out of Scope (Trajectory Only)

- Wholesale pattern → PDSL conversion (Phase 4, aspirational)
- AudioScene as a single PDSL graph (Phase 4, aspirational)
- Real-time streaming or low-latency targets below ratio-of-1: the realtime goal is
  ratio-of-1 (render time ≤ audio duration), not sub-millisecond latency.
- Timeline or release estimates

---

## 7. What the Original Plan Got Right and What Changed

**Still valid:**
- The monolithic kernel is a real performance problem for the DSP path.
- Per-channel splitting is the right architectural goal.
- `prepareBatch()` / Java-side overhead is real (53ms measured, reduced but not
  eliminated in subsequent work).
- The goal of reducing per-tick render time to below 92.9ms (ratio-of-1) is correct.

**Overtaken by events:**
- Options A–D (Channel-Scoped CellLists, Flat Buffer Pipeline, Hybrid+graph-analysis,
  Stream-Oriented) are all moot. PDSL is the answer to all of them. The `for each channel`
  construct delivers per-channel kernels; the Block model eliminates the Cell/CachedStateCell
  copy chain; the declarative structure makes graph analysis unnecessary.
- The graph-analysis pass (Option C) for copy-chain elimination is not needed: PDSL's
  expression tree handles this at the Producer level.
- The "Performance" framing as pure DSP/mixdown is incorrect: pattern generation dominates.
- The success criteria table targeting kernel time and prepareBatch time is incomplete
  without a corresponding row for pattern generation cost.

**Investigation questions from the original plan:** Q1 (channel independence), Q2
(kernel dispatch overhead), Q3 (memory consolidation), Q4 (CellList API compatibility),
Q5 (feedback loops), Q6 (prepareBatch redundancy) — these remain worth understanding but
their answers inform the PDSL cutover design rather than a Cell-graph analysis pass.

---

## 8. Success Criteria (Revised)

| Metric | Current | Target | Notes |
|--------|---------|--------|-------|
| Total per-tick time (32m) | 311ms | <93ms (ratio-of-1) | Both pattern + DSP must improve |
| Pattern generation time | ~278ms | Unknown after Phase 2 | Phase 2 sets the achievable target |
| DSP kernel time | ~33ms | <20ms (estimate) | PDSL cutover + per-channel splitting |
| prepareBatch time | 53ms | <25ms | Partially addressed; cache improvements |
| DSP kernel count | 1 monolithic | N per-channel | Automatic from PDSL |
| Acoustic match (PDSL vs Cell) | N/A | Verified by MixdownManagerPdslVerificationTest | With reverb path enabled post-delay_network |

---

## 9. Key Files

### DSP Path (Phase 1)

| File | Role | Expected Changes |
|------|------|-----------------|
| `compose/.../arrange/MixdownManager.java` | Cell-based DSP pipeline | `createCells()` replaced by PDSL path |
| `compose/.../arrange/MixdownManagerPdslAdapter.java` | Adapter: genome → PDSL args | Minor updates as cutover lands |
| `compose/.../arrange/EfxManager.java` | Cell-based EFX | Migration to PDSL `efx_channel.pdsl` |
| `compose/.../arrange/AutomationManager.java` | Automation producers | Minor — continues supplying `Producer` args to PDSL |
| `ml/.../resources/pdsl/audio/*.pdsl` | PDSL layer definitions | `delay_network` primitive added |
| `compose/.../dsl/audio/AudioDspPrimitives.java` | PDSL primitive registration | `delay_network` primitive registered |
| `compose/.../dsl/audio/MultiChannelDspFeatures.java` | Multi-channel block builders | `delay_network` block implementation |
| `compose/.../AudioScene.java` | Pipeline orchestrator | Switch to Block-based cells path |

### Pattern Generation Path (Phase 2)

| File | Role | Expected Changes |
|------|------|-----------------|
| `music/.../pattern/PatternLayerManager.java` | Pattern rendering coordinator | Targeted fixes based on profiling |
| `music/.../pattern/PatternFeatures.java` | Per-note evaluation + accumulation | Targeted fixes based on profiling |
| `music/.../pattern/PatternElement.java` | Pattern element: positions + note audio | Targeted fixes based on profiling |
| `music/.../notes/RenderedNoteAudio.java` | Note audio producer factory | Targeted fixes based on profiling |
| `music/.../notes/NoteAudioCache.java` | Per-tick note audio cache | Cache strategy changes based on profiling |
