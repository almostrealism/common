---
status: proposed
created: 2026-11-25
closed:
tracker: [f155a689-cf76-4d72-8007-17d61f6a1b21]
---

# True stereo and per-channel pan in the PDSL mixdown

**Tracker task:** `f155a689-cf76-4d72-8007-17d61f6a1b21` — *True stereo in the PDSL mixdown*
**Release:** Common 0.78 (part of the realtime AudioScene umbrella `3472d41a`)

## Confirmation first: is it already done?

The owner's note asks for this to be checked before anything else. It is **not** done on
current master (`792e9a508`, which this branch matches):

- **No pan anywhere.** No `pan` appears in any `pdsl/audio/*.pdsl`
  (`engine/ml/src/main/resources/pdsl/audio/`, or the test resources under
  `studio/compose/src/test/resources/pdsl/audio/`), and no pan concept exists in Java
  under `studio/` or `engine/audio`. That covers the legacy `CellList` path too.
- **The sink renders one mono master.** `AudioSceneRealtimeRunner.createPdsl` passes only
  the first `2N` rows of the consolidated render buffer (LEFT-MAIN, LEFT-WET) to
  `mixdown_master_wet`, which outputs `[1, signal_size]`. It then pushes that one frame to
  both stereo writers through `MultiChannelAudioOutput.pushMaster`, which sends the same
  producer to every `StereoChannel`. The RIGHT-side pattern audio is rendered into rows
  `[2N, 4N)` and never read. The javadoc on `createPdsl` says so ("The path writes a mono
  master (the LEFT writer) duplicated to both stereo channels; true stereo (per-channel PAN
  in the PDSL mixdown) is outstanding").
- **The docs agree.** In `docs/internals/pdsl-audio-dsp.md`, under "Known remaining
  differences": "The master is dual-mono: one rendered master streams to both stereo
  writers, and there is no pan."
- **No test covers it.** No test hard-pans a channel or checks an L/R energy split.
  `AudioScenePdslCutoverTest.renderBothPaths` compares the two paths but checks only
  that the output is finite and non-silent, plus windowed RMS.

The task description is slightly wrong about one thing: the sink does not "render each
side separately". It renders the LEFT side only and copies it. The legacy `CellList` path
is the one that renders each side separately, with a full `MixdownManager.createCells`
graph per `StereoChannel` (`AudioScene.getCells` → `cells(LEFT…, RIGHT…)`).

## Motivation

The PDSL mixdown is the default (`MixdownManager.enablePdslMixdown`). It throws away the
right channel of every stereo sample: the RIGHT-side pattern audio is rendered every
buffer and then ignored. As a result:

- the default path is a regression from the legacy path, which kept stereo samples in
  stereo;
- the realtime runner pays for a RIGHT-side render it never uses (this was kept on
  purpose, so that true stereo could later be added without changing the render side);
- there is no per-channel placement in the stereo field at all, on either path.

## Scope

1. **Stereo mixdown layers.** Add stereo versions of `mixdown_master_wet` (efx on) and
   `mixdown_master` (efx off) in `mixdown_manager.pdsl`. Each reads both sides of the
   consolidated buffer and produces `[2, signal_size]` (row 0 = LEFT, row 1 = RIGHT) in
   **one forward pass**, with per-channel pan.
2. **Per-channel pan parameter.** Store a pan value per scene channel in `[-1, 1]`
   (default `0` = centre) on `MixdownManager`, persist it with the other per-channel mixdown
   settings in `AudioSceneLoader.Settings`, and deliver it to the compiled graph through
   `MixdownManagerPdslAdapter`.
3. **Stereo sink.** `AudioSceneRealtimeRunner.createPdsl` runs one forward over all
   `4N` (or `2N` with efx off) rows, pushes row 0 to the LEFT master writer and row 1 to
   the RIGHT one, and pushes per-side measures and stems.
4. **Tests.** A hard-pan L/R energy-split test, and a centre-pan parity test against the
   current per-side mono layer.
5. **Docs.** Remove the dual-mono line from `docs/internals/pdsl-audio-dsp.md` and
   describe the pan law. Update the `createPdsl` javadoc.

Out of scope: adding pan to the legacy `CellList` path (see the open questions), a pan
gene in the genome, and per-frame pan automation.

## Approach

### Pan law

Use a **balance law with unity at centre**: `gL(p) = min(1, 1 − p)` and
`gR(p) = min(1, 1 + p)`. At `p = 0` both gains are exactly 1, so a centred stereo mix
matches today's per-side processing and the legacy `CellList` path. The existing parity
tests therefore keep their meaning: with every channel centred, the stereo layer's LEFT row
must equal what `mixdown_master_wet` produces on the LEFT-side rows today. At `p = −1` the
RIGHT gain is 0. Pan acts per side on each channel's own L/R pattern audio, which suits
stereo samples (a mono sample rendered to both sides behaves like a normal panner, without
the centre boost of a constant-power law). The choice of law is open question 1.

### Layer structure: sides as rows, not duplicated text

`mixdown_master_wet` is about 250 lines of DSP with named ring-buffer state. Copying it once
per side would duplicate all of that, and calling a shared side sub-layer twice runs into
the documented limit that a user-layer call infers its input shape from its `-> [shape]`
annotation (`PdslInterpreter.declaredInputShape`). Instead, the stereo layer processes
**`2N` rows** (both sides of every channel) through the same stages:

- **Gather the voicings.** The input is `[4N, S]` in
  `[L-MAIN, L-WET, R-MAIN, R-WET]` order. `reshape(2, 2, N, S)` → `permute` to
  `(voicing, side, N, S)` → flatten, then `slice` the first half for MAIN and the second
  half for WET. Each comes out as `[2N, S]` in side-major order. The consolidated layout
  and `AudioScene.prepareRenderBuffers` / `getCells` stay as they are. With efx off the
  input is `[2N, S]` = `[L-MAIN, R-MAIN]`, already in this order.
- **Per-channel stages** (`clip`, `fir(hp_coeffs[channel])`, `ramp_scale`, the efx apply
  stage, `capture(stem_channels)`) run unchanged over `2N` rows. The adapter supplies the
  per-channel arrays tiled twice (`[2N, …]`). Gene reads stay per scene channel, so both
  sides of a channel get the same values.
- **Pan and side sum in one step.** Where the mono layer collapses channels with
  `sum_channels()`, the stereo layer uses `route(pan_matrix)` with a `[2, 2N]` matrix:
  `pan_matrix[s][s·N + c] = g_s(pan_c)`, zero elsewhere. `route` already accepts
  rectangular matrices (`mixdown_efx_bus_rectangular` exercises this).
- **Shared networks become block-diagonal.** The per-channel echo matrix
  (`efx_fb_transmission`, `[2N, 2N]`, already diagonal), the bus-line network
  (`bus_send` `[2N, 2L]` with the pan gains folded in, `bus_transmission` and `bus_wet_out`
  `[2L, 2L]`, delays `[2L]`) and the reverb (`repeat_each` to `2T` taps, `[2T, 2T]`
  feedback, then a `[2, 2T]` side-sum `route` in place of `sum_channels` + mean) each get
  one independent copy per side. This reproduces the legacy behaviour, where each side has
  its own complete mixdown graph, without cross-side leakage. The adapter builds the
  block-diagonal matrices from the existing per-side ones (`householderMatrix`,
  `reverbTapDelays`, etc.), and the ring state (`fb_*`, `main_fb_*`, `bus_*`, `reverb_*`)
  doubles in size.
- **Sends are post-pan.** A channel's efx-bus and reverb sends take the same side gain as
  its dry signal, as on a normal mixer, so a hard-left channel reaches only the left
  networks.
- **The master tail** (`clip`, `fir(lp_coeffs)`, `scale(master_gain)`, `clip`) is
  element-wise over `[2, S]`. If `fir` with a single coefficient row does not apply
  row-wise to a 2-row input, wrap it in `for each channel` with a tiled `[2, taps]`
  coefficient slot.
- **Stems.** `stem_channels` becomes `[2N, S]` (side-major); `stem_efx` and `stem_reverb`
  become `[2, S]`.

The cost per forward roughly doubles compared with today's LEFT-only forward. This is the
2x that the `createPdsl` javadoc already budgets for, and it is still one forward per buffer.
`PdslHotPathBreakdownTest` and `MixdownLayerPerformanceTest` should confirm the realtime
budget still holds at `AudioScene.DEFAULT_REALTIME_BUFFER_SIZE`.

### Where the new behaviour lives

- `engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl`: the two stereo
  layers (`mixdown_master_stereo`, `mixdown_master_wet_stereo`). The mono layers stay,
  because the mixdown unit tests use them and the centre-parity test needs them as its
  reference.
- `studio/compose/.../arrange/MixdownManager.java`: per-channel pan storage
  (`getPan(int)` / `setPan(int, double)`, clamped to `[-1, 1]`), held in a
  `PackedCollection` slot so a realtime client can change pan between buffers. This sits
  next to `getReverbChannels`, the existing per-channel mixdown setting.
- `studio/compose/.../AudioSceneLoader.java`: persist pan in `Settings` next to
  `reverbChannels`. Missing values mean centre, so existing scene files load unchanged.
- `studio/compose/.../arrange/MixdownManagerPdslAdapter.java`: a stereo mode that tiles
  per-channel arrays, builds the block-diagonal matrices and doubled state, and refreshes
  `pan_matrix` (and the pan-folded `bus_send`) in `automationRefresh`, once per buffer
  like the other slots. Pan is **stepped per buffer**, not ramped within it: the
  `pan_matrix` is a `route` matrix, applied as a single constant matrix per forward,
  so it holds one pan value for the whole buffer rather than interpolating `_prev →
  current` across frames the way the `ramp_scale` slots (`volume`, `efx_automation`) do.
  That is acceptable because pan changes are user-driven and infrequent; smoothing a pan
  jump across the buffer is open question 5, and per-frame pan automation is out of scope.
- `studio/compose/.../health/MultiChannelAudioOutput.java`: per-side push methods, for
  example `pushMaster(Function<StereoChannel, Producer<PackedCollection>>)` and the
  same for measures and stems. They reuse the existing private `pushToStereo`, which
  becomes per-side instead of always pushing the same frame.
- `studio/compose/.../AudioSceneRealtimeRunner.java`: `createPdsl` selects the stereo
  layer, feeds the whole consolidated region (`4N` or `2N` rows) as a single zero-copy
  view at offset 0, and builds per-side frame producers
  (`c(shape(1), p(masterOutput), side * bufferSize + frameIndex)`) for master, measures
  and stems. The mono-duplication path and its javadoc are removed.

## Success criteria

- **Hard-pan energy split** (new test in `studio/compose/src/test/java/.../ml/test/`,
  next to `MixdownManagerPdslTest`): a synthetic multi-channel input where channel 0 has
  identical non-zero L/R content, every other channel is silent, and channel 0 has
  `pan = −1`. The stereo layer's RIGHT-row RMS is 0 (or below 1e-6 of the left), and the
  LEFT row is non-silent. `pan = +1` mirrors this. At `pan = 0` the two rows have equal
  RMS. Each case runs with efx on and off, and with reverb enabled, to show that the sends
  are panned too.
- **Centre parity:** with every channel at `pan = 0`, the stereo layer's row `s` matches
  the existing mono layer run on side `s`'s rows within floating-point tolerance, for both
  sides and for both layers.
- **End-to-end:** an `AudioScenePdslCutoverTest`-style render (an extension of
  `pdslVsCellListPlumbingSmoke`, or a new method) through `runnerRealTime` writes a stereo
  WAV whose two channels are not identical when the source samples are stereo, and shows
  the energy split when a channel is hard-panned.
- **Existing parity tests still pass:** `AudioScenePdslCutoverTest`
  (`pdslVsCellListPlumbingSmoke`, `pdslVsCellListSingleChannel`),
  `MixdownManagerPdslTest`, `MixdownAdapterConstantParityTest`,
  `MixdownManagerPdslVerificationTest`, `RenderAheadLifecycleTest`, `GenerateAudioFileTest`.
- The build validator (checkstyle, code_policy, test_timeouts, duplicate_code) is clean.

## Dependencies

None blocking: the task has no `blocked_by` entries, and the primitives it needs
(`permute`, `slice`, rectangular `route`, `repeat_each`, `concat_blocks`) are already in
the PDSL builtins (`PdslBuiltins`) and the audio primitives (`AudioDspPrimitives`).

No settings file is written for this branch. The tests run on the CPU/OpenCL backends as
well as Metal, so no specific machine is needed.

## Open questions

1. **Pan law.** This plan proposes a balance law with unity at centre, which keeps centred
   mixes identical to the legacy path. The alternative is a constant-power law (−3 dB at
   centre, or +3 dB at the extremes if normalised to unity at centre). That breaks parity
   with the `CellList` path at default settings, and for stereo sources it needs a decision
   on whether hard-panning folds the far side in or drops it.
2. **Where pan comes from.** This plan adds a per-channel scene setting on
   `MixdownManager`, persisted with the scene, default centre. Making pan a genome
   chromosome would change the genome layout and the optimizer, and would be a separate
   task.
3. **The legacy `CellList` path.** It has no pan either. Should pan also apply there, as a
   per-side gain before its per-side mixdown graph, so the paths can still be A/B tested
   with a non-centred pan? This plan leaves it out; the cutover A/B comparisons use centred
   pans.
4. **Size.** This is one coherent change, but it is medium-to-large. If a reviewer prefers
   smaller pieces, split it into (a) stereo layers plus the adapter's stereo mode, with the
   centre-parity test (no pan yet), and (b) the pan setting, the `pan_matrix`, the sink
   wiring and the hard-pan test.
5. **Stepping pan per buffer vs smoothing the jump.** Because the `pan_matrix` is a `route`
   matrix applied once per forward, pan is held constant for the whole buffer and changes
   discontinuously at buffer boundaries. A large pan jump could therefore produce an audible
   click (zipper noise) at a buffer edge. This plan accepts that, on the grounds that pan is
   user-driven and infrequent. If it proves audible, the fix is not per-frame automation
   (out of scope) but smoothing the step — for example by crossfading two `route` passes at
   the old and new pan over the buffer. That is a later refinement, not part of this change.

## Estimated complexity

**Medium–large.** Most of the effort is in the adapter (tiling per-channel arrays,
block-diagonal matrices, doubled state, per-buffer pan refresh) and in verifying the
two new stereo layers against the mono ones. The sink and settings changes are small.
