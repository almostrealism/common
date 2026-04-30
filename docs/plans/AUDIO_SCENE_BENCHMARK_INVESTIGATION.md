# AudioSceneOptimizer Performance Investigation

## Status

Open. Initial benchmarking confirms a super-linear relationship between scene
duration and multi-channel render wall-clock. Single-channel rendering scales
near-linearly (in fact sub-linearly for short durations as fixed overhead
amortizes). The cause is not yet identified.

## Empirical baseline

Initial sweep using `studio/compose/scripts/run-optimizer-benchmark.sh` with
`AR_BENCHMARK_DURATIONS=8,16,32,64`, `AR_BENCHMARK_CHANNEL=1` (percussive
channel), feature level 7, repeats=1. Source: console summary captured by the
user, 2026-04-28.

| measures | audio s | mode   | render ms | section ms | ratio  |
|---------:|--------:|--------|----------:|-----------:|-------:|
|        8 |      16 | multi  |     23170 |      88733 |  1.448 |
|        8 |      16 | single |     42121 |      46850 |  2.633 |
|       16 |      32 | multi  |    105978 |     171392 |  3.312 |
|       16 |      32 | single |     57307 |      62261 |  1.791 |
|       32 |      64 | multi  |    931067 |     998009 | 14.548 |
|       32 |      64 | single |     99125 |     104026 |  1.549 |
|       64 |     128 | multi  |   2720894 |    2788562 | 21.257 |
|       64 |     128 | single |    162283 |     167441 |  1.268 |

Per-doubling render-time growth ratios:

| audio doubling | multi growth | single growth |
|----------------|-------------:|--------------:|
| 8 → 16         |        4.6×  |          1.4× |
| 16 → 32        |        8.8×  |          1.7× |
| 32 → 64        |        2.9×  |          1.6× |

Interpretation:

- **Multi-channel is super-linear.** Doubling audio time more than doubles
  render time. The ratio (render-time / audio-time) is monotonically
  *increasing* with duration — the worst possible shape.
- **Single-channel is sub-linear.** Fixed overhead amortizes; ratio improves
  from 2.6 to 1.3 as duration grows. This is the expected shape.
- **`section_ms − render_ms` is nearly constant per mode** (~67 s for multi,
  ~5 s for single, regardless of duration). Scene compilation is one-time
  and not implicated. The blow-up is inside `setup.run() + N × tick.run()`.

Caveat: single-channel was tested only on channel 1 (percussive). We have
not yet isolated whether the multi-vs-single distinction is fundamentally
about combination of channels or about specifically-melodic content not
being in the single-channel run.

### Channel role reference

The default scene uses six channels with names assigned in
`AudioSceneLoader.Settings.defaultSettings`
(`AudioSceneLoader.java:303`) and a melodic flag computed in
`PatternSystemManager.Settings.defaultSettings` as `c > 1 && c != 5`
(`PatternSystemManager.java:541`):

| index | name       | melodic | scale traversal |
|------:|------------|:-------:|-----------------|
| 0     | Kick       |   no    | —               |
| 1     | Drums      |   no    | —               |
| 2     | Bass       |  yes    | SEQUENCE        |
| 3     | Harmony    |  yes    | CHORD           |
| 4     | Lead       |  yes    | SEQUENCE        |
| 5     | Atmosphere |   no    | —               |

So the only melodic channels are **2, 3, 4**. Channels 0, 1, and 5 are
non-melodic. Channel 3 uses a different scale traversal (CHORD) from 2 and
4 (SEQUENCE) — chord-rendering may exercise additional code paths over
single-note sequences.

## Rendering path summary

`AudioScene.runnerRealTime(output, channels, bufferSize)` builds a
`TemporalCellular` whose `setup` and `tick` are populated from `getCells(...)`.
For each channel in `channels` (or all channels when null), a
`PatternAudioBuffer` is constructed and its `setup()` and initial
`prepareBatch()` are added to the setup operation list. Per-channel EFX is
appended via `efx.apply(channel, renderCell.getOutputProducer(), ...)`. The
master mix is composed by `MixdownManager`, which applies main filters,
transmission, reverb, automation, and stem routing depending on feature level.

Two phases run repeatedly:

1. **`setup.run()`** — runs once before the tick loop. Includes
   `automation.setup()`, optional `riser.setup()`, `mixdown.setup()`,
   `time.setup()`, plus per-channel `renderCell.setup()` and an initial
   `renderCell.prepareBatch()` to populate buffer 0.
2. **`tick.run()`** — runs once per `bufferSize` (1024) frames. Each tick:
   resets `bufferFrameIndex`, calls `renderCell.prepareBatch()` for every
   render cell (Java-side note rendering, outside the compiled loop), runs
   the compiled per-frame loop body for `bufferSize` iterations, then
   advances `currentFrame` by `bufferSize`.

`PatternAudioBuffer.prepareBatch()` calls
`PatternSystemManager.sum(context, channel, currentFrame, bufferSize)` which
delegates to `PatternLayerManager.sum`, ultimately invoking
`sumInternal(...)`. `sumInternal` computes `firstRepetition` and
`lastRepetition` from the buffer's frame range and iterates only over
overlapping pattern repetitions — so per-buffer work is *theoretically*
proportional to overlapping repetitions in the buffer (1–2 typically), not
the full arrangement length.

The benchmark's `render_ms` includes both `setup.run()` and the sum of
`tick.run()` calls. We have not yet split these.

## Hypotheses (post-elimination)

