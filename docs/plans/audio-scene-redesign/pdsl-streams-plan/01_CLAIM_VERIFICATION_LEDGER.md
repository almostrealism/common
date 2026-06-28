# 01 — Claim-Verification Ledger

> Per the Prime Directive ([README](README.md)), no claim from the prior planning docs or from
> recalled memory may be used as a premise unless it is `verified-true` with a re-runnable
> receipt produced this session. This ledger is that gate. It enumerates every load-bearing
> empirical or causal claim found across the sibling `../*.md` docs and assigns each a status.
>
> **Method:** each claim was checked against current source (read-only; tests were *not* run, so
> any claim whose truth depends on a runtime number is `unverified-needs-measurement`, with the
> exact test that would settle it). Code-structural claims are `verified-true`/`verified-false`
> with a `file:symbol` receipt. This ledger was produced by a sub-agent sweep and then the
> load-bearing entries were spot-checked firsthand (see [02](02_GROUND_TRUTH_ARCHITECTURE.md));
> it remains living — update an entry in the same change that changes the underlying code.

## The headline result

| Status | Count | Meaning for the plan |
|---|---|---|
| `verified-true` | 33 | Code-structural facts. **May** be used as premises. |
| `unverified-needs-measurement` | 25 | **The entire performance narrative.** May NOT be used as a premise until measured. |
| `unverified-needs-code-read` | 3 | Need a deeper read (some on other branches). |
| `verified-false` | 1 | Stale doc claim; do not repeat. |

**The single most important finding:** *every* load-bearing performance and causal claim — the
ones that justified the prior strategy and the "5× needs a kernel redesign" conclusion — is
`unverified-needs-measurement`. They rest on `AudioScenePdslBenchmarkTest` output under
`results/` that is **git-ignored and not in the repo**, so they cannot be reproduced from the
source tree. Concretely, **none** of these may anchor the plan until re-measured through the
pinned harness (07 / Phase 1): "2.34× / 3.2×", "a2 optimized ~12×", "5× requires an a2 kernel
redesign", "the dominant cost was Metal pipeline-state switching", "eval ~24 ms / perNote ~31 ms
/ marshal ~18 ms", "delay_network 1382 ms → 3 ms", "PDSL tick 66–139 ms vs CellList 298–373 ms",
"native ~14× over budget". This is the concrete, itemized basis for treating the prior docs as
untrusted.

**What IS safe to build on** (verified-true, code receipts): the a3 tick is mixdown-only; the
ring/thread decoupling exists; continuing notes route to the per-note (cached) path, not the
batched kernel; vectorized `for each channel` is on by default; and the per-note **placement**
fan-out described in [02](02_GROUND_TRUTH_ARCHITECTURE.md). Note the asymmetry: the *mechanisms*
the prior work added are real and verified; the *performance numbers and causal stories* it
attached to them are not.

---

## The ledger

### VERIFIED-FALSE (do not repeat) — 1 claims

- **The batched renderer selects a kernel per overlap count from buckets {16,32,64,128,256,512}.**
  - source: A2_BATCHED_DISPATCH.md §3.3
  - receipt: BatchedPatternLayerRenderer.BUCKETS = {64, 128, 256, 512} (:80); bucketFor (:213) selects only from those four. The doc's set {16,32,64,128,256,512} is stale/incorrect (and the class javadoc itself documents {64,128,256,512}).

### UNVERIFIED — needs measurement (MAY NOT be a premise; the test that resolves it is noted) — 25 claims

- **The decoupling is verified by a sustained 2-minute all-channel non-silent real-time render (AudioSceneRealTimeCorrectnessTest.realTimeTwoMinuteRender, realTime=YES, efx off).**
  - source: STATE_OF_PLAY.md §2/§4
  - resolve by: Test exists (AudioSceneRealTimeCorrectnessTest.java:160, @TestDepth(1)) and calls helper.disableEffects() (:161) — confirms 'efx off' and that it renders 120s and asserts non-silent. Whether it PASSES (sustains 2 min, non-silent, all channels) requires running it (read-only forbids); 'all-channel' is not pinned in the test, which uses createSceneWithWorkingSeed(DEFAULT_SOURCE_COUNT).
- **a2 was optimized ~12× via three wins: one shared render kernel (coarse BUCKETS/SOURCE_BUCKET), a future-side gather window-filter, and a melodic-only gather cache.**
  - source: STATE_OF_PLAY.md §2/§4 (a2 render speed row)
  - resolve by: All three MECHANISMS are present in code (BatchedPatternLayerRenderer: BUCKETS={64,128,256,512} :80, SOURCE_BUCKET=16384 :87, rendererCache keyed by shape :106; future-side percussion filter :309-311; gatherCache memoization :133-302). The ~12× magnitude is a runtime figure from AudioScenePdslBenchmarkTest whose results/ is git-ignored; needs running to confirm.
