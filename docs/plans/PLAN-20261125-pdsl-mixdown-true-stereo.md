---
status: completed
created: 2026-11-25
closed: 2026-11-25
tracker: [f155a689-cf76-4d72-8007-17d61f6a1b21]
---

> **Implemented.** True stereo with per-channel pan now ships in the PDSL mixdown. The
> implementation **diverged from the single-forward, block-diagonal design** sketched below:
> rather than one forward over `2N` rows with block-diagonal matrices and two new stereo PDSL
> layers, each stereo side is rendered by its **own forward over that side's voicing rows**,
> reusing the existing `mixdown_master` / `mixdown_master_wet` layers unchanged, with its own ring
> state — exactly the legacy "complete mixdown graph per side" behaviour the plan calls faithful.
> Per-channel pan (the balance law `gL = min(1, 1 − p)`, `gR = min(1, 1 + p)`) is folded by
> `MixdownManagerPdslAdapter` into each side's dry volume and its sends, so the sends are post-pan.
> This was chosen over the block-diagonal design because it needs no new PDSL and no ~300-line
> matrix machinery in an adapter already at its length limit, while meeting every goal and success
> criterion below; the two forwards cost the ~2x the plan's own `createPdsl` javadoc named as the
> accepted price once pan exists. The one-forward optimisation remains available as a later
> refinement. See `MixdownManagerPdslStereoPanTest` and `AudioScenePdslCutoverTest`.

# True stereo and per-channel pan in the PDSL mixdown

**Tracker task:** `f155a689-cf76-4d72-8007-17d61f6a1b21` — *True stereo in the PDSL mixdown*
**Release:** the version named in the root `pom.xml` (part of the realtime AudioScene umbrella)

## Confirmation first: is it already done?

The owner's note asks for this to be checked before anything else. It is **not** done on
current master (re-checked at `a7fa8c16d`, which this branch is up to date with):

- **No pan in the mixdown.** No `pan` appears in any `pdsl/audio/*.pdsl`
  (`engine/ml/src/main/resources/pdsl/audio/`, or the test resources under
  `studio/compose/src/test/resources/pdsl/audio/`), and `MixdownManager` has no pan
  gene, field or setting. Its `initRanges` javadoc still lists "volume, pan, delay,
  and filter", but the method sets ranges only for volume, wet-in, transmission,
  wet-out, delay, delay-dynamics and the wet filter — there is no pan range, so that
  javadoc word is aspirational and nothing is wired. That covers the legacy `CellList`
  path too. The only `pan` in `engine/audio` is unrelated to mixdown placement — MIDI
  CC 10 (`MidiInputListener.PAN`) and the synth `ModulationSlot.PAN` — neither reaches
  the mixdown's stereo field.
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

`mixdown_master_wet` is about 190 lines of DSP with named ring-buffer state. Copying it once
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
  `sum_channels()`, the stereo layer uses `route(pan_matrix)` with a `[2N, 2]` matrix:
  `pan_matrix[s·N + c][s] = g_s(pan_c)`, zero elsewhere. `route` takes its matrix as
  `[in, out]` — `AudioDspPrimitives.dispatchRoute` requires the first axis to equal the
  upstream row count, and the second axis becomes the downstream row count — so the
  matrix is `[2N, 2]`, not `[2, 2N]`. Rectangular matrices are already accepted
  (`mixdown_efx_bus_rectangular` exercises this).
