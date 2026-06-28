# PDSL vs Legacy Signal Path — Remaining Differences (with Perceptual Map)

The authoritative inventory of every known difference between the legacy CellList mixdown
path (`AudioSceneRealtimeRunner.createCellList`) and the PDSL mixdown path
(`AudioSceneRealtimeRunner.createPdsl` driving `mixdown_master_wet` in
`engine/ml/src/main/resources/pdsl/audio/mixdown_manager.pdsl`). It exists so the
differences introduced by the PDSL path — **now the default** (`MixdownManager.enablePdslMixdown`,
on since 2026-06-26; set `AR_PDSL_MIXDOWN=disabled` for the legacy Java path) — are fully
understood. Each difference cites where the behavior is decided, so a later parity pass
can find the lever; where a difference has an audible symptom, it is given inline (this
doc merges the former mechanism inventory and its listener companion).

**Validation status.** The two paths were A/B validated by ear on the real curated
arrangement (40 s, 6 channels, seed 58) and by windowed RMS (per-window ratios 0.88–1.03,
overall 0.94–0.99, sweep tracking confirmed). Everything below is a *known and accepted*
difference, not an open defect — open defects live in [KNOWN_ISSUES.md](KNOWN_ISSUES.md).

**Where to listen.** The FX-character differences live on the **wet/reverb channels**,
not everywhere. With default routing `wetChannels = [2,3,4,5]` (efx bus) and
`reverbChannels = [1,2,3,4,5]` (reverb send); channel 0 is essentially dry and the least
changed. To isolate the efx feedback, render a wet channel (`AR_GENERATE_CHANNEL=2`); to
isolate the reverb, listen to a reverb-only channel.

---

## 1. What is identical (made parity-faithful)

These were made numerically equivalent during the parity work and should NOT differ:

- **Pattern preparation** — both paths share the identical Java pattern-prepare phase
  (the A/B isolates DSP only).
- **Per-channel input clamp** — `clip(-0.99, 0.99)` before the dry-bus filter, matching
  `AudioPassFilter.MAX_INPUT`.
- **Master tail shape** — clamp → master low-pass → `masterBusGain` (0.5) → hard clip
  `[-1, 1]`, same order as `MixdownManager.createEfx`.
- **Efx-bus gain structure** — per-channel `efxFactor = volume ∘ wetFilter`; the wet/dry
  blend of `EfxManager.apply`'s feedforward portion.
- **Reverb gain structure** — send scaled by DelayNetwork's `gain` (0.1); wet output is
  the mean over lines (`1/N`); feedback is a Householder reflection at spectral radius
  `1/N` — matching the Java `DelayNetwork` constants.
- **Cutoff bounds** — `[AudioPassFilter.MIN_FREQUENCY, 20000]` Hz.
- **Automation values** — the gene/clock-driven values are computed by the same
  `AutomationManager` producers; only their delivery granularity differs (§3.5).
- **Overall levels** — no gain trims or fudge factors; every gain stage maps to a named
  Java counterpart.

---

## 2. Closed since the original inventory (commit `4992598a3`, 2026-06-19)

The efx feedback bus was the **largest character difference** in the original inventory —
four approximations stacked on the wet-bus regeneration. **Three of the four are now
gene-driven and match legacy intent**, via `MixdownManagerPdslAdapter`:

- **HP/LP filter selection (was LP-only).** The efx return now honors the gene's HP/LP
  choice via a generatable host-side blend `coeffs = hp*(1-sel) + lp*sel` with
  `sel = floor(min(decision, 0.999999) × 2)` from the `delayLevels` gene
  (`MixdownManagerPdslAdapter.java:737`). There is still **no `choice()` PDSL primitive**
  (`Choice` cannot be code-generated inside a compiled model), but the "LP-only / muddy
  feedback on HP genes" symptom is resolved.
- **Per-channel feedback delay (was static 6500 samples).** `efx_fb_delay` is now
  gene-driven `floor(delayTimes gene × beatSamples)` per channel
  (`MixdownManagerPdslAdapter.java:544`), mirroring `AdjustableDelayCell`. The "uniform /
  metallic / in-sync repeats" symptom is resolved.
- **Per-channel feedback level (was static 0.5 diagonal).** `efx_fb_passthrough` is now
  the `wetOut` gene on the diagonal (`fc(wetOut)`, `:558`/`:1013`). The "flat per-channel
  wash" symptom is resolved.

