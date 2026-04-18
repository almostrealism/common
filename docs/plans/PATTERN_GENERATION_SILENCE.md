# Pattern Generation Silence — Investigation & Migration Plan

## Status: In Progress

The AudioSceneOptimizer (and by extension the desktop app's pattern generation via
`ChannelPatternController`) produces completely silent WAV output. Two layers of the
problem have been identified; the first is fixed, the second is under investigation.

---

## Layer 1: Cursor-sync error (FIXED)

### Problem
`AudioScene.getCells(output)` in offline mode called `setTickLoopCount(bufferSize)` where
`bufferSize` was the full 230-second duration (10,143,000 frames). This compiled the entire
render into a single native `Loop` guarded by an `executed[0]` boolean (one-shot). When
`StableDurationHealthComputation` iterated in 22,050-frame batches, the first `tick.run()`
burned through all 10M frames, and the cursor checkpoint (`fc = l + iter - 1`) immediately
failed: `10142999 != 22049`.

Introduced by commit `0e78fede4` on `feature/audio-loop-performance` (Feb 2026).

### Fix (already applied)
Migrated `AudioScenePopulation` and `StableDurationHealthComputation` from the offline
`scene.runner()` path to `scene.runnerRealTime()`:

- **`AudioScenePopulation.java`** — new `init(genome, output, channels, bufferSize)` overload
  uses `scene.runnerRealTime(output, channels, bufferSize)`. `DEFAULT_BUFFER_SIZE = sampleRate/2`.
  `generate()` now drives `ceil(frames / bufferSize)` explicit buffer ticks instead of
  `cells.iter(frames, false)`.
- **`StableDurationHealthComputation.java`** — dropped `TemporalRunner` wrapping entirely.
  Stores compiled `targetSetup` / `targetTick` runnables from `target.setup().get()` /
  `target.tick().get()` directly. Each `targetTick.run()` advances exactly `iter` frames.
  New `getBatchSize()` accessor.
- **`AudioSceneOptimizer.java`** — reads `health.getBatchSize()` and passes it to
  `population.init(...)` so the runner's bufferSize matches the health loop's iter.

After this fix, the cursor-sync check passes and WAV files are written.

---

## Layer 2: Silent audio output (UNDER INVESTIGATION)

### Symptoms
- WAV files are structurally valid (RIFF/WAVE, 24-bit stereo 44.1 kHz, ~60 MB each)
- Every sample in every file is zero
- 550 pattern elements are generated (genome is active)
- 31/31 TreeNoteSources have providers with valid trees
- WaveOutput cursor advances correctly (88199 after 4 buffers)
- ~304 "Note evaluation failed" warnings appear, but only at bar boundaries (every 88,200
  frames = 2 seconds at 120 BPM)

### What has been ruled out

1. **Provider resolution is healthy.** A diagnostic test (`OptimizerSceneDiagnosticTest
   #providerResolution`) loads pattern-factory.json with MigrationClassLoader, points
   TreeNoteSources at `~/Music/Samples`, and reports: 4,038 providers resolved, 4,035 load
   successfully, only 3 fail (malformed/unsupported WAV headers).

2. **Library wiring is correct.** After `scene.setLibraryRoot()` + `scene.setSettings()`,
   all 31 TreeNoteSources still have their tree and return non-empty provider lists.

3. **Cursor advancement is correct.** Real-time runner advances `bufferSize` frames per tick,
   matching the health loop's expectation.

### Where the signal is lost

The silence is between the pattern-sum layer (which produces audio per-note) and the
WaveOutput (which receives zeros). The real-time runner's per-buffer cycle is:

1. `prepareBatch()` — calls `PatternSystemManager.sum()` which renders patterns into
   `PatternAudioBuffer.outputBuffer`
2. Compiled loop — reads from output buffers via WaveCell and pushes through the cell graph
3. Cell graph pushes audio to WaveOutput writers

Something in steps 1-3 is producing or passing zeros. The investigation was narrowing to
step 1 (direct `PatternLayerManager.sum()` call outside the cell graph) when it was handed
off.

### Next diagnostic step

A test method `OptimizerSceneDiagnosticTest#directPatternSum` has been written but not yet
run. It:
1. Creates the optimizer scene exactly (loadChoices, setLibraryRoot, setSettings, assignGenome)
2. Calls `PatternLayerManager.sum()` directly on a fresh `PackedCollection` destination
3. Checks the destination for non-zero samples

If `directPatternSum` produces audio, the problem is in the cell graph wiring (step 2-3).
If it produces silence, the problem is in the pattern sum / note evaluation layer (step 1).

The test file is at:
```
common/studio/compose/src/test/java/org/almostrealism/studio/pattern/test/OptimizerSceneDiagnosticTest.java
```

Run with:
```bash
cd common/studio/compose
mkdir -p results/logs
mvn test -Dtest='OptimizerSceneDiagnosticTest#directPatternSum' \
    -DskipTests=false -DAR_RINGS_LIBRARY=$HOME/Music/Samples
```

### Hypothesis: "Note evaluation failed" at bar boundaries

The 304 failures all say `"original" is null` — meaning `WaveDataProviderAdapter.get()`
returned null for that specific note evaluation. Since the provider-resolution test shows
providers load fine in isolation, the null likely occurs because:
- The note's WaveDataProvider is a `DelegateWaveDataProvider` wrapping a root provider, and
  the delegate's `get()` returns null when the root hasn't been loaded in the current
  hardware context yet
- Or the note audio cache eviction (`CacheManager.maxCachedEntries(audioCache, 200)`) is
  clearing entries between bar boundaries

These 304 failures only account for ~8% of notes. The remaining 92% should succeed. If they
do but the output is still silent, the problem is downstream of note evaluation.

---

## Files modified

### Production code (in `common`)
- `studio/compose/src/main/java/org/almostrealism/studio/optimize/AudioScenePopulation.java`
- `studio/compose/src/main/java/org/almostrealism/studio/health/StableDurationHealthComputation.java`
- `studio/compose/src/main/java/org/almostrealism/studio/optimize/AudioSceneOptimizer.java`

### Test code (in `common`)
- `studio/compose/src/test/java/org/almostrealism/studio/pattern/test/OptimizerSceneDiagnosticTest.java` (new)

### Artifacts (in `ringsdesktop`)
- `.audioscene-run/` — run script, log, pattern-factory copy, 8 silent WAV files in `health/`
- `pattern-factory.json` and `scene-settings.json` copied to `common/studio/compose/` for
  test convenience (not committed)

---

## Follow-up work not yet started

1. **Migrate remaining offline callers** — `AudioSceneMultiGenomeTest`,
   `AudioSceneBaselineTest`, `RealTimeTestHelper.renderTraditional` still call
   `scene.runner(output)` / `scene.getCells(output)` (the broken offline path).
2. **Remove dead offline loop code** — `AudioScene.getCells` still applies
   `setTickLoopCount(bufferSize)` when `waveCellFrame == null`. Nothing in production
   exercises this path after the migration, but it remains live code.
3. **Desktop app integration** — `ChannelPatternController.generate()` uses
   `AudioScenePopulation.generate()` which was updated in this migration. Once the silence
   bug is resolved, the desktop app's pattern generation should work via the same real-time
   path. Needs manual verification.