The user has indicated, based on existing measurement infrastructure, that
GC/native-allocator pressure (#4) and JIT/code-cache thrash (#5) are unlikely.
Active hypotheses are #1, #2, #3.

### H1 — `setup.run()` grows with `totalMeasures × channelCount`

Some component invoked from `addCommonSetup` (automation/mixdown/sections/
riser) executes work whose size is proportional to total arrangement length
and is multiplied per channel. The 6-channel multi path pays this 6× and
dominates `render_ms`. The `section_ms − render_ms` constant rules out
*compilation* growing with duration; this hypothesis concerns *execution*
during `setup.run()`.

### H2 — Per-buffer `tick.run()` cost grows with elapsed frame

Either `noteAudioCache.evictBefore(frame)` is not actually evicting for some
shape of pattern (cache grows unbounded), or some downstream component reads
or writes a slice whose effective size grows with the current frame position.
Per-buffer cost should appear roughly constant under H1 and H3, growing under
H2.

### H3 — Multi-channel cross-channel kernel does O(channels²) per frame

With feature level 7, `MixdownManager` enables transmission, reverb,
automation, EFX, and main-filter passes that mix across channels. The
compiled per-frame kernel may have a shape that is O(channels²) in some
component. Per-buffer cost would then be ~constant in time but ~30× higher
than single. By itself this gives a higher *level* of `tick.run()` cost,
not a super-linear *trend* in time. To explain the observed shape it must
combine with H1 (setup grows) or H2 (tick grows in time).

## Experiments

The instrumentation must allow us to split `setup_ms` from `tick_total_ms`,
report per-tick distribution statistics, and emit a kernel-attributable
profile XML for analysis with `mcp__ar-profile-analyzer__*`.

Tests are listed in priority order. Tests A and B run inside a single
benchmark and together eliminate either H1 or {H2, H3}.

### A — Split setup vs. tick timing (highest information / lowest cost)

Benchmark records `setup_ms` (wall-clock around `setup.run()`) and
`tick_total_ms` separately. CSV gains both columns plus a `setup_ratio` and
`tick_ratio` (each divided by audio time).

Outcomes:
- If `setup_ms` grows super-linearly with measures and `tick_total_ms` is
  roughly linear → H1 confirmed. Investigation moves into `addCommonSetup`
  and per-channel `renderCell.setup()`.
- If `setup_ms` is roughly constant (or merely linear) and `tick_total_ms`
  is super-linear → H1 ruled out. Move to test B output.

### B — Per-tick distribution

Inside the tick loop, time each `tick.run()` individually. Record:
- `tick_min_ms`, `tick_p50_ms`, `tick_p95_ms`, `tick_max_ms`
- `tick_first_decile_mean_ms`, `tick_last_decile_mean_ms`
- `tick_count`

This must be extremely cheap — `System.nanoTime()` around each call, a
fixed-size primitive long array per section, computed once at section end.

Outcomes:
- If first-decile and last-decile means are within ~10% → tick cost is
  constant in time. H2 ruled out. Any super-linear growth from test A must
  be in setup (H1).
- If last-decile is materially higher than first-decile → H2 confirmed.
  Investigation moves into `PatternLayerManager.sumInternal`,
  `noteAudioCache`, and any compiled per-frame state that scales with
  cumulative frames.

### C — Channel-count sweep at fixed duration

Run multi at `channels = [0]`, `[0,1]`, `[0,1,2]`, …, `[0,1,2,3,4,5]` for a
fixed duration (e.g., 32 measures). Plot render time vs channel count.
Linear → no per-channel-pair quadratic cost. Quadratic → H3 confirmed.

The current benchmark code path always uses `channels=null` (all) for multi
and `channels=[N]` (one) for single; this test requires an explicit list
parameter.

### D — Feature-level sweep at fixed channel set

Run multi at feature levels 0, 2, 4, 7 across 8 / 16 / 32 measures. The
lowest level that produces super-linear growth identifies which features
introduce the offending cost (env / efx / transmission / reverb / automation).

### E — Single-channel content sweep

Re-run single across at least one channel from each role:

- channel 1 (Drums) — non-melodic, the channel already tested.
- channel 4 (Lead) — melodic, SEQUENCE traversal: the cleanest probe of the
  pitch-adjustment path (single melodic notes).
- channel 3 (Harmony) — melodic, CHORD traversal: exercises the chord
  rendering path that the SEQUENCE channels do not.

If all three scale linearly, the multi-vs-single distinction is genuinely
about combination, not channel content. If a melodic channel is
super-linear on its own, the pitch-adjustment path (or chord path for ch 3)
is implicated; comparing 2/4 (SEQUENCE) against 3 (CHORD) further narrows
which.

### F — `OperationProfileNode` XML capture

The optimizer already supports `OperationProfileNode` via
`AudioSceneOptimizer.setVerbosity(verbosity, true)`, which calls
`Hardware.getLocalHardware().assignProfile(profile)`. The benchmark must
do the same and emit one XML per (mode, measures, repeat). Filenames go in
the CSV so `mcp__ar-profile-analyzer__load_profile` can attribute the
super-linear cost to specific kernels and operations.

This is the single most informative artifact for diagnosing H1 vs. H3 once
the wall-clock split (A) has narrowed the hypothesis.

### G — Stem-output toggle

Run multi with `enableStemOutput=true` and `false` at the same durations.
If render time drops disproportionately with stems off, the per-stem
WaveOutputs are part of the offending cost. Cheap to do; useful elimination.

## Instrumentation requirements

The benchmark needs the following changes before running these experiments:

1. Split `render_ms` into `setup_ms` and `tick_total_ms` (test A).
2. Per-tick distribution statistics (test B).
3. Explicit `channels` list parameter for the multi path (test C).
4. Feature level already configurable (test D — done).
5. Channel index already configurable for single (test E — done).
6. Always emit an `OperationProfileNode` XML; file path included in CSV
   (test F).
7. Toggle `enableStemOutput` via property (test G).

Updated CSV schema:

```
mode,measures,channels,feature_level,audio_seconds,frames,
setup_ms,tick_total_ms,render_ms,section_ms,
tick_count,tick_min_ms,tick_p50_ms,tick_p95_ms,tick_max_ms,
tick_first_decile_ms,tick_last_decile_ms,
ratio,setup_ratio,tick_ratio,
profile_xml,wav_path,repeat,warmup
```

`render_ms` remains as `setup_ms + tick_total_ms` for continuity with the
previous baseline. New columns are appended; tooling that reads the CSV by
column name will not break.

## Execution sequence

Tests are run from the `common` working directory via
`studio/compose/scripts/run-optimizer-benchmark.sh`. Each run produces:

- a CSV at `AR_BENCHMARK_REPORT` (path varies per run, see below)
- one `OperationProfileNode` XML per (mode, measures, repeat) under
  `AR_BENCHMARK_PROFILE_DIR=results/profiles`
- per-section WAV master output under `results/`
- console log mirrored to `results/logs/audio-scene-benchmark.out`

Order:

1. **Run 1 — baseline replay with split timing (Tests A + B).**
   Reproduces the existing baseline with the new schema so we can read
   `setup_ms`, `tick_total_ms`, and per-tick distribution for both modes.
   ```
   AR_BENCHMARK_DURATIONS=8,16,32,64 AR_BENCHMARK_CHANNEL=1 \
     AR_BENCHMARK_REPORT=results/audio-scene-benchmark-run1.csv \
     studio/compose/scripts/run-optimizer-benchmark.sh
   ```
   Decision criterion: if multi `setup_ms` grows super-linearly with
   measures and `tick_total_ms` is roughly linear → H1 confirmed; pivot
   investigation into `addCommonSetup` and per-channel `renderCell.setup()`.
   Otherwise compare `tick_first_decile_ms` vs `tick_last_decile_ms`: if
   last is materially higher than first → H2 confirmed; pivot into
   `PatternLayerManager.sumInternal` and `noteAudioCache`. If neither, H3 is
   the remaining hypothesis and per-channel sweep (Run 4) is decisive.

2. **Run 2 — single-channel Lead (channel 4, melodic, SEQUENCE).**
   ```
   AR_BENCHMARK_DURATIONS=8,16,32,64 AR_BENCHMARK_CHANNEL=4 \
     AR_BENCHMARK_RUN_MULTI=false \
     AR_BENCHMARK_REPORT=results/audio-scene-benchmark-ch4.csv \
     studio/compose/scripts/run-optimizer-benchmark.sh
   ```

3. **Run 3 — single-channel Harmony (channel 3, melodic, CHORD).**
   ```
   AR_BENCHMARK_DURATIONS=8,16,32,64 AR_BENCHMARK_CHANNEL=3 \
     AR_BENCHMARK_RUN_MULTI=false \
     AR_BENCHMARK_REPORT=results/audio-scene-benchmark-ch3.csv \
     studio/compose/scripts/run-optimizer-benchmark.sh
   ```

4. **Run 4 — channel-count sweep at fixed 32 measures (Test C).**
   ```
   for ch in 0 0,1 0,1,2 0,1,2,3 0,1,2,3,4 0,1,2,3,4,5; do
     AR_BENCHMARK_DURATIONS=32 AR_BENCHMARK_MULTI_CHANNELS="$ch" \
       AR_BENCHMARK_RUN_SINGLE=false \
       AR_BENCHMARK_REPORT="results/audio-scene-benchmark-c${ch//,/_}.csv" \
       studio/compose/scripts/run-optimizer-benchmark.sh
   done
   ```

5. **Run 5 — feature-level sweep (Test D), conditional on prior outcomes.**
   Only run if Runs 1–4 leave any feature-level dependence ambiguous:
   ```
   for fl in 0 2 4 7; do
     AR_BENCHMARK_DURATIONS=8,16,32 AR_BENCHMARK_FEATURE_LEVEL=$fl \
       AR_BENCHMARK_RUN_SINGLE=false \
       AR_BENCHMARK_REPORT="results/audio-scene-benchmark-fl${fl}.csv" \
       studio/compose/scripts/run-optimizer-benchmark.sh
   done
   ```

6. **Run 6 — stem toggle (Test G), conditional.**
   ```
   AR_BENCHMARK_DURATIONS=16,32 AR_BENCHMARK_STEMS=false \
     AR_BENCHMARK_RUN_SINGLE=false \
     AR_BENCHMARK_REPORT=results/audio-scene-benchmark-nostems.csv \
     studio/compose/scripts/run-optimizer-benchmark.sh
   ```

After each run, profile XMLs go through
`mcp__ar-profile-analyzer__load_profile` followed by
`mcp__ar-profile-analyzer__find_slowest` /
`mcp__ar-profile-analyzer__get_timing_breakdown` for kernel-level
attribution. Findings are appended to this document under a new "Results"
section per run.

## Status

- 2026-04-29: Plan and instrumentation complete. Run 1 launched, completed
  six of eight sections, then OOM during the multi-64m profile XML write.
  Findings recorded below.

## Run 1 — results (8m, 16m, 32m for both modes)

CSV: `/Users/Shared/Music/results/audio-scene-benchmark-run1.csv`. Profile
XMLs (six valid + one truncated 64m): `/Users/Shared/Music/results/profiles/`.

### Wall-clock split

| mode  | meas | setup_ms | tick_total | render_ms | first10 | last10 | p50 | ratio |
|-------|-----:|---------:|-----------:|----------:|--------:|-------:|----:|------:|
| multi |    8 |      932 |      27253 |     28185 |      43 |     38 |  37 | 1.762 |
| multi |   16 |      422 |      55671 |     56093 |      40 |     40 |  39 | 1.753 |
| multi |   32 |      509 |     491343 |    491852 |    **33** | **323** | 311 | 7.685 |
| single|    8 |      140 |      47874 |     48014 |      67 |     71 |  69 | 3.001 |
| single|   16 |      120 |      55560 |     55680 |      40 |     40 |  39 | 1.740 |
| single|   32 |      152 |      74238 |     74390 |      25 |     28 |  26 | 1.162 |

### Conclusions from wall-clock

- **H1 (setup grows) ruled out.** Setup is ~500 ms regardless of measures
  in the multi path; ~150 ms in single. Linear at most.
- **H2 (per-tick grows in time) confirmed at multi 32m.** Per-tick
  first-decile = 33 ms; last-decile = 323 ms — a 10× progressive slowdown
  *within a single render*. Per-tick first-decile at multi 32m is similar
  to multi 8m and multi 16m (33–43 ms), so JIT warmup is not the cause.
- **The threshold is between 16m and 32m.** Multi 8m and 16m both show
  flat per-tick. Multi 32m exhibits monotonic growth.
- **Single never shows it.** Even single 32m has first10 = 25 ms,
  last10 = 28 ms — within noise. Per-second-of-audio cost actually
  *improves* with longer single renders (3.0 → 1.7 → 1.2 ratio).

### Compiled-kernel attribution (from `OperationProfileNode` XMLs)

Total compiled-kernel time per profile (extracted via direct XML scan; the
ar-profile-analyzer MCP returned correct data but its CLI wrapper truncates
output past ~500 chars, so the table below was produced by a Python regex
over the raw XML):

| profile     | total_kernels_s | tick_total_ms (CSV) | java_overhead_s |
|-------------|----------------:|--------------------:|----------------:|
| multi  8m   |             ~17 |              27 253 |             ~10 |
| multi 16m   |             ~40 |              55 671 |             ~16 |
| multi 32m   |             ~99 |             491 343 |            ~392 |
| single 32m  |             ~63 |              74 238 |             ~11 |

Compiled-kernel time grew 40 s → 99 s (2.5×) for 2× audio doubling — close
to linear. **Java-side orchestration grew 16 s → 392 s (~24×) for the same
2× doubling.** The super-linear cost is *not* on the GPU; it lives in the
Java code between kernel calls.

Top compiled kernels (totals across each entire run):

| kernel | 8m total / inv / avg | 16m total / inv / avg | 32m total / inv / avg |
|---|---|---|---|
| `f_loop_X` (per-frame body) | 13.4 s / 690 / **19.4 ms** | 27.1 s / 1379 / **19.6 ms** | 55.2 s / 2757 / **20.0 ms** |
| `f_collectionProductComputation_5` | 2.7 s / 16584 / **0.164 ms** | 5.4 s / 33120 / **0.164 ms** | 22.1 s / 66192 / **0.334 ms** |
| `f_collectionZerosComputation_52` | 2.9 s / 16584 / **0.175 ms** | 5.8 s / 33120 / **0.174 ms** | 13.8 s / 66192 / **0.208 ms** |
| `f_collectionAddComputation_2` | 0.7 s / 40326 / 0.018 ms | 1.4 s / 81808 / 0.017 ms | 5.0 s / **286334** / 0.017 ms |
| `f_multiOrderFilter_480` | 22 ms / 25 / 0.86 ms | 15 ms / 24 / 0.64 ms | 414 ms / **465** / 0.89 ms |
| `f_defaultTraversableExpressionComputation_268` | 22 ms / 75 / 0.30 ms | 20 ms / 72 / 0.27 ms | 749 ms / **1395** / 0.54 ms |
| `f_interpolate_27655` | 13 ms / 30 / 0.43 ms | 7 ms / 14 / 0.49 ms | 186 ms / **295** / 0.63 ms |

### Two distinct kernel-level effects at multi 32m

1. **Per-invocation cost roughly doubled for the same compiled kernel.**
   `f_collectionProductComputation_5` average per-invocation was 0.164 ms
   at both 8m and 16m, then jumped to 0.334 ms at 32m. Same compiled
   instructions, same invocation count per tick (24/tick = 16584/690,
   33120/1379, 66192/2757). The only thing that explains a same-kernel
   slowdown is **kernel argument-size growth** — the {@link
   org.almostrealism.collect.PackedCollection} the kernel multiplies is
   bigger at 32m than at 16m. `f_collectionZerosComputation_52` shows the
   same pattern at smaller magnitude (0.174 → 0.208 ms).
2. **A class of secondary kernels suddenly fires 15–35× more often** at
   32m than at 16m, despite audio only doubling: filter/expression/
   interpolate kernels go from ~24 invocations across the whole 16m run
   to ~465 invocations across the 32m run (roughly equal in 8m and 16m,
   so this is not a per-tick or per-buffer effect — it's triggered by
   something that accumulates).

### Java-orchestration attribution

Per-tick wall-clock at multi 32m is 311 ms (p50). Compiled kernels per
tick total ~33 ms (`f_loop_X` 20 ms + `Product_5` 24 × 0.334 = 8 ms +
`Zeros_52` 24 × 0.208 = 5 ms). **The remaining ~278 ms per tick is Java
orchestration**: iterating `PatternElement → notes`, cache get/put,
`PackedCollection.range()` delegate allocation, JNI parameter binding,
and per-note `sumToDestination` calls inside `PatternFeatures.render`.
Add-call count grew 59 → 104 per tick (1.75× more notes overlapping each
buffer at 32m vs 16m); orchestration overhead per add presumably
grew too as more state accumulated.

### Refined diagnosis

The accumulating quantity that drives the slowdown is **per-buffer active-
note overlap depth in the multi-channel render**. Two observations support
this:

- `f_collectionAddComputation_2` (the per-note sum-to-destination kernel)
  fires 58 / tick at multi 16m vs 104 / tick at multi 32m — same compiled
  kernel, more invocations per tick at later frames.
- The argument-size growth in `f_collectionProductComputation_5` is
  consistent with a destination/working buffer whose effective populated
  range grows with the active-note count. (Compiled CL/Metal kernels
  scale with their input dimension, so a `multiply` over more elements
  takes proportionally longer.)

Why does this only manifest in multi at long durations? Because multi has
6 simultaneously-active channels each contributing notes, while single
has only 1. Note overlap depth that's harmless at 1 channel can become
super-linear at 6.

### Run 1 known issue (instrumentation-only)

At the seventh section (multi 64m) the profile XML write hit
`OutOfMemoryError` inside `XMLEncoder.writeObject`. The XML writer
materializes the full profile tree (170k+ nodes for multi 32m, more for
64m) and the 2 GB heap was insufficient. The benchmark process completed
the 64m render but failed during the profile save and never reached the
single-64m section. JVM hung in cleanup at 0% CPU; killed manually.

Fixes for Run 2 onward (see Followups):

- Catch `Throwable` (not just `IOException`) in `saveProfile` so an OOM
  during XML write does not abort the whole sweep.
- Bump benchmark JVM heap to `-Xmx8g` in `run-optimizer-benchmark.sh`.
- Optional: skip profile XML for very long durations, or write profile
  before WAV so a failed WAV write doesn't lose the profile.

## Next experiments (revised)

Given Run 1's findings, the priority order changes:

1. **Per-tick timeline dump.** Add an option to write the full
   `tickNs` array to a CSV/Parquet file so the *shape* of the per-tick
   growth curve can be plotted. The first10/last10 split is enough to
   detect growth, but to identify the trigger we need to see the curve.
   This is a small benchmark change.
2. **Channel-count sweep at multi 32m.** Test C from the original plan.
   With `noteAudioCache` per-channel, the slowdown should grow with
   channel count. The shape of `tick_total_ms` vs channel count tells us
   whether the cost is linear in channels (additive) or quadratic
   (cross-channel). Do this even before melodic single tests because the
   immediate question is "how does multi scale with channel count" —
   this directly informs whether a fix should target per-channel or
   cross-channel state.
3. **Single-channel melodic sweep.** Channel 4 (Lead, SEQUENCE) and
   channel 3 (Harmony, CHORD) at 8/16/32m. If even a single melodic
   channel is super-linear at 32m, the cause is melodic-content-specific
   rather than channel-combination-specific.
4. **Targeted source reading.** With the per-tick timeline curve in hand,
   read `PatternLayerManager.sumInternal` and `PatternFeatures.render`
   to find which iteration grows non-linearly with cumulative active
   notes. Most likely site: the `elements.stream() ... forEach(note -> ...)`
   loop in `render`, where the note set grows as cumulative repetitions
   contribute notes that haven't yet decayed past current buffer.

5/6/7 (feature-level sweep, stem toggle, OperationProfileNode walking)
remain as documented above but are now lower priority.

## Run 4 — results (channel-count sweep at 32m, 6 fresh JVMs)

CSVs: `/Users/Shared/Music/results/audio-scene-benchmark-run4-c*.csv`
Profile XMLs: `/Users/Shared/Music/results/profiles/benchmark-multi-c*-32m-r0.xml`
Per-tick timelines: `/Users/Shared/Music/results/timelines/benchmark-multi-c*-32m-r0.csv`

Each iteration ran in a **fresh JVM** (driver script invokes
`run-optimizer-benchmark.sh` once per channel set). Each JVM exits cleanly
via `System.exit(0)` added after Run 1's Metal-cleanup hang.

### Wall-clock and per-tick distribution

| ch_n | channels | tick_total | first10 | last10 | late/early | ratio |
|-----:|----------|-----------:|--------:|-------:|-----------:|------:|
| 1 | 0 | 7 950 | 3 ms | 3 ms | 0.96× | 0.130 |
| 2 | 0,1 | 70 301 | 26 | 25 | 0.96× | 1.107 |
| 3 | 0,1,2 | 126 840 | 50 | 42 | 0.84× | 1.993 |
| 4 | 0,1,2,3 | 89 533 | 29 | 36 | 1.24× | 1.410 |
| 5 | 0,1,2,3,4 | 195 812 | 58 | 84 | 1.44× | 3.072 |
| 6 | 0,1,2,3,4,5 | **399 440** | **144** | **145** | **1.00×** | 6.257 |

The per-tick growth ratio (late/early) is essentially 1.0 at ch_n = 1, 2,
3, and 6, with mild positive growth only at ch_n = 4 and 5 (likely genome
variance — each invocation uses a fresh random genome and pattern density
varies). Critically, **ch_n = 6 in a fresh JVM is perfectly flat
(144 → 145 ms/tick)**. Compare with Run 1's same configuration which ramped
33 → 323 ms over the same render.

The ch_n = 3 vs ch_n = 4 inversion (3 ch slower than 4 ch) is genome
variance: the random patterns generated for that ch=3 sweep happened to be
denser. To eliminate it cleanly, future sweeps should set
`AR_BENCHMARK_REPEATS≥3` and look at medians.

### Compiled-kernel attribution

| ch_n | f_loop avg | product_5 inv | product_5 avg | add_2 inv |
|-----:|-----------:|--------------:|--------------:|----------:|
| 1 |   0.17 ms | 11032 = ×1 | 0.149 ms |  17677 |
| 2 |  19.07 ms | 22064 = ×2 | 0.208 ms |  77026 |
| 3 |  19.25 ms | 33096 = ×3 | 0.296 ms | 142800 |
| 4 |  19.33 ms | 44128 = ×4 | 0.198 ms |  44473 |
| 5 |  19.54 ms | 55160 = ×5 | 0.287 ms | 117312 |
| 6 |  19.80 ms | 66192 = ×6 | 0.421 ms | 226609 |

`f_collectionProductComputation_5` invocation count is **exactly
11032 × n_channels** for ch_n = 1..6 (linear in channels). The per-frame
loop kernel `f_loop_X` has a constant ~19 ms / call across ch_n = 2..6,
suggesting a single fused kernel that internally handles all active
channels regardless of count. The 1-channel case is special (0.17 ms /
call): the multi mixdown chain effectively collapses when only one channel
is active.

`product_5` average per-invocation cost rises from 0.149 ms (1 ch fresh
JVM) to 0.421 ms (6 ch fresh JVM) — **2.8× growth** for 6× more channels.
The kernel itself doesn't change; the buffer it multiplies grows because
more channels participate.

### Critical reinterpretation of Run 1 H2

Run 1's multi 32m exhibited a 10× within-run per-tick growth (33 →
323 ms). Run 4's identical configuration in a fresh JVM is flat
(144 → 145 ms/tick). **The within-run growth in Run 1 was not a property
of multi 32m itself** — it was triggered by running multi 32m after four
prior sections in the same JVM (multi 8m → single 8m → multi 16m →
single 16m).