> The `efx_fb_delay`/`efx_fb_passthrough` rows that older docs listed under "live-swap
> staleness — static by design" are therefore obsolete; both are now live producers
> (reflected in §6).

The fourth approximation (block-rate re-entry + contraction scaling) is *structural* and
remains — see §3.1.

---

## 3. Remaining DSP differences (PDSL ≠ legacy by design)

### 3.1 Efx feedback is block-rate, not per-sample (+ contraction scaling)
`feedback(...)` re-enters **once per buffer** (block-parallel) vs the legacy per-sample
recurrence, and the transmission matrix is scaled by `feedbackGain / channels` (0.6/N,
`MixdownManagerPdslAdapter.java:552`) to guarantee a stable contraction under that
quantization. **Symptom:** regeneration is steppier (repeats quantized to the ~93–186 ms
buffer grid) and decays at a somewhat more controlled rate than the gene intended.
**Levers:** smaller buffer for a finer grid; tune `feedbackGain` toward the legacy decay
(bounded by stability); true per-sample re-entry is an in-kernel recurrence (expensive
structural change). This is now the *only* remaining efx-feedback character difference.

### 3.2 Reverb network topology differs (a different room)
Both are closed-loop multi-tap feedback delay networks with Householder mixing, but PDSL
uses `REVERB_FRAMES = 2` buffers (~0.37 s max ring) with deterministic short line delays
(fractions 0.3–0.85 of the ring, ≈0.11–0.29 s at 8192/44.1 kHz, `:651`/`:689`), against
the legacy random 0.15–1.5 s delays. Same injection gain, output mean, and feedback
radius, so the **level matches**; the PDSL tail is shorter-range and more regular — denser
early reflections, less long-tail shimmer: a smaller, "boxier" but plausible room.
**Lever:** extend `REVERB_FRAMES` and match the line-delay distribution (a knob, not a
rewrite).

### 3.3 Per-channel `EfxManager.apply` echo absent from the dry bus
The legacy `EfxManager.apply` wraps each channel's MAIN voicing, so its decaying echo
contributes to the dry mix; the PDSL path applies the apply-chain only on the WET arm.
**Symptom:** the dry bus is ~6% quieter / slightly less ambient on echo-heavy material.
Subtle — this was the residual in the final windowed-parity numbers. **Lever:** a
multi-frame comb ring on the dry bus.

### 3.4 Filters are FIR approximations of the legacy IIR `AudioPassFilter`
Legacy is a per-sample two-pole IIR with coefficients recomputed every frame; PDSL uses
`fir()` with `pdslFilterOrder + 1 = 41` taps that are the **truncated impulse response of
the same biquad**, delivered from a 1024-bin log-spaced cutoff table over [10 Hz, 20 kHz]
(`biquadResponseTable`, gathered device-side per buffer). **Symptom:** cutoff is quantized
to ~0.75% bins (well below the JND); very low cutoffs whose IR exceeds 41 taps truncate
(small DC leak / shallower ultimate slope); sweep phase/shape differs slightly. All
measured sub-perceptual on the review render.

### 3.5 Automation is stepped per buffer, not per frame
Time-varying values live in collection slots refreshed once per buffer by
`MixdownManagerPdslAdapter.automationRefresh` (~186 ms steps at 8192, ~93 ms at 4096).
**Symptom:** slow production envelopes sound identical; only a hypothetical fast
LFO-style automation would audibly zipper. (In-kernel coefficient producer graphs are not
viable — input-independent subgraphs freeze at build, recurrence-unrolled coefficient
expressions grow exponentially — so per-buffer slot delivery is the design, not a
shortcut.)