- **The densest curated scene runs at 2.34× real-time; sparser scenes ~3.2×.**
  - source: STATE_OF_PLAY.md §2/§4/§5; A2_BATCHED_DISPATCH.md §1; PERF_OPTIMIZATION.md
  - resolve by: Per the docs these are M1 figures from AudioScenePdslBenchmarkTest writing to results/pdsl-cutover/ (git-ignored, not in repo). Instrumentation counters exist (BatchedPatternLayerRenderer.evalNanos/perNoteNanos/marshalNanos :146-155) but values require running the benchmark.
- **Reaching ~5× requires an a2 kernel redesign; the structural blockers are the batched kernel sharing one sourceLength/row length across all rows and host-side input marshalling — incremental tuning is exhausted.**
  - source: STATE_OF_PLAY.md §5; A2_BATCHED_DISPATCH.md §5; PDSL_STREAMS.md
  - resolve by: The structural facts are code-verified: BatchedPatternLayerRenderer.sourceLengthFor computes ONE shared sourceLength per dispatch (:631-643) and rendererCache is keyed by a single sourceLength (:106,230); host marshalling via copyRow/writeColumn (:470-508). But the causal conclusion that 5× *requires* a redesign and that tuning is 'exhausted' (net-loss-when-batching-continuing-notes measurement) needs profiling — not derivable from source.
- **The dominant real-scene a2 cost was Metal pipeline-state switching across many distinct compiled kernels (not the FIR/arithmetic/frame count); collapsing onto one shared kernel cut batched eval ~761 ms → ~28 ms.**
  - source: STATE_OF_PLAY.md §2/§4; A2_BATCHED_DISPATCH.md §1 Scope/§4.2
  - resolve by: The shared-kernel mechanism is in code (BUCKETS/SOURCE_BUCKET, rendererCache reuse). The causal attribution to 'pipeline-state switching' and the 761→28 ms figure require GPU profiling (ar-profile-analyzer / OperationProfileNode capture); no committed artifact establishes them.
- **The actual GPU render (eval) is already ~24 ms (under the ~37 ms budget); the gap is per-buffer overhead — perNote ~31 ms (continuing-note full renders) and marshal ~18 ms (host input write).**
  - source: STATE_OF_PLAY.md §5
  - resolve by: The three cost buckets are real instrumentation counters (BatchedPatternLayerRenderer.evalNanos :149, perNoteNanos :155, marshalNanos :146), but the ~24/31/18 ms values and the 37 ms budget comparison require running AudioScenePdslBenchmarkTest; no committed evidence.
- **The delay_network ring update was de-fragmented into single-expression computations, taking it from 1382 ms → 3 ms.**
  - source: STATE_OF_PLAY.md §2; PDSL_DIFFERENCES.md §7; PERF_OPTIMIZATION.md lever 2
  - resolve by: Pure timing claim with no committed artifact; the delay_network primitive exists but the 1382→3 ms requires running/profiling the de-fragmented vs original form.
- **The PDSL default (vectorized) tick is 66–139 ms at 8192 (1.34–2.81×), 44 ms at 4096 (2.12×), faster than the CellList tick (298–373 ms, 0.50–0.62×); non-vectorized 161–228 ms.**
  - source: STATE_OF_PLAY.md §2 (a3 row); PDSL_DIFFERENCES.md §7
  - resolve by: M1 figures from AudioScenePdslBenchmarkTest; the doc states results/pdsl-cutover/benchmark.txt is git-ignored and not in the repo. Needs running.
- **mixdown_master_wet forward dropped 77 ms → 21 ms after channel-uniform bodies were vectorized.**
  - source: PDSL_DIFFERENCES.md §7
  - resolve by: Timing claim, no committed artifact; requires running.
- **Real-time requires hybrid routing: AR_HARDWARE_DRIVER=mtl fails to compile the mixdown loop (Metal 31-buffer-argument limit), and AR_HARDWARE_DRIVER=native is ~14× over budget (hybrid ~3–4× faster than native).**
  - source: STATE_OF_PLAY.md §2 hard constraint 1; KNOWN_ISSUES.md §1
  - resolve by: The Metal 31-buffer-argument limit is a documented platform constraint, but 'mtl fails to compile the mixdown loop', 'native ~14× over budget', and 'hybrid ~3–4× faster' are behavioral/timing claims requiring runs under each AR_HARDWARE_DRIVER setting; no committed measurement.
- **Process.optimized() ADDED ~15% overhead on the flat per-note / pipeline chain — do not use it there.**
  - source: STATE_OF_PLAY.md §7; A2_BATCHED_DISPATCH.md §1
  - resolve by: Timing claim about a benchmark variant; no committed artifact. Requires running PatternRenderingFloorBenchmark with/without Process.optimized().