The cold-JVM `f_loop` per-tick at 6 channels is **higher** than the
warm-JVM first-decile (Run 4 144 ms vs Run 1 33 ms). Prior sections
warm the JIT and each section starts faster than cold. But over the
warm 32m run something **accumulates** that erodes that advantage
until the warm-JVM run is *slower* per tick (323 ms) than the cold-JVM
flat baseline (145 ms).

This matches the production symptom in `AudioSceneOptimizer`: many
genomes are evaluated back-to-back in one JVM, each contributes some
accumulating state, and eventually new evaluations are progressively
slower.

### Refined hypotheses

H2 from earlier is replaced with two more specific hypotheses:

- **H2a — Inter-section JVM-state accumulation.** Some state lives
  across `pop.destroy()` + `scene.destroy()` cycles and progressively
  inflates either kernel argument sizes (visible in `product_5` /
  `Zeros_52` per-call cost) or Java orchestration cost (visible as the
  ~278 ms / tick of non-kernel time at warm Run 1 multi 32m). Plausible
  carriers: hardware kernel cache, expression cache, native memory
  pool fragmentation, accumulating `ContextSpecific` registrations,
  static collectors in `WaveData` / `AudioProcessingUtils`.
- **H2b — Single-channel resets it.** Run 1's single 32m ran
  immediately after the slow multi 32m and was flat at 26 ms/tick.
  Either the single path uses different state, or `pop.destroy()`
  between sections does eventually free the multi-specific state.
  Identifying what destruction the single path triggers but the
  follow-up multi path doesn't is informative.