### 3.6 Dual-mono master instead of independent stereo
The PDSL runner renders ONE master (from the LEFT-region voicings) and streams it to both
stereo writers. Since no per-channel pan exists in either path, both are effectively mono
on current content. **Cost:** none audible; **benefit:** half the DSP work. A true stereo
image requires a pan stage — new capability, not parity (tracked in
[STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5).

---

## 4. Determinism differences

### 4.1 PDSL reverb is deterministic; legacy is randomized
`DelayNetwork` draws per-line delay lengths with `Math.random()` (0.15–1.5 s) at
construction, so the **legacy path renders a different reverb texture every run**; PDSL
uses fixed taps and is bit-repeatable. After the swap, renders of the same scene+genome
become reproducible — a behavior *improvement*, but a change for anyone who relied on
re-rolling the reverb. (If a particular legacy reverb take is the reference, remember it
was one random draw.)

### 4.2 Kernel recompilation can shift feedback-coupled levels ~1–3%
Recompiling (different machine, driver, or compile ordering) can reorder FP reductions; in
feedback structures this compounds to deterministic-per-binary but binary-to-binary RMS
shifts of ~1–3% late in a render. Both paths are subject to this; PDSL simply has more DSP
inside compiled kernels. Not an audible artifact in itself — it explains RMS drift across
machines.

---

## 5. Structural / behavioral differences

- **Channel-selection guard with CellList fallback** — the PDSL path engages for any
  single channel `[c]` and the zero-based contiguous prefix `[0,1,…,n-1]`; a
  *non-contiguous* subset (e.g. `[0,2]`) falls back to CellList (the feedback grid is
  indexed by bank position). See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §5.
- **One warm-up forward at build time** — `createPdsl` runs one throwaway
  `compiled.forward(...)` over the zero-filled consolidated buffer to capture the stable
  output handle; reads zeros, writes zeros, costs one buffer of compile-warm latency,
  never audio.
- **Per-buffer clock advance** — the scene clock is ticked `bufferSize` times per buffer
  (advancing a full buffer), but `automationRefresh` samples it **once** per buffer, so
  everything time-varying moves in buffer-sized steps (the complete "time granularity"
  story with §3.5).
- **Compile profile** — legacy compiles many small per-frame kernels (genome changes never
  recompile); PDSL compiles one large per-buffer model once per (channel count, buffer
  size). Genome changes flow through slots/collections and also never recompile, but
  **buffer size is baked in** — changing it means a rebuild. `createPdsl` uses
  `AudioScene.prepareRenderBuffers` (not `getCells`), so it no longer builds the unused
  Java mixdown CellList ("lean prep").

---

## 6. Live-swap staleness (arguments sampled at build time)

Slot-delivered values refresh every buffer; **producer/collection args sampled at build do
not**. After `assignGenome` on a live runner:

| Argument | Refresh |
|---|---|
| `hp_coeffs`, `lp_coeffs`, `hp_cutoff`, `lp_cutoff`, `volume`, `efx_automation`, `reverb_send` | per buffer (slots) — **live** |
| `efx_wet_level`, `efx_fb_transmission`, `efx_fb_delay`, `efx_fb_passthrough` | producers, read **live** (the latter two became gene-driven in §2) |
| `wet_filter_coeffs`, `efx_filter_coeffs` | build time — **stale until rebuild** |
| `reverb_delays`, `reverb_feedback` | static — deterministic by design (§3.2) |

**Impact:** the only genuine staleness now is the two filter-coefficient banks
(`wet_filter_coeffs`, `efx_filter_coeffs`); their coloration lags the genome until the
runner is rebuilt. The CellList path has no such lag. If exact-per-genome filters matter
for a workflow, rebuild the runner per genome or move those banks to per-buffer slot
refresh (same mechanism as `hp_coeffs`).

---

## 7. Performance

Realtime budget: `bufferSize / sampleRate` — **185.76 ms** at 8192/44.1 kHz, **92.88 ms**
at 4096. The figures below are M1 measurements from `AudioScenePdslBenchmarkTest` (full
tick = pattern prep + DSP + streaming, three genomes spanning 318–1126 elements,
`AR_PATTERN_CACHE_PERSIST=true`). **These are not committed evidence** — the benchmark
writes to `results/pdsl-cutover/benchmark.txt`, which is git-ignored and not in the repo.

`AR_PDSL_VECTOR_FOREACH` (vectorized `for each channel`) is **on by default**, so the
current default tick is the vectorized row:

| Path | Buffer | Median tick | vs budget |
|---|---|---|---|
| **PDSL (default = vectorized)** | 8192 | 66–139 ms | **1.34–2.81× realtime** |
| PDSL (vectorized) | 4096 | 44 ms | **2.12× realtime** |
| PDSL (`AR_PDSL_VECTOR_FOREACH=disabled`) | 8192 | 161–228 ms | 0.81–1.15× realtime |
| CellList | 8192 | 298–373 ms | 0.50–0.62× realtime |

The PDSL tick is **faster than the CellList tick everywhere**, after the `delay_network`
ring update was de-fragmented into single-expression computations (1382 ms → 3 ms) and
channel-uniform bodies were vectorized (`mixdown_master_wet` forward 77 ms → 21 ms). The
remaining per-tick cost is dominated by **a2** pattern rendering (now batched — both
melodic and percussion; ~2.34× realtime on a dense scene, so the system is a2-bound),
tracked in [A2_BATCHED_DISPATCH.md](A2_BATCHED_DISPATCH.md).

---

## 8. Perceptual map — symptom → cause (remaining differences only)

| What you hear | Cause | § | Magnitude | Move toward legacy by |
|---|---|---|---|---|
| Feedback **regeneration is steppy / coarser**, repeats land on a grid | block-rate (once-per-buffer) re-entry vs per sample | §3.1 | Medium (buffer-size dependent) | Smaller buffer; true per-sample needs in-kernel recurrence |
| Feedback **decays tamer / doesn't sustain like it did** | transmission scaled by `feedbackGain/channels` for stability | §3.1 | Medium | Tune `feedbackGain` toward legacy decay |
| Reverb sounds like a **smaller, more regular room** — shorter tail | short deterministic line delays + 2-buffer ring | §3.2 | Medium ("different but plausible room") | Extend `REVERB_FRAMES`; match line-delay distribution |
| Reverb **no longer changes when you re-run** the same scene | PDSL reverb is deterministic; legacy randomizes | §4.1 | Workflow change (an improvement) | (Intentional) |
| Dry signal is **slightly thinner** on echo-heavy parts | per-channel `apply` echo absent from dry bus (~6% quieter) | §3.3 | Subtle | Multi-frame comb ring on the dry bus |
| Filter **sweeps differ subtly**; a downward sweep leaks a touch more low end | 41-tap FIR approximation of the per-sample IIR | §3.4 | Sub-perceptual | More FIR taps |
| **Zipper** on fast modulation (not slow envelopes) | automation stepped per buffer | §3.5 | Inaudible at production rates | Per-frame automation (revisit only if audible) |
| No **stereo width**, and no widening capability | dual-mono master | §3.6 | None on current (pan-free) content | Add a pan stage (new capability) |
| FX **tone seems "stuck"** when sweeping genomes live | `wet_filter_coeffs`/`efx_filter_coeffs` sampled at build | §6 | Only in live genome-swap workflows | Per-buffer slot refresh, or rebuild per genome |
| Same scene **~1–3% different in level on another machine** | FP reduction reordering in feedback loops | §4.2 | Not audible as such | (Inherent; both paths) |

> The original inventory's three biggest "different FX colour" rows — darker LP-only
> feedback, uniform fixed delay, flat fixed level — are **no longer present** (§2).

---

## 9. Swap impact summary & triage

What a listener gets after flipping `enablePdslMixdown` on:

1. Same arrangement, same levels, same sweeps — validated identical-by-ear.
2. A different (deterministic, shorter-range) reverb texture; and a *block-rate* (steppier)
   efx-feedback regeneration — but the feedback **brightness, spread, and per-channel
   depth now match the gene** (§2), so the former "different FX colour" is largely gone.
3. Slightly quieter dry bus (~6%) on echo-heavy channels.
4. Reproducible renders run-to-run.
5. Buffer-quantized automation (inaudible at production envelope rates).
6. Mono-equivalent stereo, as before in practice.
7. A **faster tick than CellList** (§7).
8. Single-channel and zero-based-contiguous multi-channel renders go through PDSL;
   non-contiguous subsets fall back to CellList.

**Triage — chase vs accept:**
- **Tune (cheap knobs):** `feedbackGain` for efx decay (§3.1); `REVERB_FRAMES` + line-delay
  distribution for reverb room size (§3.2).
- **Accept (structural/expensive or sub-perceptual / an improvement):** block-rate
  feedback & automation re-entry (§3.1/§3.5 — smaller buffers mitigate); dual-mono (§3.6);
  dry-bus apply echo (§3.3); FIR minutiae (§3.4); FP drift (§4.2); determinism (§4.1).
- **Close only if a workflow needs it:** the `wet_filter_coeffs`/`efx_filter_coeffs`
  build-time staleness (§6) for live genome-swap workflows.