- **Expression-level micro-optimization of GPU kernels produced zero measurable kernel-time change (the native compiler already does LICM/CSE/strength-reduction).**
  - source: STATE_OF_PLAY.md §7
  - resolve by: Empirical 'zero measurable change' claim; requires before/after kernel timing — not establishable from source.
- **The remaining per-tick cost is per-note pattern prep (a2), not the DSP; the system is a2-bound.**
  - source: STATE_OF_PLAY.md §2/§4; PDSL_DIFFERENCES.md §7
  - resolve by: Requires the per-stage tick breakdown (AudioScenePdslBenchmarkTest.pdslTickStageTiming); counters exist but the apportionment (DSP ~34 ms vs a2 dominating) needs running.
- **The per-note rendering floor is ~99.4% JNI dispatch overhead — amortized per-note cost falls from 0.5374 ms (sequential) to 0.0031 ms (batched).**
  - source: A2_BATCHED_DISPATCH.md §1
  - resolve by: PatternRenderingFloorBenchmark exists and isolates resample->volume->lowpass_fir->accumulate against the 92.9 ms threshold (engine/audio/.../PatternRenderingFloorBenchmark.java:36,90-92), but the 99.4%/0.5374/0.0031 numbers are runtime outputs requiring a run.
- **Sequential 4-kernel per-note cost is constant ~0.88 ms/note regardless of N (468.78 ms @512 → 7,095.85 ms @8,192), the signature of pure per-note JNI dispatch.**
  - source: A2_BATCHED_DISPATCH.md §1
  - resolve by: Benchmark output numbers; PatternRenderingFloorBenchmark exists but figures are not committed (git-ignored results).
- **Batched render speedups: 16 notes/m → 123×, 64 → 172× (6.39 ms), 256 → 201×; kernel fusion across the 4 phases bought 1.73× (~43% of even the fused chain is dispatch); a 2-kernel form clears the floor at 0.90 ms (~921×); padded-FIR 4-kernel hits 1.89 ms.**
  - source: A2_BATCHED_DISPATCH.md §1
  - resolve by: All are PatternRenderingFloorBenchmark timing outputs; not committed; require a run.
- **On the real curated scene both melodic and percussion channels batch — percussion classifies via isPercussionSssShape and dispatches through dispatchWindowPercussion → buildBatchedPercussionChainPlaced; only continuing/non-batchable notes fall to per-note; the mix is non-silent with fallbackCount=0 on the melodic channel.**
  - source: A2_BATCHED_DISPATCH.md §4.2; KNOWN_ISSUES.md §6; PERF_OPTIMIZATION.md lever 1
  - resolve by: The percussion PATH is code-verified: BatchedPatternLayerRenderer.dispatchWindowPercussion (:536) and dispatchWindow routing on isPercussion() (:412-415); BatchedPatternRenderer.buildBatchedPercussionChainPlaced (:310). But fallbackCount=0 / non-silent / peak are RUNTIME results, curated-library-gated and flaky (random realGenome, peak can be 0.0); require running BatchedRealSceneRenderTest with /Users/Shared/Music samples.
- **allChannelsFullPipeline times out (>18 min cold) on the legacy CellList mixdown, so all-channel methods are run on the faster PDSL mixdown.**
  - source: A2_BATCHED_DISPATCH.md §4.2 caveat
  - resolve by: Timing/behavioral claim; needs a run on the curated library. The PDSL-vs-CellList routing is in code (supportsPdsl) but the >18 min cold timeout is not source-derivable.
- **Per-note pattern preparation is ~64 ms/tick on M1 (one busy channel ≈ half) and is the dominant remaining per-tick cost once the DSP is fast.**
  - source: A2_BATCHED_DISPATCH.md §4.3
  - resolve by: Explicitly flagged in the doc as an M1 runtime measurement from AudioScenePdslBenchmarkTest (BENCH_MEASURES=64), not CI-gated or source-verifiable.
- **The PDSL and CellList paths were A/B validated as identical by ear and by windowed RMS (per-window ratios 0.88–1.03, overall 0.94–0.99).**
  - source: PDSL_DIFFERENCES.md header/§9
  - resolve by: Perceptual + RMS A/B result from AudioScenePdslCutoverTest on the curated library; no committed numeric artifact (results/ git-ignored). Requires running the A/B.
- **Kernel recompilation (different machine/driver/order) can reorder FP reductions and shift feedback-coupled RMS levels ~1–3% binary-to-binary.**
  - source: PDSL_DIFFERENCES.md §4.2
  - resolve by: Cross-binary numerical-drift claim; requires multi-binary renders to observe; not source-derivable.