H3 (multi-channel cross-channel quadratic kernel) is mostly ruled out
by Run 4 — `product_5` invocations are exactly linear in channel count,
and `f_loop` is constant per call from ch_n = 2 onward. Channel count
is roughly linear in cost (modulo ~30% genome variance).

## Next experiments (revised again)

Highest-priority experiments now target H2a:

A. **Same-config back-to-back in one JVM.** Run multi 32m three times in
   the same JVM. If iterations 2 and 3 are progressively slower than
   iteration 1, accumulation is confirmed. With repeated identical
   genomes (or even random ones), genome variance is averaged out.

   ```
   AR_BENCHMARK_DURATIONS=32 AR_BENCHMARK_REPEATS=3 \
     AR_BENCHMARK_RUN_SINGLE=false \
     AR_BENCHMARK_REPORT=results/audio-scene-benchmark-run5-stack.csv \
     studio/compose/scripts/run-optimizer-benchmark.sh
   ```

B. **Reproduce Run 1's exact sequence.** A single JVM running
   8m multi → 8m single → 16m multi → 16m single → 32m multi.
   If 32m multi shows the ramp again, the sequence is the trigger.

   ```
   AR_BENCHMARK_DURATIONS=8,16,32 \
     AR_BENCHMARK_REPORT=results/audio-scene-benchmark-run5-seq.csv \
     studio/compose/scripts/run-optimizer-benchmark.sh
   ```

