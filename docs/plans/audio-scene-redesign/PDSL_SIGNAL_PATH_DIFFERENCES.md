# PDSL vs Legacy Signal Path — Complete List of Expected Differences

This is the authoritative inventory of every known difference between the legacy
CellList mixdown path (`AudioSceneRealtimeRunner.createCellList`) and the PDSL
mixdown path (`AudioSceneRealtimeRunner.createPdsl` driving
`mixdown_master_wet` in `engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl`).
It exists so that the impact of flipping `MixdownManager.enablePdslMixdown` is fully
understood before the swap is made permanent.

Validation status: the two paths were A/B validated by ear on the real curated
arrangement (40s, 6 channels, seed 58) and by windowed RMS (per-window ratios
0.88–1.03, overall 0.94–0.99, sweep tracking confirmed). Everything below is a
*known and accepted* difference, not an open defect — open defects live in
`KNOWN_ISSUES.md`.

References into source: every difference cites where the behavior is decided, so a
later parity pass can find the lever.

---

## 1. What is identical

These behaviors were made bit-faithful (or numerically equivalent) during the parity
work and should NOT differ:

- **Pattern preparation** — both paths share the identical Java pattern-prepare phase
  (`AudioScene.getCells` filling the consolidated render buffer); the A/B isolates DSP
  only.
- **Per-channel input clamp** — `clip(-0.99, 0.99)` before the dry-bus filter, matching
  `AudioPassFilter.MAX_INPUT`'s per-sample input clamp.
- **Master tail shape** — clamp → master low-pass → `masterBusGain` (0.5) → hard clip
  `[-1, 1]`, in that order, same as `MixdownManager.createEfx`. (An earlier `tanh_act()`
  saturation was replaced by the same hard clip Java uses.)
- **Efx-bus gain structure** — per-channel `efxFactor = volume ∘ wetFilter` matching
  `MixdownManager.createCells`; the wet/dry blend of `EfxManager.apply`'s feedforward
  portion (gene filter → wet level → automation, summed with dry).
- **Reverb gain structure** — send enters the network scaled by DelayNetwork's `gain`
  (0.1); wet output is the mean over lines (`1/N`); feedback is a Householder reflection
  at spectral radius `1/N` — all matching the Java `DelayNetwork` constants.
- **Cutoff bounds** — filter cutoffs bounded to `[AudioPassFilter.MIN_FREQUENCY, 20000]`
  Hz, the same bounds the `AudioPassFilter` constructor applies.
- **Automation values** — the gene/clock-driven values themselves (cutoffs, volume, efx
  automation, reverb send) are computed by the same `AutomationManager` producers; only
  their delivery granularity differs (§3.2).
- **Overall levels** — no gain trims or fudge factors anywhere in the PDSL path; every
  gain stage maps to a named Java counterpart.

---

## 2. Determinism differences

### 2.1 The PDSL reverb is deterministic; the legacy reverb is randomized
`DelayNetwork` draws its per-line delay lengths with `Math.random()` (0.15–1.5 s range)
at construction, so **the legacy path renders a different reverb texture every run**.
The PDSL path uses fixed tap delays (§3.5), so its render is bit-repeatable. This means:

- Legacy A/B control files differ run-to-run; PDSL files do not.
- After the swap, renders of the same scene+genome become reproducible — a behavior
  *improvement*, but a change users may notice if they relied on re-rolling the reverb.

### 2.2 Kernel recompilation can shift feedback-coupled levels ~1–3%
Recompiling the kernels (different machine, driver, or compile ordering) can reorder
floating-point reductions. In feedback structures (efx feedback grid, reverb network)
this compounds, producing deterministic-per-binary but binary-to-binary RMS shifts of
roughly 1–3% late in a render. Both paths are subject to this; the PDSL path simply has
more of its DSP inside compiled kernels.

---

## 3. DSP approximations (PDSL ≠ legacy by design)

### 3.1 The per-channel `EfxManager.apply` echo is absent from the dry bus
In the legacy path, `EfxManager.apply` wraps **each channel's MAIN voicing** as well,
so its decaying echo contributes to the dry mix. The PDSL `mixdown_master_wet` applies
the apply-chain rendition only on the WET region (efx arm). Measured impact: the PDSL
dry bus is ~6% quieter on echo-heavy material. This was the residual in the final
windowed-parity numbers and is sub-perceptual on the review material.

### 3.2 Automation is stepped per buffer, not per frame
Legacy: `AutomationManager` values are evaluated per frame inside the CellList loop.
PDSL: time-varying values live in collection slots refreshed once per buffer by
`MixdownManagerPdslAdapter.automationRefresh` (8192 frames ≈ 186 ms steps at 44.1 kHz;
4096 ≈ 93 ms). Slow envelopes (the production case) sound identical; a hypothetical
fast LFO-style automation would audibly zipper. Coefficient producer graphs embedded
in the kernel are not viable (input-independent subgraphs get frozen at build time, and
recurrence-unrolled coefficient expressions grow exponentially), so per-buffer slot
delivery is the design, not a shortcut.

