# EfxManager → PDSL: DSP Parity Plan

> Phase plan for closing the DSP gap between the PDSL Block-forward runner and the
> Java CellList path on `feature/audio-scene-pdsl`. Companion docs:
> [STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5, [PDSL_AUDIO_DSP.md](PDSL_AUDIO_DSP.md)
> (substrate + MixdownManager migration map), [KNOWN_ISSUES.md](KNOWN_ISSUES.md)
> §5/§6/§7. Last updated 2026-06-10. Verify line numbers against current source
> before editing — they drift.

## 0. THE PARITY STANDARD (read first)

This is a project to **replace one mixdown process with another that produces the same
result.** The PDSL output must be **indistinguishable from the Java output to a normal
listener.** The bar is: *you should have to be a sound engineer doing careful A/B listening
to notice any difference at all.* If a non-expert can hear a difference, something is wrong.

Concretely:
- The ONLY acceptable differences are sub-perceptual approximations whose mechanism is
  understood and bounded: FIR vs Java's IIR filter shape; block/frame-quantized delay and
  feedback vs per-sample; floating-point ordering. These must be *barely* audible at most.
- A **level/energy difference of more than a few percent is a DEFECT, not a "hotter mix."**
  "1.4 vs 1.15" or "PDSL is ~2.5× the Java energy" is a HUGE, disqualifying gap — it means
  the PDSL path is computing the signal structurally differently from Java.
- **Never compensate for a divergence by trimming a gain.** Scaling the output to make a
  meter match hides the defect (it will reappear under a different genome/input) and destroys
  the very signal that tells us the model is wrong. A divergence is always localized and
  fixed at its structural source so the level comes out right *on its own*.
- Verification is by **per-stage A/B against the Java equivalent** (energy/RMS/peak AND by
  ear), not by "is it non-silent / finite."

## 0b. KNOWN DEFECTS (open)

- **efx path ~1.5–2.5× hotter than Java (OPEN, under investigation).** Bisection runs
  5f1a90b2 / 9b0a79c6: PDSL RMS 0.19–0.26 vs CellList 0.16–0.17, peak 0.86 vs 0.57 — i.e.
  the PDSL summed mix is ~2.3× hotter going into the master (back-computed via `atanh` from
  the post-`tanh` peak), driving the master saturator into its nonlinear region (audible
  distortion). This is a structural divergence in the efx/wet/feedback rendering, NOT a level
  to trim. Candidate mechanisms in §H below.
- **Reverb parity unverified.** Reverb is now modeled faithfully (per-channel `reverbFactor`
  send on `reverbChannels=[1,2,3,4,5]`), but for the random-seed test genomes the reverb send
  evaluates to ~0 in BOTH paths (Java reverb-on ≡ reverb-off), so reverb parity is not yet
  exercised by audio. Needs a genome (or forced send) that actually drives reverb.

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
- **Reverb bus — DONE (2026-06-10).** A third `accum_blocks` arm in `mixdown_master_wet`
  renders `MixdownManager.createEfx`'s `reverb.sum().map(DelayNetwork)`: MAIN region → mono
  send → `scale(reverb_send)` → `repeat(channels)` → `delay_network(reverb_delays,
  reverb_feedback, reverb_buffers, reverb_heads)` → `sum_channels`, reusing the existing
  `mixdown_reverb_state`. Adapter supplies `reverb_send` (0.25), `reverb_delays` (per-tap
  multi-frame, spread for diffusion), `reverb_feedback` (scaled Householder, spectral radius
  `REVERB_GAIN` 0.7 → stable), and a `REVERB_FRAMES`-frame ring (set to 2 to limit compile
  re-optimization). Audible decaying tail; validated by the smoke + real-scene A/B.
- **Stereo — currently DUAL-MONO (stopgap); true stereo is wanted and valuable.** The PDSL
  path renders one master and streams it to both writers.
  **Why stereo matters (do not dismiss it):** many audio samples are themselves stereo, with
  genuinely different left and right channels. Users who load a stereo sample expect the L and
  R channels carried through the signal path independently and delivered as a matching stereo
  result. Stereo support is therefore a real, expected feature — independent of any particular
  test arrangement.
  **A prior measurement is NOT evidence against stereo.** An earlier A/B happened to use
  near-mono content, so its output was near-mono (Java L−R rms ~0.003%). That is tautological —
  mono-ish input yields mono-ish output — and says nothing about the value of stereo support.
  Validate stereo with samples that have deliberately different L/R channels, not with the
  default arrangement.
  **REQUIREMENT for the implementation:** stereo means *twice the channels inside a SINGLE
  compiled model and a SINGLE forward pass* — one graph that carries the L and R channels
  together (process `[2*channels, ...]` / emit a `[2, bufferSize]` master). Running the whole
  mixdown pipeline twice (two compiles / two forwards) is never acceptable; that earlier
  attempt was reverted.
  **Sources of L/R difference to carry through:** (1) stereo input samples — the primary one —
  whose distinct channels must flow through the per-channel chain and mixdown without being
  collapsed to mono; (2) per-channel pan in the mixdown. Both belong in the single stereo-width
  graph.
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

## H. efx-path divergence — candidate mechanisms (investigation 2026-06-10)

The PDSL summed mix is ~2.5× hotter than Java (§0b). This is a structural mismatch in how
the PDSL efx/main arms layer their gains versus Java — NOT a level to trim. Java signal flow:

- **Per channel (before mixdown):** `EfxManager.apply(voicing)` (EfxManager.java:215-249) =
  `dry + feedback(automatedDelay(delayLevels[ch,0] · (HP|LP)(dry)))`. Applied to BOTH the MAIN
  and WET voicing render cells (`AudioScene.getPatternChannel:1187`), gated by
  `wetChannels`.
- **Main bus:** `main = (efx.apply'd MAIN) × v` (the per-channel volume), with `mainFilterUp`
  HP, then `sum` (createCells:585-668, 691).
- **Efx bus:** `efx = (efx.apply'd WET) × efxFactor` where `efxFactor = v ∘ wetFilter`
  (createCells:654-656), then `.m(delays).mself(transmission, fc(wetOut)).sum()`
  (createEfx:745-748).
- **Reverb bus:** `(WET) × reverbFactor → DelayNetwork` (createCells:629-657, createEfx:754-756).
- **Master:** `(main + efx + reverb) → masterFilterDown LP → × masterBusGain(0.5) → bound(±1)`.

PDSL `mixdown_master_wet` divergences (energy errors), prime-suspect first:

1. **[PRIME] The efx arm never applies the per-channel volume `v`.** Java scales the entire
   efx bus by `efxFactor = v ∘ wetFilter` (v < 1). The PDSL efx arm applies `efx_wet_level`
   and a static `wet_level` but NOT `volume[ch]`. The main arm DOES apply volume. That
   asymmetry alone makes the efx bus hotter than Java by ~`1/v` — the most likely source of
   the ~2.5× summed-mix excess.
2. **Spurious static `wet_level` on the efx arm.** PDSL multiplies the efx arm by
   `config.wetLevel` (0.35–0.5), which has no counterpart in Java's efx bus (Java's wet
   scaling is `v × wetFilter` on input and `wetOut` on the feedback output). Redundant /
   mis-scaled.
3. **`EfxManager.apply` is not applied to the MAIN voicing in PDSL.** Java applies it (dry+wet)
   to BOTH voicings; the PDSL main arm uses the raw MAIN region. Wrong main/efx balance.
4. **Feedback output level.** Java's efx feedback output factor is `wetOut.valueAt(0)`; the
   PDSL feedback `passthrough` is the static `wetLevel`.
5. **Filter layering / shape.** Java: per-channel `EfxManager.applyFilter` (gene HP/LP) +
   branch `wetFilter`. PDSL: `efx_filter_coeffs` (apply-replica, LP-only due to the `choice`
   limitation) + `wet_filter_coeffs` (mixdown). Shape divergence (≈unity energy), plus the
   accepted LP-only approximation.

**Fix (structural, never a trim):** re-derive the PDSL efx/main arms to mirror Java's exact
gain chain — apply `v` to the efx bus (`efxFactor = v ∘ wetFilter`), remove the spurious
static `wet_level`, use `wetOut` for the feedback output, and apply `EfxManager.apply` to the
MAIN voicing too. Then verify **per-stage RMS against the Java equivalent** — target: PDSL
within a few percent of CellList with no audible difference (§0 standard), not "close enough".