C. **JMX heap snapshot at the end of run B's 32m multi.** Compare class
   histogram against fresh-JVM baseline. The leak is in whatever class
   has anomalous retained-instance growth.

D. **Profile-compare the warm-vs-cold 32m multi.** Both XML files
   exist; diff their kernel-by-kernel per-invocation costs. Any
   kernel that costs significantly more per call in warm vs cold has
   inflated argument sizes.

A is cheapest (one JVM, ~30 min) and most decisive for H2a. Run B and
C only if A confirms. D is offline analysis on existing files.

## Status

- 2026-04-29: Run 1 launched, six of eight sections completed, OOM
  during the multi-64m profile XML write. H1 ruled out, original H2
  confirmed at multi 32m with 10× per-tick growth.
- 2026-04-29: Instrumentation upgrades — saveProfile catches Throwable,
  per-tick timeline CSV export, AR_BENCHMARK_HEAP / DISABLE_PROFILE
  toggles, System.exit(0) at end of main to escape Metal-cleanup hang.
- 2026-04-29: Run 4 launched, completed cleanly in ~22 min with all six
  channel sweeps. Showed flat per-tick at 6 channels in fresh JVM —
  contradicting Run 1's within-run ramp. Diagnosis pivoted: the ramp
  is JVM-state-accumulation across prior sections (H2a), not a
  property of long multi renders. H3 mostly ruled out (cost roughly
  linear in channel count). Run 5 (back-to-back same-config) is the
  next decisive test.
- 2026-04-29: Run 5A launched (multi 32m × 3 repeats, single JVM,
  warmup + 2 measurement repeats). **H2a decisively confirmed.**
- 2026-04-29: Instrumented `AudioSceneBenchmark` with optional
  inter-repeat pause + sentinel marker file
  (`AR_BENCHMARK_INTER_REPEAT_PAUSE_MS`) so external JMX tooling can
  capture class histograms during a stable idle window.
- 2026-04-29: Run 5C launched (multi 32m × 3, JMX-attached, 120s
  inter-repeat pause + `System.gc()` between repeats, profile capture
  disabled). **H2a timing degradation did NOT reproduce.** Run 5C
  reveals two distinct phenomena that we had previously conflated.
- 2026-04-29: Run 5D-1 launching — cheapest discriminator for
  phenomenon Y. Configuration: profile=disabled, pause=0, no
  `System.gc()`, multi 32m × 3. If rep 2 is flat the trigger of Y is
  profile-tree retention (Run 5A condition); if rep 2 is slow the
  trigger is GC pressure / no idle reset (independent of profile).
- 2026-04-29: Run 5D-1 completed. **Phenomenon Y reproduced
  (rep-1 ratio 5.10) without profile capture.** Profile retention
  ruled out as Y's trigger. Within-rep ramp-down (first10=199ms,
  last10=37ms) implicates a transient major-GC event during render.
  Y is the user-visible symptom of X (heap retention). Fixing X is
  the path forward — proceeding to Run 5E.
- 2026-04-29: Run 5E-2 completed. Configuration: same as Run 5D-1
  (profile=disabled, no cache drain) plus
  `AR_BENCHMARK_INTER_REPEAT_PAUSE_MS=2000` (2 second pause + a
  `System.gc()` call). **Y fully prevented.** rep 1 ratio 1.70,
  rep 2 ratio 1.93 — matching Run 5C's 120-second-pause result. The
  carrier of Y is GC pressure relievable by an explicit `System.gc()`;
  a 2 second pause is sufficient. The 118-second additional idle time
  in Run 5C was unnecessary. **Practical production fix: insert
  `System.gc()` between optimizer genome evaluations.**
- 2026-04-29: Production fix landed in
  `AudioScenePopulation.enableGenome(int)` behind a static
  `gcBeforeGenome` toggle (default `true`). Both
  `AudioSceneOptimizer` (via `HealthCallable.enableGenome`) and
  ringsdesktop preview (`AudioScenePopulation.generate()`) traverse
  this single hook. Verified in benchmark via new
  `AR_BENCHMARK_REUSE_SCENE` mode that mirrors the optimizer's
  one-scene-many-genomes lifecycle (Run 5E-7 vs 5E-8 controlled
  pair): post-warmup average render time drops 2.4× with the fix.
- 2026-04-29: Run 5E-1 launched (drain `NoteAudioProvider.audioCache`
  between reps via new `dropAll()` on `CacheManager` + `clearCache()`
  on `NoteAudioProvider`). **Negative result.** Two passes:
  1. Initial implementation called `v.clear()` per entry, which
     invokes the configured destroy consumer. The consumer cascaded
     into still-live state shared via delegate chains; warmup ran at
     ratio 7.08 and the JVM exited via runtime exception after one
     repetition. Replaced with `dropAll()` that drops HashMap
     references only and does not actively destroy values.
  2. Re-run with `dropAll()` (`run5e-1d`, durations=8, repeats=3):
     rep 0 ratio 2.22 (warmup, normal); rep 1 ratio 8.66, p50=198,
     first10=201, last10=199 — uniformly slow, *no* within-rep
     recovery. Phenomenon Y still triggered, in fact more severely
     than Run 5D-1's rep-1 ratio 5.10.

  Conclusion: clearing only the audio cache between reps does **not**
  prevent Y. Audio cache retention alone is not the dominant carrier,
  or if it is, dropping references doesn't trigger the GC needed to
  relieve render-time pressure. The relief Run 5C observed comes from
  the explicit `System.gc()` (which compacts old gen during the idle
  window) — not from the pause itself or from entries leaving the
  cache.

## Run 5A — Same-config back-to-back in one JVM

Configuration: `AR_BENCHMARK_DURATIONS=32 AR_BENCHMARK_REPEATS=3
AR_BENCHMARK_RUN_SINGLE=false AR_BENCHMARK_MULTI_CHANNELS=all`. Three
back-to-back multi 32m renders in one JVM. Repeat 0 is warmup (JIT
cold), repeats 1 and 2 are measurement.

Per-repeat tick statistics (multi, 32m, all channels):

| repeat | setup_ms | tick_total_ms | first10_ms | p50_ms | last10_ms | tick_max_ms | ratio |
| ------:| --------:| -------------:| ----------:| ------:| ---------:| -----------:| -----:|
| 0 (warm-up) | 920 | 174 420 | 71 | 64 | 56 | — | 2.74 |
| 1 (warm)    | 545 |  98 421 | 39 | 36 | 32 | — | 1.55 |
| 2 (degraded)| 896 | 549 517 | 259 | 248 | 140 | — | **8.60** |

Per-tick timeline shape (sampled coarsely):

- repeat 0: 71 → 69 → 63 → 57 → 57 ms — smooth downward (JIT warming).
- repeat 1: 39 → 39 → 36 → 32 → 32 ms — smooth downward, fully warm.
- repeat 2: 259 → 259 → 200 → 139 → 140 ms — starts ~7× slower than
  repeat 1's steady state, recovers partially but never returns to
  warm baseline.

Profile kernel attribution (rep 0 / rep 1 / rep 2 totals and per-call
averages from `OperationProfileNode` XML):

| kernel | rep 0 total / avg | rep 1 total / avg | rep 2 total / avg |
| --- | --- | --- | --- |
| `f_loop_X` | 53.8 s / 19.5 ms | 53.5 s / 19.4 ms | 55.1 s / 20.0 ms |
| `f_collectionProductComputation_5` | 16.5 s / 0.249 ms | 11.1 s / 0.168 ms | 29.9 s / **0.451 ms** |
| `f_collectionAddComputation_2` invocations | 205 378 | 112 486 | **516 545** |

`f_loop_X` (the master per-buffer kernel) is **flat across all three
repeats** in both total time and per-call cost. The compiled inner
loop is not the regression. The cost growth in repeat 2 lands in:

1. **Sub-kernels invoked far more often.** `f_collectionAddComputation_2`
   is invoked 4.6× more in repeat 2 than repeat 1 with no change in
   genome configuration.