- **Every `sum_channels()` in `mixdown_master_wet` changes.** The layer has four, and
  each needs a side-aware replacement, or it silently collapses both sides into one row:
  1. MAIN arm, after `capture(stem_channels)`: `route(pan_matrix)` (`[2N, 2]`).
  2. Efx bus, after `feedback(...)`: a constant `[2L, 2]` side-sum matrix (ones in each
     side's `L` lines). The pan is already in `bus_send`, so this sum carries no pan.
  3. Reverb send, after the per-channel `ramp_scale(reverb_send…)` and before `repeat`:
     a pan-weighted `[2N, 2]` route. This is where the reverb send becomes post-pan.
     `repeat(reverb_taps)` then becomes `repeat_each(reverb_taps)`, which turns `[2, S]`
     into side-major `[2T, S]` (`repeat` would interleave the sides).
  4. Reverb taps, after `delay_network(...)`: a constant `[2T, 2]` side-sum route,
     followed by the existing `scale(reverb_tap_mean)`.
- **Shared networks become block-diagonal.** The per-channel echo matrix
  (`efx_fb_transmission`, `[2N, 2N]`, already diagonal), the bus-line network
  (`bus_send` `[2N, 2L]` with the pan gains folded in, `bus_transmission` and `bus_wet_out`
  `[2L, 2L]`, delays `[2L]`) and the reverb (`repeat_each` to `2T` taps, `[2T, 2T]`
  feedback, then the `[2T, 2]` side-sum `route` and the existing mean) each get
  one independent copy per side. This reproduces the legacy behaviour, where each side has
  its own complete mixdown graph, without cross-side leakage. The adapter builds the
  block-diagonal matrices from the existing per-side ones (`householderMatrix`,
  `reverbTapDelays`, etc.), and the ring state (`fb_*`, `main_fb_*`, `bus_*`, `reverb_*`)
  doubles in size. The bus rings dominate that cost: `mixdown_bus_state` sizes each of the
  `delay_layers` lines to the largest 4–20 s `delay` gene plus slow-down headroom, so
  doubling them adds tens of seconds of audio per line. The adapter's allocation should be
  checked against the default memory budget (see open question 6).
- **The efx-off layer is a different shape.** `mixdown_master` is not a cut-down
  `mixdown_master_wet`. It computes its high-pass in the graph
  (`highpass(hp_cutoff[channel], …)`, not `fir(hp_coeffs[channel])`), applies volume with
  a stepped `scale(volume[channel])` rather than `ramp_scale`, has its own wet arm
  (`fir`, `scale(wet_level)`, `delay(…, buffers[channel], heads[channel])`,
  `route(transmission)` `[N, N]`), uses `lowpass(lp_cutoff, …)` in the tail, and has no
  stem captures. Its stereo version needs `hp_cutoff`/`volume` tiled to `[2N]`, a
  block-diagonal `[2N, 2N]` `transmission`, doubled `buffers`/`heads`, and its two
  `sum_channels()` replaced as above: `route(pan_matrix)` on the dry arm, and on the wet
  arm either a pan-weighted `[2N, 2]` route or pan folded into `transmission`.
- **Sends are post-pan.** A channel's efx-bus and reverb sends take the same side gain as
  its dry signal, as on a normal mixer, so a hard-left channel reaches only the left
  networks.
- **The master tail** (`clip`, `fir(lp_coeffs)`, `scale(master_gain)`, `clip`) is
  element-wise over `[2, S]`. If `fir` with a single coefficient row does not apply
  row-wise to a 2-row input, wrap it in `for each channel` with a tiled `[2, taps]`
  coefficient slot.
- **Stems.** `stem_channels` becomes `[2N, S]` (side-major); `stem_efx` and `stem_reverb`
  become `[2, S]`.

The cost per forward roughly doubles compared with today's LEFT-only forward. The
`createPdsl` javadoc gives this 2x as the reason the path is still mono: "until
per-channel PAN exists, processing the two (near-identical) sides separately would cost 2x
for no audible benefit". This plan adds pan, which is the condition the javadoc names for
paying that cost. It is still one forward per buffer.
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

None blocking: the task has no `blocked_by` entries, and the primitives it needs are
already available — `reshape`, `permute`, `slice` and `repeat_each` in the PDSL builtins
(`PdslBuiltins`), and the rectangular `route` in the audio primitives
(`AudioDspPrimitives`), which the existing `mixdown_efx_bus_rectangular` layer already
exercises.

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

   A simpler alternative, which the reviewer may prefer to the `pan_matrix` design
   altogether: apply pan as a **per-row gain** on the `2N` rows with
   `ramp_scale(pan_gain_prev[channel], pan_gain[channel])`. This is the stage the layer
   already uses for `volume`, `wet_in`, `efx_automation` and `reverb_send`, and the
   layer's own comment on the volume ramp calls a per-buffer step a zipper. The ramp goes
   after each arm's last non-linear stage (`clip`) — the dry arm next to the volume ramp,
   the efx-bus arm next to the `wet_in` ramp, the reverb arm next to the `reverb_send`
   ramp. Pan would then ramp within the buffer, and every side sum in the list above
   would be a constant 0/1 matrix built once, so the adapter would not need to refresh
   `pan_matrix` or a pan-folded `bus_send` per buffer. The cost is one more `ramp_scale`
   per arm. Under this plan's balance law the pan gain is a plain per-side gain, so moving
   it ahead of the (linear) send and network stages gives the same signal as the
   `route` form.
6. **State memory.** Doubling the ring state is the largest resource change in this plan,
   and it is driven by the bus-line rings, which each hold many seconds of audio. An
   alternative is to keep the bus-line network and the reverb shared across sides (mono
   send, stereo dry). That would avoid the doubling, but the sends would then not be
   panned, and it would no longer match the legacy per-side graph. The plan chooses full
   per-side copies. The reviewer should confirm that the doubled allocation fits the
   default `AR_HARDWARE_MEMORY_SCALE`, or ask for that measurement as part of the first
   step.

## Estimated complexity

**Medium–large.** Most of the effort is in the adapter (tiling per-channel arrays,
block-diagonal matrices, doubled state, per-buffer pan refresh) and in verifying the
two new stereo layers against the mono ones. The sink and settings changes are small.