- **A dense percussion channel cost ~3.8 s (94% of the tick) of Java-side per-note graph building (compiled_operations=0, ~67k nodes) — the wall, now resolved because percussion batches.**
  - source: PERF_OPTIMIZATION.md §where the time goes / status
  - resolve by: The resolution MECHANISM (percussion batching) is code-verified (dispatchWindowPercussion, buildBatchedPercussionChainPlaced). The 3.8 s / 94% / 67k-node / compiled_operations=0 profile is a captured runtime measurement (AudioScenePdslBenchmarkTest.pdslTickProfile) with no committed artifact; needs running.
- **The curated pattern-factory.json is 11/14 percussion vs 3 melodic, and each channel is exclusively one type, so a dense percussion channel was 100% per-note before the fix.**
  - source: PERF_OPTIMIZATION.md lever 1 / diagnosis
  - resolve by: pattern-factory.json lives at /Users/Shared/Music/pattern-factory.json (external, not in repo, AR_RINGS_PATTERNS); cannot be read here. The 11/14 split and per-channel homogeneity require inspecting that external asset.
- **The a3 PDSL mixdown forward (mixdown_master_wet) is ~34 ms @ 8192 — already ≈5× realtime and not the bottleneck; gather is ~156 ms/tick; automation/streaming/clock < 5 ms.**
  - source: PERF_OPTIMIZATION.md §where the time goes
  - resolve by: Per-stage tick timing from AudioScenePdslBenchmarkTest.pdslTickStageTiming; no committed numbers; requires running.
- **A ~14-hour effort to reach 5× by tweaking the existing batched-render structure stalled at 2.34×, blocked by the kernel's shared per-dispatch source-length and host-side input marshalling.**
  - source: PDSL_STREAMS.md intro/§why
  - resolve by: The two structural costs are code-verified: BatchedPatternLayerRenderer.sourceLengthFor returns ONE source length for the whole dispatch (:631-643); inputs are marshalled host-side via copyRow/writeColumn into bound buffers (:470-508, marshalNanos:146). The '14-hour effort / stalled at 2.34× / net-loss-when-batching-continuing-notes' outcome is a measured/historical claim requiring runs.

### UNVERIFIED — needs deeper code-read — 3 claims

- **The prior attempt (feature/audio-scene-redesign) rendered ZERO notes through the batched path (batchedDispatchCount=0, fallbackCount=476) because it called innermostAudio() which throws on a 3-layer merge.**
  - source: A2_BATCHED_DISPATCH.md §3.5
  - resolve by: Historical claim about a different branch's state; not verifiable on the current branch without checking out feature/audio-scene-redesign (read-only on current tree). The innermostAudio()-throws-on-3-layer-merge behavior could be code-read but on that branch's sources.
- **The PDSL forward writes each stateless stage as its own kernel because LayerFeatures.layer hardcodes layer.init(inputShape, Layer.ioTracking, true), and DefaultCellularLayer already has an unused no-tracking forward path that inference could use.**
  - source: PERF_OPTIMIZATION.md lever 2
  - resolve by: The hardcoded `layer.init(inputShape, Layer.ioTracking, true)` is verified (domain/graph/.../LayerFeatures.java:415), and DefaultCellularLayer has the tracking 'exit' cell + output buffer (:84-106). But 'getForward already has a no-tracking path that is currently unused' and the ~25-30/~38 stages / ~30 launches / 6 MB intermediate-traffic counts need a deeper read of DefaultCellularLayer.getForward and a profile capture.
- **The CellList DSP architectures (channel-scoped/flat-buffer/hybrid-graph/stream) are moot because PDSL subsumes them (for-each-channel kernels, Block model eliminates the Cell copy chain, declarative tree removes the graph-analysis pass).**
  - source: STATE_OF_PLAY.md §6
  - resolve by: This is a design-judgment/architecture claim. The PDSL constructs exist (for each channel vectorization in PdslInterpreter; Block-forward runner; accum_blocks), but 'subsumes all four alternatives' is an asserted conclusion that would require comparing against the (unbuilt) alternatives — not establishable from current source.

### VERIFIED-TRUE (code receipt; may be used as a premise) — 33 claims

- **The a3 hot-path tick is mixdown-only; the original spec violation (a2 re-rendering inside the mixdown tick) is fixed.**
  - source: STATE_OF_PLAY.md §2/§4; KNOWN_ISSUES.md §5
  - receipt: AudioSceneRealtimeRunner.createPdsl tick() (studio/compose/.../AudioSceneRealtimeRunner.java:405-440) contains only frame-index reset, automationRefresh, renderStream.awaitSlot()->compiled.forward(slot)->release(), the output-stream loop and clock tick — NO prepareBatch. Pattern prepareBatch() runs in renderOps executed by the PatternRenderStream producer thread (:354-360).