2. **Sub-kernels with inflated per-call cost.** `f_collectionProduct
   Computation_5` per-invocation cost jumps 2.7× rep 1 → rep 2 (0.168
   ms → 0.451 ms).

### Interpretation

The compiled loop kernel (`f_loop_X`) is unchanged. What changes is
the orchestration around it: more dispatches of small kernels per
buffer, with each dispatch costing more. Both effects are consistent
with H2a — between repeat 1 and repeat 2, something in JVM-resident
state grew or fragmented in a way that:

- inflated argument-list sizes for `product_5` (more 2.7× per-call),
  which means its argument count grew (more pattern repetitions in
  scope, larger collection arguments, or both);
- forced more `add_2` invocations (4.6×), suggesting more buffers /
  layers / cache entries are participating in the sum on each tick.

Both effects point at structures that survive `pop.destroy()` +
`scene.destroy()` and grow per section. Plausible carriers in
descending order of suspicion:

1. **`PatternSystemManager` / `PatternLayerManager` static or
   long-lived collectors** that retain layer references.
2. **`NoteAudioCache` static caches** that aren't cleared between
   scenes — the per-tick `add_2` 4.6× inflation looks like more cache
   entries being summed.
3. **Hardware kernel/expression cache** holding compiled artefacts
   that include grown argument schemas; the `product_5` per-call
   inflation is consistent with this.
4. **`WaveData` / `AudioProcessingUtils` static accumulators** for
   spectrum/frequency analysis.
5. **`ContextSpecific` registrations** never deregistered.

The 4.6× sub-kernel invocation jump is the largest signal and the
easiest to localise: counting cache sizes / layer counts at the
boundary between repeat 1 and repeat 2 should land on the leak.

### Decisions following Run 5A

- H2a is treated as confirmed. The accumulation is real, repeats
  within a single JVM, occurs at the second clean repeat (not the
  third), and is independent of profile-tree retention (profiles
  saved successfully, not the carrier).
- Run 5B (8m → 16m → 32m sequence in one JVM) is no longer required
  for confirmation but is still useful to map the *threshold*: does
  the carrier saturate after one section, after two, or only after
  several? Defer unless heap analysis alone is inconclusive.
- Run 5C (JMX class-histogram diff between fresh JVM end-of-rep-1 and
  end-of-rep-2) is now the priority. The 4.6× `add_2` invocation jump
  predicts a class with ~4× retained-instance growth between those
  snapshots; that class is the leak.

## Run 5C — JMX heap diff with inter-repeat pause + System.gc()

Configuration: same as Run 5A (multi 32m × 3, all channels) but with
`AR_BENCHMARK_INTER_REPEAT_PAUSE_MS=120000`,
`AR_BENCHMARK_DISABLE_PROFILE=true`. Each repeat is followed by
`System.gc()` plus a 120-second idle window during which a sentinel
file is created so an external JMX client can call
`mcp__ar-jmx__get_class_histogram`. Class histograms were captured at
end-of-rep-0, end-of-rep-1, and end-of-rep-2.

### Timing — H2a did NOT reproduce

Per-repeat tick statistics (multi, 32m, all channels):

| repeat | tick_total_ms | first10 ms | p50 ms | last10 ms | ratio |
|--------|--------------:|-----------:|-------:|----------:|------:|
| 0 (warmup) | 172 822 | 64 | 61 | 61 | 2.71 |
| 1 (warm)   | 113 368 | 33 | 45 | 49 | 1.78 |
| 2          | 108 629 | 37 | 39 | 41 | **1.70** |

**Repeat 2 is *flat*, not 5.6× slower.** Compared to Run 5A:

| | Run 5A rep 2 | Run 5C rep 2 |
|---|---:|---:|
| tick_total | 549 517 ms | 108 629 ms |
| ratio | 8.60 | 1.70 |
| first10 / last10 | 259 / 140 ms | 37 / 41 ms |

The only differences between Run 5A and Run 5C are:

1. Inter-repeat pause: 0 ms (Run 5A) → 120 000 ms (Run 5C).
2. Explicit `System.gc()` at start of each pause.
3. Profile capture: enabled (Run 5A) → disabled (Run 5C).

**Either (1)+(2) or (3) is sufficient to neutralise the slowdown.**
Run 5D (below) will isolate which.

### Heap growth — leakage IS present, but is GC-recoverable

Class histogram diff across repeats (only `org.almostrealism` /
`io.almostrealism` classes shown). Numbers are live-instance counts.

| class | rep 0 | rep 1 | rep 2 | per-rep delta |
|---|---:|---:|---:|---:|
| `io.almostrealism.profile.OperationMetadata` | 4 259 | 7 606 | 10 940 | **+3 300** |
| `io.almostrealism.collect.TraversalPolicy` | 1 923 | 3 037 | 4 044 | +1 060 |
| `org.almostrealism.audio.notes.NoteAudioKey` | 167 | 321 | 462 | +148 |
| `org.almostrealism.audio.notes.NoteAudioProvider$$Lambda` | 167 | 321 | 462 | +148 |
| `io.almostrealism.code.CacheManager$$Lambda/0x…ff890` | 167 | 321 | 462 | +148 |
| `org.almostrealism.collect.CollectionFeatures$3` | 167 | 321 | 462 | +148 |
| `io.almostrealism.code.CachedValue` | 167 | 321 | 462 | +148 |
| `org.almostrealism.collect.computations.ReshapeProducer` | 376 | 530 | 671 | +148 |
| `org.almostrealism.audio.notes.NoteAudioProvider` | 100 | 201 | 300 | +100 |
| `org.almostrealism.audio.data.FileWaveDataProvider` | 100 | 201 | 300 | +100 |
| `org.almostrealism.time.Frequency` | 108 | 216 | 324 | +108 |
| `org.almostrealism.hardware.metal.MetalOperator` | 74 | 111 | 148 | +37 |
| `org.almostrealism.hardware.metal.MetalProgram` | 25 | 42 | 59 | +17 |
| `org.almostrealism.hardware.metal.MetalOperatorMap` | 25 | 42 | 59 | +17 |
| `org.almostrealism.hardware.metal.MTLFunction` | 25 | 42 | 59 | +17 |
| `org.almostrealism.hardware.metal.MTLComputePipelineState` | 49 | 69 | 89 | +20 |

Three growth families are evident:

1. **Audio-note cache cluster** — `NoteAudioKey` / `CacheManager` lambda /
   `NoteAudioProvider` lambda / `CollectionFeatures$3` / `CachedValue`
   all grow at exactly +148 instances per repeat with **identical
   counts**. Identical counts mean they are co-allocated entries of a
   single backing structure; the natural candidate is `NoteAudioCache`
   plus its supplier wrappers (`CacheManager$$Lambda` is a
   memoization wrapper around a `Producer` constructed in
   `CollectionFeatures$3`).
2. **Compiled-kernel cluster** — `MetalProgram` / `MetalOperatorMap` /
   `MTLFunction` / `MetalOperator` / `MTLComputePipelineState`
   grow at +17–37 per repeat. Each represents one cached compiled
   kernel; their growth is the hardware kernel cache holding compiled
   artefacts across `pop.destroy()`.
3. **Computation graph metadata** — `OperationMetadata` (+3 300/rep)
   and `TraversalPolicy` (+1 060/rep) grow much faster. These are the
   per-Computation metadata records and shape descriptors. The
   profile-capture path was disabled in Run 5C, so this growth is
   *not* caused by the profile tree retaining metadata; the metadata
   nodes are themselves retained even with profile off.

`NoteAudioProvider` and `FileWaveDataProvider` instance growth is
+100 per rep, exactly matching the number of distinct samples loaded
by an `AudioScene` (the channel-0 drum kit alone is ~100 sample files
per the loader log). Each scene reload creates a fresh set of
providers; the previous set is retained.

### Reinterpretation of H2a

Run 5A vs Run 5C now decouples two phenomena:

- **Phenomenon X — Inter-section heap growth** is real, occurs in
  every run including Run 5C, and is independent of the slowdown. It
  has clear linear-per-section signatures in five class clusters.
  This is a genuine retention issue but on its own does not produce
  user-visible slowdown for at least the first 3 sections.
