# EfxManager → PDSL: DSP Parity Plan

> Phase plan for closing the DSP gap between the PDSL Block-forward runner and the
> Java CellList path on `feature/audio-scene-pdsl`. The pattern path + runner
> plumbing are done and by-ear-validated (same arrangement, less DSP); this plan
> covers ONLY the remaining DSP-parity work. Companion docs:
> [STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5, [PDSL_AUDIO_DSP.md](PDSL_AUDIO_DSP.md)
> (substrate + MixdownManager migration map), [KNOWN_ISSUES.md](KNOWN_ISSUES.md)
> §5/§6/§7. Last updated 2026-06-09. Verify line numbers against current source
> before editing — they drift.

## A. Verified facts that ground the plan

- **Consolidated buffer already contains WET.** `consolidateRenderBuffers` allocates
  `bufferSize * channelCount * 4` (`AudioScene.java:1077-1081`); `getCells` fills LEFT
  then RIGHT (`:1047-1051`); within each, MAIN for all channels then WET when
  `enableEfx` (`:1103-1116`), one slot per `renderBufferIndex` (`:1174-1179`). Fill
  order: `[LEFT-MAIN(N), LEFT-WET(N), RIGHT-MAIN(N), RIGHT-WET(N)]`. The PDSL path
  currently reads only LEFT-MAIN at offset 0 (`AudioSceneRealtimeRunner.java:254-256`);
  LEFT-WET begins at offset `channelCount * bufferSize`.
- **Java master stage:** `bound(multiply(in, c(gain)), -1, 1)` — gain then HARD CLIP
  (`MixdownManager.java:780-792`), `masterBusGain = 0.5` (`:184`). PDSL does
  `scale(master_gain); tanh_act()` (`mixdown_manager.pdsl:361-362`) → soft-sat, runs
  hotter (peak ~0.99 vs ~0.6).
- **Java per-channel EFX (`EfxManager.apply`):** gene-chosen HP/LP `applyFilter`
  (`:299-329`), wet level `delayLevels[...,0]` (`:223`), `AdjustableDelayCell`
  `delayTimes[...,0]*beatDuration` (`:226-232`), automation `0.5*(1+curve)` (`:236-241`),
  recursive `.mself(fi(), g(delayLevels[...,1]))` echo (`:245`), `cells(wet, dry).sum()`
  (`:248`). Feedforward already in `efx_channel` (`efx_channel.pdsl:186-198`); feedback
  in `feedback_comb` (`:236-241`).
- **MixdownManager cross-channel + master:** per-channel HP+vol on main (`:561-583`); efx
  bus delays routed by `delayGene` then `.mself(fi(), transmission, fc(wetOut))` then
  `.sum()` (`:705-727`); reverb `DelayNetwork` (`:730-732`); master LP (`:765-773`);
  master gain+clip (`:780-792`). All map to existing `mixdown_manager.pdsl` layers.
- **IIR↔FIR mismatch (accepted):** Java wet/efx filters are IIR `AudioPassFilter`; PDSL
  renders static FIR (`MixdownManagerPdslVerificationTest.java:76-84,:280-289`). Owner
  accepted FIR for the wet bus (PDSL_AUDIO_DSP §16 item 4); parity gate is an energy band.
- **Adapter:** `buildArgsMap` already supplies per-channel `hp_cutoff`/`volume`,
  `lp_cutoff`, `wet_filter_coeffs`, `transmission`, `master_gain`, `buffers`/`heads`
  (`MixdownManagerPdslAdapter.java:151-222`); `perChannelProducer` uses `concat` and
  requires `channels >= 2`. No separate WET input is wired yet.

## B. Per Java DSP stage → PDSL mapping

| Java stage | Evidence | PDSL today | Action |
|---|---|---|---|
| Per-channel main HP+vol+sum | `MixdownManager.java:561-583,667` | `mixdown_master` main arm (`mixdown_manager.pdsl:342-349`) | keep; feed LEFT-MAIN |
| Per-channel EFX feedforward | `EfxManager.java:222-241,299-329` | `efx_channel` (`efx_channel.pdsl:186-198`) | NEW: apply per channel on WET input |
| EFX recursive `.mself` echo | `EfxManager.java:245` | `feedback_comb` (`efx_channel.pdsl:236-241`) | Stage 4: swap delay→feedback (multi-frame ring) |
| Wet voicing source | already in consolidated buffer (`AudioScene.java:1110-1116`) | NOT wired | NEW: read WET region, route to wet arm |
| Transmission route + sum | `MixdownManager.java:705-727` | `route(transmission)`+`sum_channels` (`mixdown_manager.pdsl:350-358`) | keep square route |
| Reverb DelayNetwork | `MixdownManager.java:730-732` | `mixdown_reverb_bus` (`:415-423`) | keep OFF (matches `enableReverb=false`) |
| Master LP | `MixdownManager.java:765-773` | `lowpass(lp_cutoff)` (`:360`) | keep |
| Master gain+clip | `MixdownManager.java:780-792` | `scale+tanh_act` (`:361-362`) | CHANGE (Stage 1) |
| True stereo | Java L/R; PDSL dual-mono (`AudioSceneRealtimeRunner.java:287-295`) | dual-mono | Stage 5 (follow-up) |
| Automation granularity | Java per-frame; PDSL per-buffer (`:328-332`) | per-buffer | Stage 6 (accept for now) |

## C. Phased, independently A/B-testable plan

Gate each stage by ear via `AudioScenePdslCutoverTest.realSceneAbReview` (real library
`/Users/Shared/Music/Samples` + `/Users/Shared/Music/pattern-factory.json`) plus the fast
`pdslVsCellListPlumbingSmoke`, and by energy/peak via
`MixdownManagerPdslVerificationTest`.

- **Stage 0 — baseline.** Capture current A/B WAVs + peak/RMS as the "before" reference.
- **Stage 1 — master-stage parity. SKIPPED by owner decision (2026-06-09):** keep the
  `tanh_act()` soft saturation rather than matching Java's hard clip. (The `clip`/`bound`
  primitive idea is parked; revisit only if level-matching becomes necessary.)