- **The a1/a2/a3 ring decoupling is implemented: PatternRenderStream runs a2 ahead on its own producer thread, filling a bounded ring; a3 consumes already-rendered slots and never triggers a render.**
  - source: STATE_OF_PLAY.md §2/§4; PDSL_STREAMS.md
  - receipt: PatternRenderStream.java: dedicated daemon thread 'a2-pattern-render-ahead' (:139), two-semaphore bounded ring (empty/filled, :124-125), produceLoop renders into ring (:158-182), awaitSlot/release consumer contract (:191-211). Wired in AudioSceneRealtimeRunner.createPdsl (:359-360, renderAheadSlots=8 at :94).
- **Continuing notes cannot be batched/cached into the shared kernel — batching them bloats the shared per-dispatch source length (a measured net loss, reverted) — so they fall to the per-note path.**
  - source: STATE_OF_PLAY.md §5; PDSL_STREAMS.md; A2_BATCHED_DISPATCH.md §3.3
  - receipt: Routing is code-verified: BatchedPatternLayerRenderer.render only batches notes with getBatchedInputs()!=null AND noteStart>=startFrame (:337); continuing notes (noteStart<startFrame) go to the perNote list -> renderNotes (:339-351). The comment (:332-336) states batching them bloats the shared source length. NOTE: the 'measured net loss' itself is a measurement claim (unverified).
- **Vectorized 'for each channel' (AR_PDSL_VECTOR_FOREACH) is on by default.**
  - source: STATE_OF_PLAY.md §4; PDSL_DIFFERENCES.md §7; PDSL_DSP_REFERENCE.md
  - receipt: PdslInterpreter.enableVectorizedForEach = SystemUtils.isEnabled("AR_PDSL_VECTOR_FOREACH").orElse(true) (engine/ml/.../PdslInterpreter.java:124-125); used at :943.
- **Continuous rendering requires -DAR_PATTERN_CACHE_PERSIST=true (a static final read at class load); without it native memory leaks ~150 MB/buffer and the per-tick ratio explodes from ~1.1× to 70×+ before OOM.**
  - source: STATE_OF_PLAY.md §2 hard constraint 2; KNOWN_ISSUES.md §2
  - receipt: The static-final/class-load mechanism is verified: PatternLayerManager.java:199-200 `private static final boolean cachePersist = Boolean.getBoolean("AR_PATTERN_CACHE_PERSIST")`. (The ~150 MB/buffer leak and 1.1×→70× ratio explosion are separate measurement claims requiring a sustained run — those sub-claims are unverified.)