- **Phenomenon Y — Per-tick render slowdown at second back-to-back
  long section** appeared in Run 5A but vanished in Run 5C. Y is
  triggered by *some combination* of Run 5A conditions absent in 5C:
  no `System.gc()`, no idle pause, profile capture enabled.

Y is the user-visible regression; X is its substrate. Y likely
requires X to have grown past some threshold, but the trigger for Y
is one of: GC pressure during render that doesn't get a chance to
cycle without the pause, soft-reference cache invalidation when GC
*does* run during render, or profile-tree retention of the entire
graph metadata across reps.

## Run 5D-1 — profile=disabled, pause=0, no `System.gc()`

The cheapest discriminator for phenomenon Y. If Y reproduces here,
profile-tree retention is ruled out and the trigger is GC-related.

### Timing

| repeat | tick_total_ms | ratio | first10 ms | last10 ms | p50 ms |
|--------|--------------:|------:|-----------:|----------:|-------:|
| 0 (warmup) | 107 658 | 1.69 |  39 |  39 |  38 |
| 1          | 325 602 | **5.10** | **199** | **37** | 181 |
| 2          | 154 134 | 2.42 |  54 |  57 |  55 |

### Findings

1. **Phenomenon Y reproduced.** Rep 1 ran 5.10× ratio — comparable to
   Run 5A's rep-2 8.60× — *with profile disabled*. **Profile-tree
   retention is ruled out as the trigger of Y.**
2. **The slowdown is transient.** Within rep 1, first decile is 199
   ms/tick and last decile is 37 ms/tick. The render started slow,
   then recovered to warm-baseline timings before finishing. Run 5A
   had a similar within-rep ramp-down (rep 2: 259 → 140 ms first/last
   decile), though less complete.
3. **The affected rep is stochastic.** In Run 5A the slowdown landed
   on rep 2; in Run 5D-1 it landed on rep 1. Same configuration class,
   different repetition affected. Whichever repetition first crosses
   the GC-pressure threshold takes the hit.
4. **Recovery is partial across reps.** Rep 2's tick is 2.42× warmup
   despite rep 1 having "worked through" a slow period. The warm
   baseline of Run 5C (1.70–1.78) is not restored without explicit
   `System.gc()`.

### Diagnosis of phenomenon Y

The trigger is **young-gen / old-gen pressure crossing the threshold
that forces a major GC cycle during render**. Plausible mechanism:

- Each rep retains ~3 300 `OperationMetadata`, ~1 060 `TraversalPolicy`,
  ~150 `NoteAudioKey` cluster entries, ~17 `MetalProgram` artefacts,
  ~100 `NoteAudioProvider` / `FileWaveDataProvider` instances (per
  Run 5C heap diff).
- After 1–2 reps, old-gen fills with retained graph metadata.
- During the next render, allocation rate of throwaway temporaries
  collides with insufficient young-gen, forcing repeated minor GCs.
  Each minor GC promotes more retained metadata to old gen.
- Eventually a major GC fires: STW pauses lengthen each tick by
  hundreds of ms; once the major GC compacts old gen the ticks
  recover.
- `System.gc()` during the inter-repeat pause pre-empts this by
  forcing the major GC at a known idle moment.

This explains every empirical observation: stochastic timing of the
hit, within-rep recovery, full-cure with explicit GC, partial-cure
without it. It also explains why the inner compiled kernel
(`f_loop_X`) was flat across reps in Run 5A's profile attribution
— the kernel itself wasn't slower, the JVM around it was paused for
GC.

The carrier of phenomenon X (heap retention) is therefore directly
the cause of phenomenon Y. Fixing X fixes Y; mitigating Y without
fixing X (via explicit GC between reps) is a workaround that papers
over the leak. Production `AudioSceneOptimizer` evaluates many more
genomes per JVM than 3, so the workaround would need GC every N reps
or every X bytes — fragile.

### Decision

Skip Run 5D-2/-3/-4. With profile=disabled producing Y, we have all
the discriminator information needed. Move to Run 5E (fix candidates
for X) since fixing X is both the diagnosis-confirming experiment and
the actual production fix.

## Next experiments (revised after Run 5C)

- **Run 5D — isolate the trigger of phenomenon Y.** Run multi 32m × 3
  back-to-back in three configurations:
  1. profile=disabled, pause=0, no `System.gc()` (was: not run)
  2. profile=disabled, pause=120s, with `System.gc()` (was: Run 5C — flat)
  3. profile=enabled, pause=0, no `System.gc()` (was: Run 5A — slow)

  Add a fourth:
  4. profile=enabled, pause=120s, with `System.gc()`.

  If (1) is also slow, the cause is GC pressure / no idle reset
  (independent of profile). If (1) is flat, the cause is profile
  retention.

  Run (1) is the cheapest discriminator and should run first. The
  benchmark already supports the inputs:
  ```
  AR_BENCHMARK_DURATIONS=32 AR_BENCHMARK_REPEATS=3 \
    AR_BENCHMARK_RUN_SINGLE=false \
    AR_BENCHMARK_DISABLE_PROFILE=true \
    AR_BENCHMARK_INTER_REPEAT_PAUSE_MS=0 \
    AR_BENCHMARK_REPORT=results/audio-scene-benchmark-run5d-1.csv \
    studio/compose/scripts/run-optimizer-benchmark.sh
  ```

- **Run 5E — fix candidates for phenomenon X.** Independent of Y.
  Three patches to test:
  1. Drain `NoteAudioProvider.audioCache` between sections in
     `AudioSceneBenchmark` (the static `CacheManager<PackedCollection>`
     identified below).
  2. Clear `Hardware.getLocalHardware()` kernel cache between
     sections.
  3. Null out scene-local `NoteAudioProvider` lists.

  Each fix should drop the corresponding family's per-rep delta in
  the heap diff to ≤5 instances per rep.

### Suspect carriers identified

`NoteAudioProvider.java:65` declares a **static** cache:

```java
private static final CacheManager<PackedCollection> audioCache = new CacheManager<>();
```

Configured with `maxCachedEntries(audioCache, 200)` as access-listener
eviction (`NoteAudioProvider.java:69`). Every `NoteAudioProvider`
caches its resampled audio here at line 224:

```java
notes.put(key, c(getShape(key), audioCache.get(computeAudio(key).get())));
```

`CacheManager.get(source)` (`base/code/src/main/java/io/almostrealism/code/CacheManager.java:170`)
wraps the source in a `CachedValue` and stores it in an internal
`HashMap<CachedValue<T>, Long>`. The wrapping lambda captures `source`
strongly. `source` is the result of `computeAudio(key).get()`, which
captures `this` (the `NoteAudioProvider` instance) by lambda capture.

Eviction via `maxCachedEntries` calls `cv.clear()` on the oldest
entries but does **not** remove them from the HashMap; entries are
filtered out of `getCachedOrdered()` by `CachedValue::isAvailable`,
not deleted. Therefore:

- The internal HashMap grows without bound across the JVM lifetime.
- Even after `cv.clear()`, the captured source Evaluable (and
  through it the `NoteAudioProvider`) remains strongly reachable from
  the HashMap key, since the key is the `CachedValue` itself.
- This explains the 300 `NoteAudioProvider` instances retained at
  end of rep 2 in Run 5C despite the 200-entry "bound": the bound
  applies to *available* entries, not HashMap entries, and the
  HashMap retains references regardless.

`CacheManager` exposes no public clear-all method; the only eviction
is via `maxCachedEntries`, which doesn't free the HashMap entries.

The Metal kernel cluster (`MetalProgram`, `MetalOperatorMap`,
`MTLFunction`, `MTLComputePipelineState`) grows ~17 / rep. Each new
compiled kernel is created via
`MetalComputeContext.java:185` (`new MetalOperatorMap(...)`). The
retention path needs separate investigation but is a smaller
contributor to total heap pressure.

`OperationMetadata` (+3 300 / rep) and `TraversalPolicy` (+1 060 / rep)
are the largest growth sources by instance count. These belong to
`Computation` graph nodes, which should die with the scene; the
retention path needs investigation. The static `audioCache` HashMap
is one likely transitive root: each cached `Evaluable` holds the
operation graph that produced its source audio, which transitively
holds metadata for every Computation in that graph.