### 3.3 Filters are FIR approximations of the legacy IIR `AudioPassFilter`
- Legacy: per-sample two-pole IIR with coefficients recomputed every frame.
- PDSL: `fir()` with `pdslFilterOrder + 1 = 41` taps; the taps are the **truncated
  impulse response of the same biquad** at the current cutoff
  (`MixdownManagerPdslAdapter.biquadImpulseResponse`, resonance =
  `FixedFilterChromosome.defaultResonance`).
- Coefficients are delivered from a 1024-bin log-spaced cutoff table over
  [10 Hz, 20 kHz] (`biquadResponseTable` built host-side once at build;
  `tableRow` gathers the row device-side per buffer). Bin spacing is ~0.75% in
  cutoff — far below the JND for filter cutoff.
- Consequences: (a) cutoff is quantized to the bin grid; (b) very low cutoffs whose
  impulse response is longer than 41 taps are truncated, leaving a small DC leak /
  shallower ultimate slope; (c) during sweeps the FIR's phase/shape differs slightly
  from the per-frame IIR. All were measured sub-perceptual on the review render.

### 3.4 Efx feedback grid is frame-quantized and contraction-scaled
Legacy `.mself(fi(), transmission, fc(wetOut))` is a per-sample recurrence. PDSL
`feedback(...)` is block-parallel: feedback re-enters with one-buffer granularity. To
guarantee stability under that quantization, the genome transmission matrix is scaled
by `feedbackGain / channels` (0.6 / N) — a guaranteed contraction preserving the
genome's routing *pattern* but not its exact decay rate. Additional approximations on
this stage:

- `efx_fb_passthrough` is a **static diagonal** at `config.wetLevel`
  (`pdslWetLevel = 0.5`) standing in for the gene-driven `fc(wetOut)` output level.
- `efx_fb_delay` is a **static** `pdslDelaySamples = 6500` per channel rather than the
  gene-driven `AdjustableDelayCell` delay times.