- **Stage 2 — route the already-rendered WET voicing. DONE (2026-06-09).** Added layer
  `mixdown_master_wet` ([`mixdown_manager.pdsl`]) taking `[2*channels, signal_size]`: the
  dry arm reads rows `[0,channels)`, the efx arm reads rows `[channels,2*channels)`.
  `AudioSceneRealtimeRunner.createPdsl` uses it when `enableEfx` with a single zero-copy
  `[2*channels,bufferSize]` view over consolidated offset 0 (LEFT-MAIN+LEFT-WET contiguous).
  **PDSL gotcha:** the built-in `slice` is 1-D only (`subset` rejects a 1-D slice of a 2-D
  shape), so each arm does `reshape(shape(2*channels*signal_size)); slice(off,
  channels*signal_size); reshape(shape(channels, signal_size))`; use the `shape(...)`
  builtin so arithmetic dim args evaluate. Nested layer calls don't compose
  (`callUserLayer` infers input shape from the return annotation), so the arm bodies are
  inlined like `mixdown_master`. Validated exactly by
  `MixdownManagerPdslVerificationTest.mixdownMasterWetRoutesMainAndWetHalves` (WET==MAIN →
  maxDiff 0.0) and by-ear via the real-scene A/B.
- **Stage 3 — per-channel EFX feedforward on WET. DONE (2026-06-10, owner chose
  "both, feedforward then feedback").** `mixdown_master_wet`'s WET arm now prepends the
  EfxManager.apply feedforward chain per channel: `accum_blocks({identity}, {fir(efx_filter_coeffs[ch]);
  scale(efx_wet_level[ch]); scale(efx_automation[ch])})` = dry + (filter → wet level →
  automation). `MixdownManagerPdslAdapter.buildArgsMap(mixdown, efx, config)` (new overload)
  sources the params from EfxManager genes (`getDelayLevels`/`getDelayAutomation`/
  `getAutomationManager` — new package-private getters); the runner passes
  `scene.getEfxManager()` when `enableEfx`. **Two wire-first approximations:** (a) the
  gene HP/LP `choice(...)` is NOT generatable inside the compiled model
  (`UnsupportedOperationException: Choice`), so the efx filter is a per-channel **low-pass
  only** at the gene cutoff (HP option dropped); (b) the per-channel efx **delay** is not in
  this stage (it's part of the feedback increment). Validated: the routing test still matches
  exactly with efx neutralized (`maxDiff 0.0`), and the real-scene A/B renders non-silent/finite.
- **Stage 3b — recursive feedback grid. DONE (2026-06-10).** The WET arm's feedforward
  `route(transmission)` is replaced by `feedback(efx_fb_delay, efx_fb_transmission,
  efx_fb_passthrough, fb_buffers, fb_heads)` — the PDSL analogue of `MixdownManager.createEfx`'s
  `.mself(fi(), transmission, fc(wetOut))` decaying echo/tail. Adapter (`buildArgsMap(mixdown,
  efx, config)`) supplies: `efx_fb_transmission` = genome transmission scaled by
  `FEEDBACK_GAIN/channels` (0.6/N → max row sum ≤ 0.6 < 1 → guaranteed contraction → stable
  block-parallel feedback); `efx_fb_passthrough` = diagonal `wetLevel`; `efx_fb_delay` =
  `config.delaySamples`; fresh `fb_buffers`/`fb_heads` state (1-frame ring, delay < signal_size).
  New PDSL state block `mixdown_efx_feedback_state`. Validated: smoke (run 157942f8) + real-scene
  A/B (run fb4bf116, seed 59, no divergence, PDSL peak 0.996). **Wire-first approximations:**
  frame-quantized (block-parallel feedback ≠ Java per-sample `.mself`); transmission
  contraction-scaled (deviates from raw genome magnitude, preserves routing pattern); passthrough
  static diagonal `wetLevel` (not the `wetOut` gene); constant per-channel feedback delay.
- **Stage 4 — EFX recursive feedback.** Swap feedforward `delay` for `feedback`
  (`feedback_comb`) with a multi-frame ring (`buffers` sized `channels*k*bufferSize`),
  matching `.mself(... g(delayLevels[...,1]))`. Sub-frame feedback stays frame-quantized
  (accepted, PDSL_AUDIO_DSP §17.3). May be split as its own follow-up if Stage 3 already
  satisfies by ear.
- **Stage 5 — true stereo (follow-up).** Run the master over both L/R regions (RIGHT at
  offsets `2*channelCount` / `3*channelCount` * bufferSize) and stream distinct L/R.
  Doubles the graph; interacts with the 31-arg ceiling. Defer.
- **Stage 6 — automation granularity (follow-up).** Keep per-buffer (~186ms at 8192) this
  phase; only sub-divide if the owner hears stepping.

## D. Riskiest unknowns & de-risking

1. **Metal 31-buffer-arg ceiling (highest).** Each new per-channel producer adds kernel
   args; the full per-frame Java loop already exceeds Metal's 31 (KNOWN_ISSUES §5). Never
   force `AR_HARDWARE_DRIVER` (hybrid mandatory); after Stages 3/4 confirm the model
   compiles under default hybrid. If one layer blows the budget, split into sequential
   sub-layers and lean on consolidated-buffer dedup to collapse state args.
2. **Compile-reuse / GeneratedOperation pool (KNOWN_ISSUES §6).** Bigger graphs may exhaust
   it. Compile once at runner build (already the pattern). If it triggers, escalate — do
   not work around.
3. **IIR↔FIR mismatch.** Accepted; keep the gate an energy/dynamic-range band, never loosen
   to manufacture a pass; document the residual in the new adapter methods.
4. **Transmission re-evaluation per buffer.** Confirm the matrix producer samples current
   genome state each `forward()` and matches Java routing.
5. **WET-region offset correctness.** Stage 2 depends on the verified layout; add a runtime
   sentinel assertion guarding against future `getPatternCells` reordering.

## E. Critical files & order
1. `engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl` — master fix (1),
   WET-slice top layer (2), efx-on-wet wiring (3/4). Extend layers; do not duplicate.
2. `engine/ml/src/main/resources/pdsl/audio/efx_channel.pdsl` — reuse
   `efx_channel`/`feedback_comb` (3/4).
3. `studio/compose/.../AudioSceneRealtimeRunner.java` — `createPdsl`: WET view, widened
   input, master args, efx layer (2-5). Block-forward only; never `wrapBlockAsCellList`.
4. `studio/compose/.../arrange/MixdownManagerPdslAdapter.java` — `buildArgsMap`: efx
   per-channel args, feedback matrices, master-clip arg (1,3,4). Methods on the adapter.
5. `studio/compose/.../dsl/audio/AudioDspPrimitives.java` (+ `MultiChannelDspFeatures.java`)
   — only if Stage 1 adds `clip`/`bound`; pure `CollectionProducer` ops.

Validation harnesses (gates, not change set): `AudioScenePdslCutoverTest` (by-ear A/B per
stage), `MixdownManagerPdslVerificationTest` (energy/peak parity, new probes).

## F. Follow-ups to split out of this phase
- True stereo (Stage 5); reverb acoustic parity (`mixdown_reverb_bus`/`DelayNetwork`,
  kept OFF here); finer automation (Stage 6); rectangular `delayGene` N→M fan-in
  (`MixdownManager.java:717-721`, now expressible via rectangular `route`, not needed for
  square-channel parity); lean pattern-prep (`createPdsl` still calls `getCells`, building
  the unused Java mixdown CellList — replace only after parity lands so the A/B keeps
  sharing identical prep).

## G. Repo-health note
`AudioScene.java` is ~1486/1500 lines. Add new WET-routing/lean-prep behavior to
`AudioSceneRealtimeRunner`, not back into `AudioScene`. Consider extracting consolidated
render-buffer management (`consolidateRenderBuffers` + `getConsolidatedRenderBuffer` +
render-cell tracking) into its own collaborator to drop `AudioScene` under the threshold;
raise with the owner before any edit that would push it over 1500.