### Run 5E-1 — Negative result (audio-cache drain alone)

`run5e-1d` (durations=8m, repeats=3, profile=disabled, pause=0,
`AR_BENCHMARK_CLEAR_AUDIO_CACHE=true` calling
`NoteAudioProvider.clearCache()` → `CacheManager.dropAll()` between
reps):

| repeat | ratio | first10 ms | last10 ms | p50 |
|--------|------:|-----------:|----------:|----:|
| 0 (warmup) | 2.22 |  53 |  50 |  49 |
| 1          | **8.66** | 201 | 199 | 198 |

Compared to Run 5D-1 (no cache drain) at 32m repeats, the rep-1
ratios are similar (5.10 → 8.66). Cache draining does not prevent Y.
At minimum, the audio cache is not the *only* carrier; more likely
the relief Run 5C measured was the explicit `System.gc()` rather
than the cache eviction it caused via the access-listener.

A first revision tried `v.clear()` per cache entry (invoking the
destroy consumer); this destroyed `PackedCollection`s shared via
delegate chains and caused the JVM to throw on the next render.
`CacheManager.dropAll()` was added as the safer "drop strong
references only, let GC release native memory" variant.

### Run 5E-2 — `System.gc()` between reps (no cache drain) — PASSES

Configuration: profile=disabled, no cache drain, 2-second pause +
`System.gc()` between repeats. Same total wall-clock budget as
Run 5D-1 plus 4 seconds of idle and 3 GC invocations.

| repeat | tick_total ms | ratio | first10 ms | last10 ms |
|--------|--------------:|------:|-----------:|----------:|
| 0 (warmup) | 158 188 | 2.49 |  60 |  54 |
| 1          | 108 153 | **1.70** |  35 |  42 |
| 2          | 123 249 | **1.93** |  37 |  51 |

Identical timing profile to Run 5C (which used a 120-second pause).
**The carrier is GC pressure relievable by an explicit `System.gc()`,
and the 118-second additional idle time was unnecessary.**

### Production fix recommendation

Insert `System.gc()` at the per-genome boundary in
`AudioScenePopulation`. Both production paths converge on this
class:

- The optimizer's `PopulationOptimizer.orderByHealth()` submits one
  `HealthCallable` per genome with `pop::disableGenome` as the
  cleanup callback; `enableGenome(int)` is called as setup.
- `AudioScenePopulation.generate(channel, frames, ...)` (the path
  used by ringsdesktop previews) loops over genomes and calls the
  same `enableGenome(int)` / `disableGenome()` pair.

`disableGenome()` is **not** the right hook because
`init(Genome, MultiChannelAudioOutput, List<Integer>, int)` (line 147)
and `validateGenome` (line 220) also call it during setup, where
firing GC interrupts JIT compilation and prematurely promotes setup
state to old gen. `enableGenome(int)` is only invoked from the two
production iteration loops (optimizer and `generate()`), so it is
the safe place.

**The fix:** add `System.gc()` at the start of
`AudioScenePopulation.enableGenome(int)`, gated by a static
`gcBeforeGenome` boolean (default `true`) so tests / unusual drivers
can opt out. Cost: a single GC pause per genome evaluation. At ~1s
per major GC and tens of seconds to minutes per render, overhead
is negligible.

### Heap size context

Every benchmark run in this document used `-Xmx8g` (the
`AR_BENCHMARK_HEAP` default in `run-optimizer-benchmark.sh`). For
comparison, the production ringsdesktop bundles set:

| target | `Xmx` |
|--|--:|
| `rings-appbundle-intel/pom.xml` | 10 GB |
| `rings-appbundle-arm/pom.xml`   |  2 GB |

The benchmark sits closer to the intel bundle. The arm bundle, at
2 GB, has a much tighter old-gen budget, which means accumulated
retained state crosses the major-GC pressure threshold sooner —
phenomenon Y likely manifests more frequently per genome on arm
than the benchmark captures. The `System.gc()` fix is therefore
expected to deliver *more* benefit on arm than the 2.4× the
benchmark measured at 8 GB. If reproducibility on arm becomes
relevant, set `AR_BENCHMARK_HEAP=2g` and rerun the controlled
pair.

### Validation in the production lifecycle

Added `AR_BENCHMARK_REUSE_SCENE=true` to `AudioSceneBenchmark`
(via the new `runReuseSceneMulti` method). With this toggle the
benchmark builds one scene + one population once and evaluates N
genomes against them — exactly mirroring the optimizer's
`HealthCallable` loop and `AudioScenePopulation.generate()`.

A controlled pair, both 8m × 4 reps in reuse-scene mode, profile
disabled, no inter-repeat pause:

| | Run 5E-7 (`gcBeforeGenome=true`) | Run 5E-8 (`gcBeforeGenome=false`) |
|--|--:|--:|
| rep 0 ratio | 11.98 |  2.97 |
| rep 1 ratio |  2.86 | 13.23 |
| rep 2 ratio |  4.38 |  7.63 |
| rep 3 ratio |  2.04 |  1.63 |
| Σ tick reps 1-3 |  147 s |  358 s |
| avg ratio reps 1-3 |  3.09 |  7.51 |

The fix yields a **2.4× improvement in average render time** across
the post-warmup reps. Both runs still contain one elevated rep
(5E-7 rep 0; 5E-8 rep 1) attributable to genome-stochastic
warm-up cost — Y itself, in the per-tick-ramp-within-rep sense,
no longer manifests with the fix; without it, individual reps
still incur the major-GC penalty for an entire render.

The recreate-per-rep benchmark pattern (`runMultiChannel`,
the default) does **not** exercise the production fix: my
`enableGenome(int)` GC fires *after* the rep's new scene/pop are
already initialised, leaving newly-allocated setup state on the
heap. Run 5E-5 (`pause=0`, `gcBeforeGenome=true`, recreate
mode) showed rep 1 ratio 4.78 in that lifecycle, confirming the
fix is targeted at the reuse-scene pattern — which is the actual
production lifecycle.

### Phenomenon X is still real but no longer the user-visible problem

Heap retention (Run 5C diff) continues to occur every section even
with `System.gc()` between iterations. `System.gc()` clears the
soft-reachable / pending-finalizer slack that drives the render-time
slowdown but does not collect strongly-reachable retained graph
metadata. Over a thousand-genome optimizer run that retention will
eventually exhaust available heap. The full cleanup work (NoteAudio
cache drain that does *not* destroy delegate-shared values, kernel
cache lifecycle, `OperationMetadata` retention path) remains as a
separate cleanup task but is not blocking the immediate performance
problem.

### Implementation plan for the original Run 5E-1 (kept for reference)

Two minimal additive changes:

1. Add `public static void clearAll()` to `CacheManager` that empties
   the internal HashMap (after invoking the configured `clear`
   consumer on each value, to release native memory).
2. Add `public static void clearAudioCache()` to `NoteAudioProvider`
   that delegates to `audioCache.clearAll()`.

Then call `NoteAudioProvider.clearAudioCache()` from
`AudioSceneBenchmark.runMultiChannel`'s `finally` block, immediately
after `scene.destroy()`. Re-run multi 32m × 3 with profile=disabled,
pause=0, no `System.gc()` (Run 5D-1 conditions). Measure:

- Per-rep tick statistics: phenomenon Y should be neutralised if the
  audio cache was the dominant carrier. Partial neutralisation (rep 2
  ratio between Run 5C's 1.70 and Run 5D-1's 5.10) means it's a
  contributor but not the sole carrier.
- Heap-diff over reps with the inter-repeat pause re-enabled (so
  snapshots can be taken). The `NoteAudioKey` / `NoteAudioProvider` /
  `FileWaveDataProvider` clusters should drop to ≤5 instances/rep
  delta. If `OperationMetadata` and `TraversalPolicy` also collapse,
  the audio cache was the transitive root for those too.

## Followups

- After A/B identify the locus, write a focused micro-benchmark inside the
  responsible component (`PatternSystemManager`, `MixdownManager`, etc.)
  for unit-level confirmation.
- Fix candidates can then be measured with the same harness; report-to-report
  comparison is straightforward because the schema is stable.