- The gene-chosen efx filter renders as **LP-only** (the PDSL `choice()` needed for the
  gene's HP/LP selection is an open limitation; see KNOWN_ISSUES).

Net effect: the echo/tail texture differs in decay timing and brightness from the
legacy path while sitting at the same level. This is the largest *character* difference
of the swap.

### 3.5 Reverb network topology differs
Both are closed-loop multi-tap feedback delay networks with Householder mixing, but:

| Aspect | Legacy `DelayNetwork` | PDSL `delay_network` |
|---|---|---|
| Line delays | random, 0.15–1.5 s | deterministic, fractions 0.3–0.85 of a 2-buffer ring (≈0.11–0.29 s at 8192/44.1 kHz) |
| Ring length | per random delay | `REVERB_FRAMES = 2` buffers (≈0.37 s max) |
| Feedback step | per sample | per buffer (block-parallel) |

Same injection gain, same output mean, same feedback radius — so the *level* matches,
but the PDSL tail is shorter-range and more regular (denser early, less long-tail
shimmer). Perceptually a different but plausible room. Extending `REVERB_FRAMES`
lengthens the tail if later work wants closer texture parity.

### 3.6 Dual-mono master instead of independent stereo sides
The PDSL runner renders ONE master (from the LEFT-region voicings) and streams it to
both stereo writers. The legacy path nominally renders both sides, but for this content
the two sides are near-identical (no per-channel pan exists in either path), so both
paths are effectively mono. A true stereo image requires a pan stage in the PDSL
mixdown — new capability, not parity. Cost of the current choice: none audible; benefit:
half the DSP work.

---

## 4. Structural / behavioral differences

### 4.1 Channel-selection guard with CellList fallback
The PDSL path only engages for channel selections that are a zero-based contiguous
prefix of size ≥ 2 (`AudioSceneRealtimeRunner.supportsPdsl`). Anything else — notably
`AudioScene.renderChannel`'s single-channel render — silently (with a log line) uses
the CellList runner even when `enablePdslMixdown` is on. Consumers that render channel
subsets keep legacy behavior after the swap.

### 4.2 One warm-up forward at build time
`createPdsl` runs one throwaway `compiled.forward(...)` during construction to capture
the stable output buffer handle. The consolidated render buffer is explicitly
zero-filled (`PatternRenderBuffers.consolidate`), so this pass reads zeros and writes
zeros into the DSP rings; it costs one buffer of compile-warm latency at build, never
audio.

### 4.3 Per-buffer clock advance
The PDSL runner advances the scene clock once per buffer (the value every automation
producer reads); the CellList path advances per frame. Combined with §3.2 this is the
complete "time granularity" story: *everything* time-varying in the PDSL path moves in
buffer-sized steps.

### 4.4 Compile profile
- Legacy: compiles many small per-frame kernels (CellList); genome changes never
  require recompile.
- PDSL: compiles one large per-buffer model once per (channel count, buffer size);
  genome changes also never require recompile (values flow through slots/collections),
  but **buffer size is baked in** — changing it means a rebuild.
- The PDSL build currently *also* builds the unused Java mixdown CellList as a side
  effect of reusing `getCells` for pattern preparation, which dominates one-time build
  cost. Skipping it ("lean prep") is the identified next performance win.

---

## 5. Live-swap staleness (arguments sampled at build time)

Slot-delivered values refresh every buffer; **producer/collection args sampled at build
do not**. After `assignGenome` on a live runner, the following PDSL parameters keep
their build-time values until the runner is rebuilt, while the legacy path would track
the new genome immediately:

| Argument | Java counterpart | Refresh |
|---|---|---|
| `hp_coeffs`, `lp_coeffs`, `hp_cutoff`, `volume`, `lp_cutoff`, `efx_automation`, `reverb_send` | AutomationManager values | per buffer (slots) — **live** |
| `wet_filter_coeffs` | wet-filter gene coefficients | build time — **stale** |
| `efx_filter_coeffs` | EfxManager gene filter | build time — **stale** |
| `efx_wet_level` | delayLevels gene | producer, read live |
| `efx_fb_transmission` | transmission chromosome | producer, read live |
| `efx_fb_delay` | AdjustableDelayCell delay genes | static constant — **stale by design** (§3.4) |
| `efx_fb_passthrough` | `fc(wetOut)` gene | static constant — **stale by design** (§3.4) |
| `reverb_delays`, `reverb_feedback` | DelayNetwork internals | static — deterministic by design (§3.5) |

Impact: genome auto-swap workflows (e.g. optimizer health evaluation reusing one
runner across genomes) get *mostly* up-to-date behavior; the wet/efx filter coloration
lags the genome until rebuild. The CellList path has no such lag. If exact-per-genome
filters matter for a workflow, rebuild the runner per genome or move the coefficient
banks to per-buffer slot refresh (same mechanism as `hp_coeffs`).

---

## 6. Performance differences

Realtime budget: a buffer must render in under `bufferSize / sampleRate` seconds —
185.76 ms at 8192/44.1 kHz, 92.88 ms at 4096. Measured by
`AudioScenePdslBenchmarkTest` (M1, 2026-06-12, full tick = pattern prep + DSP +
streaming, three genomes spanning 318–1126 elements, medians of a 6 s steady-state
window, `AR_PATTERN_CACHE_PERSIST=true`; raw lines in
`studio/compose/results/pdsl-cutover/benchmark.txt`):

| Path | Buffer | Median tick | vs budget |
|---|---|---|---|
| PDSL | 8192 | 66–139 ms | **1.34–2.81× realtime** |
| CellList | 8192 | 298–370 ms | 0.50–0.62× realtime |
| PDSL | 4096 | 44 ms | **2.12× realtime** |

What the numbers say:

- **The PDSL tick is well under budget on every benchmarked genome and at both buffer
  sizes** — including the lower-latency 4096-frame configuration — and is ~3–5× faster
  than the CellList tick. Two fixes got it here (STATE_OF_PLAY §5): the `delay_network`
  ring update was de-fragmented into single-expression computations
  (`CollectionSlotUpdateComputation` + fused ring read/route, 1382 ms → 3 ms), and
  `for each channel` bodies of channel-uniform primitives now compile ONCE over
  `[channels, signalSize]` with bank-form arguments (`fir`/`scale`/`delay`) instead of
  per-channel blocks (`mixdown_master_wet` forward 77 ms → 21 ms; build 2.3 s → 0.8 s).
  Bodies that cannot take the bank form fall back to per-channel dispatch
  automatically (`PdslInterpreter.enableVectorizedForEach`).
- The remaining per-tick cost is dominated by per-note pattern preparation
  (~64 ms, one busy channel accounts for half) — the open a2 batched-dispatch
  integration, tracked separately.
- Build time (one-time): PDSL ≈42 s, CellList ≈67 s.

---

## 7. Swap impact summary

What a listener gets after flipping `enablePdslMixdown` on:

1. Same arrangement, same levels, same sweeps — validated identical-by-ear.
2. A different (deterministic, shorter-range) reverb texture and a different echo decay
   character on the efx bus.
3. Slightly quieter dry bus (~6%) on echo-heavy channels.
4. Reproducible renders run-to-run.
5. Buffer-quantized automation (inaudible at production envelope rates).
6. Mono-equivalent stereo, as before in practice.
7. **A far faster tick than the CellList path** (§6) — 1.34–2.81× realtime at 8192
   frames (2.12× at 4096) vs the CellList's 0.50–0.62×.
8. Single-channel renders (`renderChannel`) unchanged — they fall back to CellList.