- **The Metal sustained-dispatch ceiling (host wedged past ~2300–2560 cumulative dispatches) is SOLVED — 3000+ sustained dispatches verified, regression-guarded.**
  - source: STATE_OF_PLAY.md §2; KNOWN_ISSUES.md §8.3
  - receipt: Regression guards exist: BatchedRealtimeTickTest.sustainedBatchedDispatch asserts batched>2560 (:310) over SUSTAINED_TICKS=3000 (:64); OperationDispatchBatchingTests.java exists (engine/utils). The fix is in MetalCommandRunner/MTL.cpp per the doc. (Whether the 3000-dispatch run actually passes on this machine requires running — the guard's EXISTENCE is what is verified here.)
- **The argument-aggregation / compile-reuse pool-exhaustion blocker is resolved — the null-signature-defeats-reuse premise is gone; structurally-identical rebuilds reuse cached kernels.**
  - source: STATE_OF_PLAY.md §2; KNOWN_ISSUES.md §8.1
  - receipt: CollectionProviderProducer.signature() now emits a real signature with `&aggRoot=<rootLen>` (compute/algebra/.../CollectionProviderProducer.java:309-329), and MemoryDataArgumentMap.isAggregationTarget is a pure size test (`enableArgumentAggregation && memLength <= maxAggregateLength`, default 1024) (base/hardware/.../MemoryDataArgumentMap.java:80,371-373).
- **Instruction-set cross-context reuse was fixed by making MetalDataContext share a single MetalComputeContext, which is what made AR_PDSL_VECTOR_FOREACH safe to enable by default.**
  - source: KNOWN_ISSUES.md §8.2
  - receipt: MetalDataContext.java declares 'The single MetalComputeContext shared by every thread of this data context' (sharedContext field :88-102). The causal link to enabling vectorized-foreach is documented in the same class javadoc.
- **MultiOrderFilter's boundary check treats length() as the whole array, so on [N, NOTE_SIZE] input the ±filterOrder/2 reads bleed across rows; per-row padding by filterOrder/2 zeros fixes it.**
  - source: A2_BATCHED_DISPATCH.md §1/§3.4
  - receipt: MultiOrderFilter.java boundary case uses `index.lessThan(input.length())` over the whole input (:341) with the ±filterOrder/2 offset (:323,331). The mechanism is exactly as described. (The resulting 1.89 ms padded-FIR figure is a separate measurement.)
- **Production builds a closed note vocabulary; the only constructed merge is plain SSS (SummingSourceAggregator) because aggregationChoice defaults to 0.0; the other 3 strategies (SSV 3.0, SSF 1.0, SFV 2.0) are gated behind enableAdvancedAggregation.**
  - source: A2_BATCHED_DISPATCH.md §2.1/§2.2
  - receipt: NoteAudioSourceAggregator constructor adds SSS (weight 6.0) unconditionally; SSV/SSF/SFV are added only `if (enableAdvancedAggregation)` (engine/audio/.../NoteAudioSourceAggregator.java). BatchedNoteInputs.isMelodicSssShape requires layers.size()==LAYERS(3) && aggregationChoice==0.0 (BatchedNoteInputs.java:135-188; LAYERS=3 :61).
- **Envelope order: per-layer envelopes apply before the merge; the heavy filter+volume envelopes apply after (filter inner, volume outer), present iff melodic.**
  - source: A2_BATCHED_DISPATCH.md §2.2
  - receipt: PatternElementFactory.java (~:263-271): `main = filterEnvelope.apply(...,MAIN,main)` then `main = volumeEnvelope.apply(...,MAIN,main)`, both gated `if (... && melodic)` for the MAIN voicing (filter inner, volume outer).
- **PatternFeatures.render dispatches to the batched renderer when enableBatched and a BatchedPatternLayerRenderer is available, else renderPerNote; accumulateBatchedOutput is the single evaluate boundary (output.get().evaluate()).**
  - source: A2_BATCHED_DISPATCH.md §3.2
  - receipt: PatternFeatures.java render() :98-108 (flag check + getBatchedLayerRenderer + fallback); accumulateBatchedOutput :156-159 (`output.get().evaluate()`).
- **A note is batchable iff it carries a BatchedNoteInputs record (getBatchedInputs() != null); MAX_WINDOW=8192 bounds sub-windows; melodic-SSS classification is layers==3 && aggregationChoice==0.0.**
  - source: A2_BATCHED_DISPATCH.md §3.3
  - receipt: BatchedPatternLayerRenderer batchable test `note.getBatchedInputs() != null && noteStart >= startFrame` (:337); MAX_WINDOW=8192 (:97), dispatchBatched splits by it (:373); BatchedNoteInputs.isMelodicSssShape (:135-188).
- **Sentinel discipline: the gate is batchedDispatchCount>0 && fallbackCount==0; every deferred shape routes to renderPerNote with a loud fallbackCount++, never silently.**
  - source: A2_BATCHED_DISPATCH.md §3.5
  - receipt: Counters BatchedPatternLayerRenderer.batchedDispatchCount/fallbackCount (:140,143), resetCounters (:170); render() increments batchedDispatchCount on batch (:346) and fallbackCount before renderNotes on the perNote remainder (:349-351).
- **Three studio sentinel tests are active (not @Ignore'd) and assert: dispatch fires (>0); non-silent (refRms/maxAbs>1e-4); batched matches per-note within 5% relative RMS; 3000 sustained dispatches with fallback==0, batched>2560, and avgMs<budget.**
  - source: A2_BATCHED_DISPATCH.md §4.1
  - receipt: BatchedDispatchSentinelTest asserts batchedDispatchCount>0 (:122-126); BatchedVsPerNoteRmsTest asserts refRms>1e-4 (:169-170) and relative<0.05 (:171-172); BatchedRealtimeTickTest SUSTAINED_TICKS=3000 (:64), maxAbs>1e-4 (:308), fallback==0 (:309), batched>2560 (:310-311), avgMs<budgetMs (:312-313). None carry @Ignore. (Pass/fail on this hardware needs running.)
- **The often-quoted <1% RMS, ~12× under budget, and 7.76 ms/tick are reported M1 measurements, NOT enforced assertion thresholds (the enforced bounds are 5% RMS, merely under budget, and >2560 dispatches).**
  - source: A2_BATCHED_DISPATCH.md §4.1
  - receipt: Confirmed by reading the tests: enforced bounds are relative<0.05 (BatchedVsPerNoteRmsTest:172, BatchedRealtimeTickTest:198), avgMs<budgetMs (:312), batched>2560 (:310). The tighter numbers appear only in log() lines, not assertions.
- **BatchedRealSceneRenderTest is re-enabled (the @Ignore is removed).**
  - source: A2_BATCHED_DISPATCH.md §4.2; KNOWN_ISSUES.md §6
  - receipt: studio/compose/.../BatchedRealSceneRenderTest.java has no @Ignore annotation; methods singleMelodicChannelFullPipeline (:153), allChannelsFullPipeline (:308), warm-steady-state present and active.
- **Pattern preparation is identical across the two paths (the A/B isolates DSP only).**
  - source: PDSL_DIFFERENCES.md §1
  - receipt: Both AudioSceneRealtimeRunner.createCellList (uses PatternAudioBuffer.prepareBatch, :229-231) and createPdsl (same prepareBatch in renderOps, :355-357) drive the identical Java pattern-prepare phase; only the DSP backend differs.
- **Per-channel input clamp clip(-0.99,0.99) is applied before the dry-bus filter, matching AudioPassFilter.MAX_INPUT.**
  - source: PDSL_DIFFERENCES.md §1
  - receipt: mixdown_manager.pdsl MAIN arm of mixdown_master_wet: `clip(-0.99, 0.99)` before `fir(hp_coeffs[channel])` (engine/ml/.../pdsl/audio/mixdown_manager.pdsl, MAIN region ~:460-466).
- **Reverb gain structure matches the Java DelayNetwork constants: injection gain 0.1, wet output is the mean over lines (1/N), feedback is a Householder reflection at spectral radius 1/N.**
  - source: PDSL_DIFFERENCES.md §1
  - receipt: MixdownManagerPdslAdapter: reverb_network_gain=0.1 (:588), reverb_tap_mean=1.0/channels (:589), reverb_feedback=householderMatrix(channels, 1.0/channels) (:601). DelayNetwork.java gain default 0.1 (:109).
- **HP/LP filter selection is now gene-driven via a host-side generatable blend coeffs = hp*(1-sel) + lp*sel with sel = floor(min(decision,0.999999)×2) from the delayLevels gene; there is no choice() PDSL primitive.**
  - source: PDSL_DIFFERENCES.md §2; PDSL_DSP_REFERENCE.md
  - receipt: MixdownManagerPdslAdapter.efxFilterCoefficients (~:738-758): `sel = floor(min(decision,0.999999) * 2)` from levels.valueAt(src,2); `perChannel[ch] = hp*(1-sel) + lp*sel`. Class comment states Choice cannot be code-generated inside the compiled model.
- **Per-channel feedback delay is now gene-driven floor(delayTimes gene × beatSamples), and per-channel feedback level is the wetOut gene on the diagonal (fc(wetOut)).**
  - source: PDSL_DIFFERENCES.md §2/§6
  - receipt: efx_fb_delay = floor(delayTimes × beatSamples) (MixdownManagerPdslAdapter.java:543-546); efx_fb_passthrough = passthroughMatrix(manager,config) (:558), which puts getWetOut().valueAt(0) on the diagonal (:1013-1015).
- **Efx feedback is block-rate (re-enters once per buffer) not per-sample, with the transmission matrix scaled by feedbackGain/channels (0.6/N) to guarantee a stable contraction.**
  - source: PDSL_DIFFERENCES.md §3.1
  - receipt: mixdown_master_wet WET arm uses the block-parallel feedback() primitive (mixdown_manager.pdsl:499; comment 'Frame-quantized (block-parallel)'); efx_fb_transmission scaled by c(feedbackGain/config.channels) (MixdownManagerPdslAdapter.java:553) with feedbackGain=0.6 (:638). (The perceptual 'steppier/tamer decay' symptoms are separate, unverified.)
- **PDSL reverb uses REVERB_FRAMES=2 buffers with deterministic short line delays (fractions 0.3–0.85 of the ring), against the legacy DelayNetwork's random 0.15–1.5 s delays.**
  - source: PDSL_DIFFERENCES.md §3.2/§4.1
  - receipt: MixdownManagerPdslAdapter.reverbTapDelays: fraction = 0.3 + 0.55*(ch+1)/(channels+1) (range 0.3..0.85) (:689+). DelayNetwork.java: bufferLengths.fill = floor(0.1*max + 0.9*random()*max) with maxDelay=1.5 s → 0.15..1.5 s (:139,:109). Math.random() makes the legacy reverb non-deterministic per run.
- **PDSL filters are FIR approximations: pdslFilterOrder+1 = 41 taps, the truncated IR of the same biquad, gathered from a 1024-bin log-spaced cutoff table.**
  - source: PDSL_DIFFERENCES.md §3.4
  - receipt: pdslFilterOrder=40 (AudioSceneRealtimeRunner.java:87) → 41 taps. biquadResponseTable producer exists (MixdownManagerPdslAdapter.java:413). (The 1024-bin spacing and 'sub-perceptual' magnitude are partly reference/measurement.)
- **The PDSL master is dual-mono: one master rendered from the LEFT-region voicings and streamed to both stereo writers; true stereo is outstanding.**
  - source: PDSL_DIFFERENCES.md §3.6; KNOWN_ISSUES.md §5; STATE_OF_PLAY.md §5
  - receipt: AudioSceneRealtimeRunner.createPdsl streams the single masterOutput to both masterLeft and masterRight (:383-388); layer selection mixdown_master_wet/mixdown_master (:320-322); javadoc/comment confirm dual-mono and the missing per-channel PAN.
- **wet_filter_coeffs / efx_filter_coeffs are sampled at build time and lag the genome until rebuild (the only genuine live-swap staleness), while hp/lp cutoffs, volume, sends are per-buffer slots.**
  - source: PDSL_DIFFERENCES.md §6; STATE_OF_PLAY.md §5
  - receipt: wet_filter_coeffs/efx_filter_coeffs are producer args set once in buildArgsMap (MixdownManagerPdslAdapter.java:275 and efx variant), NOT among the per-buffer slots refreshed by automationRefresh (which refreshes reverb_send and cutoffs, :331-389). hp_coeffs/lp_coeffs are slots (:266-268).
- **supportsPdsl engages for any single channel or the zero-based contiguous prefix [0..n-1]; a non-contiguous subset (e.g. [0,2]) falls back to the CellList path.**
  - source: PDSL_DIFFERENCES.md §5; KNOWN_ISSUES.md §5
  - receipt: AudioSceneRealtimeRunner.supportsPdsl: size==1 -> true; else requires IntStream...allMatch(i -> channels.get(i)==i) (:169-179); create() falls back to createCellList otherwise (:134-143).
- **mixdown_master_wet = accum_blocks(MAIN, WET, REVERB) — three arms reading disjoint input slices that combine only at the final element-wise sum, run serially today.**
  - source: PERF_OPTIMIZATION.md lever 3
  - receipt: engine/ml/.../pdsl/audio/mixdown_manager.pdsl mixdown_master_wet body is accum_blocks( {MAIN region rows[0,channels)}, {WET region rows[channels,2*channels)}, {Reverb bus} ) (:442-520).
- **AR_PATTERN_CACHE_PERSIST reads via Boolean.getBoolean (pass literal true) and did NOT change the percussion stage cost (so the bottleneck is not a cache miss).**
  - source: PERF_OPTIMIZATION.md §harness
  - receipt: The Boolean.getBoolean read is verified (PatternLayerManager.java:200). (The 'did not change the percussion stage cost' half is a measurement claim and is itself unverified.)
- **BatchedPatternRenderer.buildResampleProducer does pitch resample as integers×ratio → floor → gather → lerp (a producer, not host math), and the floor() over a global-id expression has previously hit a Metal math-intrinsic overload ambiguity.**
  - source: KNOWN_ISSUES.md §3; A2_BATCHED_DISPATCH.md §3.4
  - receipt: BatchedPatternRenderer.buildResampleProducer (:343) computes srcPos then `floor(srcPos)` (:345) and gathers/lerps as producers. (The Metal overload-ambiguity history and the prior fix commits are historical and not re-verifiable read-only, but the resample-via-floor construct is present as claimed.)
- **The batched path bypasses ParameterizedVolumeEnvelope/ParameterizedFilterEnvelope.apply() (which remain hybrid evaluate()/toDouble) and instead regenerates envelope curves in-kernel from [N] ADSR scalar tensors.**
  - source: KNOWN_ISSUES.md §4; A2_BATCHED_DISPATCH.md §2.2
  - receipt: The batched path marshals [N] filter/volume ADSR scalar columns (BatchedPatternLayerRenderer.dispatchWindow filterAdsr/volumeAdsr writeColumn :483-506) and BatchedPatternRenderer generates the curves in-kernel (buildBatchedSssChainPlacedFromScalars :528-575) — never calling the envelope classes' apply(). (That those legacy classes still use a hybrid evaluate()/toDouble pattern is a separate code-read of ParameterizedVolumeEnvelope/FilterEnvelope, not done here.)
- **The PDSL DSP fully migrated to one compiled per-buffer model (mixdown_master/mixdown_master_wet) behind MixdownManager.enablePdslMixdown, on by default since 2026-06-26.**
  - source: STATE_OF_PLAY.md §4; PDSL_DIFFERENCES.md header
  - receipt: MixdownManager.enablePdslMixdown = SystemUtils.isEnabled("AR_PDSL_MIXDOWN").orElse(true) (studio/compose/.../MixdownManager.java:163-164); AudioSceneRealtimeRunner.create selects createPdsl when enablePdslMixdown && supportsPdsl (:134-137); compileMixdownModel builds one CompiledModel per buffer (:479-486). (The 'parity by ear' validation is the separate, unverified A/B.)