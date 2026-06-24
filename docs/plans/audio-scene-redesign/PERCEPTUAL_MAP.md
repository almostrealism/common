# Perceptual Map — What the PDSL/Legacy Differences Sound Like

This is the **listener's companion** to
[PDSL_SIGNAL_PATH_DIFFERENCES.md](PDSL_SIGNAL_PATH_DIFFERENCES.md). That document is
organized by *mechanism* (what the code does differently); this one is organized by
*symptom* (what a human would hear) so it can be opened during an A/B listening session.
Every entry points back to the inventory section that explains the mechanism and names
the lever.

It exists because the two paths are now close enough that "this part is obviously broken"
no longer applies — the remaining differences read as *character/colour* changes
("the FX feedback has a different colour than before"), and those are hard to localize by
ear without a map from symptom to cause.

**How to use it.** Find the thing you are hearing in the quick table, jump to the detail
section for the mechanism and magnitude, then decide using the triage in the last section
whether it is worth chasing toward the legacy sound or accepting.

**Where to listen.** All the FX-character differences live on the **wet/reverb channels**,
not everywhere. With default settings the routing is `wetChannels = [2,3,4,5]` (efx bus)
and `reverbChannels = [1,2,3,4,5]` (reverb send); channel 0 is essentially dry and will be
the *least* changed. So if you want to isolate the efx-feedback character, render a wet
channel (e.g. `AR_GENERATE_CHANNEL=2`); to isolate the reverb, listen to a reverb-only
channel. A channel that is neither wet nor reverb should be nearly indistinguishable
between the two paths.

> Caveat: these are *predictions from the DSP*, not measured listening results. Each is
> tied to a concrete mechanism so it can be confirmed or falsified at the console. Where a
> difference was already judged sub-perceptual on the 40s review render, that is noted.

---

## Quick table — symptom → cause

| What you hear | Most likely cause | Inventory § | Magnitude | Move toward legacy by |
|---|---|---|---|---|
| FX **feedback/echo has a different colour** — darker, "warmer", or muddier than before | Efx feedback filter is **LP-only** (gene HP/LP `choice()` not yet in compiled models), so a gene that asked for a high-pass on the return gets a low-pass instead | §3.4 | **Largest character difference** | Implement PDSL `choice()` so the efx gene filter honours HP/LP |
| FX echoes feel **uniform / "in sync" / metallic-fluttery**, less spread out than before | Per-channel feedback delay is a **static 6500 samples (~147 ms)** for every channel, replacing the gene-driven `AdjustableDelayCell` times | §3.4 | Large | Wire the gene delay times into `efx_fb_delay` |
| The wash is **flatter across channels** — channels that used to "bloom" differently now feed back about the same | Feedback injection level is a **static diagonal at `wetLevel` (0.5)**, replacing the gene-driven `fc(wetOut)` per-channel level | §3.4 | Medium | Wire `fc(wetOut)` into `efx_fb_passthrough` |
| Feedback **regeneration is steppy / coarser**, repeats land on a grid | Feedback re-enters **once per buffer** (block-parallel) vs per sample; grid ≈ 186 ms @ 8192, 93 ms @ 4096 | §3.4, §4.3 | Medium (buffer-size dependent) | Smaller buffer reduces the grid; true per-sample needs in-kernel recurrence (expensive) |
| Feedback **decays at a different rate** (tamer, or doesn't sustain like it did) | Transmission matrix scaled by **`feedbackGain/channels` (0.6/N)** for stability — preserves routing pattern, not exact decay | §3.4 | Medium | Tune `feedbackGain` toward the legacy decay (bounded by stability) |
| Reverb sounds like a **different (smaller, more regular) room** — shorter tail, less long shimmer | PDSL reverb uses **short deterministic line delays** (≈0.11–0.29 s) and a **2-buffer ring** (~0.37 s max), feedback stepped per buffer | §3.5 | Medium "different but plausible room" | Extend `REVERB_FRAMES`; match the line-delay distribution |
| Reverb **no longer changes when you re-run** the same scene | PDSL reverb is **deterministic**; legacy randomizes delay lengths every run | §2.1 | Workflow change, not a defect | (Intentional; legacy was the random one) |
| Dry signal is **slightly thinner / less ambient** on echo-heavy parts | The per-channel `EfxManager.apply` echo is **absent from the dry bus** (~6% quieter) | §3.1 | Subtle (was the residual in parity numbers) | Add a multi-frame comb ring to the dry bus |
| Filter **sweeps differ subtly** in shape; a downward sweep leaks a touch more low end | Filters are **41-tap FIR approximations** of the legacy per-sample IIR; very low cutoffs truncate (DC leak / shallower ultimate slope) | §3.3 | Sub-perceptual on review material | More FIR taps; cutoffs already quantized below JND |
| **Zipper / stair-stepping** on fast modulation (not on slow envelopes) | Automation is **stepped per buffer** (~93–186 ms), not per frame | §3.2, §4.3 | Inaudible at production envelope rates; audible only on fast LFO-style automation | Per-frame automation (deliberately not done; revisit only if audible) |
| No **stereo width** difference now, but also no widening capability | PDSL renders **dual-mono** (one master to both sides) | §3.6 | None on current content (no pan in either path) | Add a pan stage (new capability, not parity) |
| The same scene sounds **~1–3% different in level on another machine** | FP reduction reordering across binaries/drivers compounds in feedback loops | §2.2 | Not audible as such; explains RMS drift | (Inherent; both paths affected) |
| When sweeping genomes **live** (optimizer), the FX **tone seems "stuck"** relative to the rest | `wet_filter_coeffs` / `efx_filter_coeffs` are **sampled at build time**; they lag the genome until the runner is rebuilt | §5 | Only in live genome-swap workflows | Move those coefficient banks to per-buffer slot refresh, or rebuild per genome |

---

## The big one: the FX feedback "colour" (inventory §3.4)

This is almost certainly the difference being described as *"some feedback in the FX that
has a different character/colour than before."* The efx feedback grid is the place where
the PDSL path makes the most approximations, and four of them stack on the **same signal**
(the wet-bus regeneration), so their effects combine into one audibly different texture.
Taken together they predict a feedback wash that is **more uniform, fixed in time, darker,
and coarser** than the legacy one. Breaking it apart:

1. **LP-only return filter (darker colour).** The legacy efx return runs the gene's chosen
   filter, which can be a *high-pass*. A high-pass on a feedback path thins each repeat and
   keeps the tail bright and articulate. PDSL currently can only apply a **low-pass** there
   (the in-graph `choice()` that would pick HP vs LP is an open limitation), so on any gene
   that wanted HP, the feedback comes back **darker/duller/"warmer"** and accumulates
   low-mid energy with each pass — the classic "muddy feedback" signature. This is the most
   likely single cause of a "different colour" impression. *Lever: implement `choice()` in
   compiled models so the gene filter type is honoured.*

2. **One fixed delay time for every channel (uniform/metallic).** Legacy gives each channel
   its own gene-driven delay (`AdjustableDelayCell`), so the repeats across channels are
   spread out and detuned relative to each other — that spread is what makes a multi-channel
   feedback wash sound rich and diffuse. PDSL uses a **single static delay (~147 ms) on
   every channel**, so the repeats line up at the same interval. Predicted result: a more
   **comb-filtered, "in-sync", metallic or flutter** character instead of a spread, chorused
   one. *Lever: feed the gene delay times into `efx_fb_delay`.*

3. **Uniform feedback level across channels (flatter image).** Legacy sets each channel's
   feedback injection from `fc(wetOut)` (gene-shaped, per channel); PDSL uses a **static
   0.5 on the diagonal**. So channels that should regenerate a lot and channels that should
   barely regenerate now do so about equally — the **per-channel contrast of the wash
   flattens**, and the parts that used to "bloom" differently sound more alike. *Lever: wire
   `fc(wetOut)` into `efx_fb_passthrough`.*

4. **Block-rate re-entry + contraction scaling (coarser, tamer).** Legacy feedback is a
   per-sample recurrence; PDSL re-enters **once per buffer** and scales the transmission
   matrix by `feedbackGain/channels` to stay stable under that quantization. Net: the
   regeneration is **steppier** (repeats quantized to the ~93–186 ms buffer grid) and decays
   at a **somewhat different, more controlled rate** than the gene intended. *Levers: smaller
   buffer for a finer grid; tune `feedbackGain` for decay; true per-sample re-entry is a
   structural change (in-kernel recurrence) and is the expensive option.*

**What to expect after fixing 1–3 (the cheap, high-impact ones):** the feedback would
regain its gene-driven brightness (HP returns), its spread/diffusion (varied delays), and
its per-channel depth (varied levels) — recovering most of the "character" while leaving
only the block-rate coarseness (4) as a residual structural difference.

---

## The reverb room (inventory §3.5)

The reverb level matches (same injection gain, same output mean, same feedback radius —
this was one of the six parity fixes), so it will not sound *louder or quieter*; it will
sound like a **different room**. The PDSL line delays are short (≈0.11–0.29 s) and the ring
is only two buffers (~0.37 s), with feedback stepped per buffer, so the tail is
**shorter-range and more regular**: denser early reflections, less long-tail shimmer — a
smaller, "boxier" but plausible space. The legacy network's random 0.15–1.5 s delays give a
longer, more diffuse, and run-to-run-varying tail.

Two consequences worth separating by ear:
- **Room size/decay** — addressable by extending `REVERB_FRAMES` (longer ring → longer
  tail) and by matching the line-delay distribution. This is a knob, not a rewrite.
- **Determinism** — the PDSL tail is identical every run; the legacy tail re-rolls. If a
  particular legacy reverb take is being used as the reference, remember it was one random
  draw; the PDSL one is fixed. (§2.1.)

---

## The subtle / sub-perceptual ones

These were measured or judged sub-perceptual on the 40s review and are listed so they are
not mistaken for the cause of a larger character change:

- **Dry bus ~6% quieter on echo-heavy channels** (§3.1) — a slight loss of "size"/ambience
  on the dry signal, because the per-channel apply echo is only on the wet arm. Subtle; it
  was the residual in the final windowed-parity numbers.
- **FIR vs IIR filter shape** (§3.3) — sweep phase/shape differs slightly; very low cutoffs
  leak a little DC / have a shallower ultimate slope (41 taps can't represent a very long
  impulse response). Cutoff is quantized to ~0.75% bins, well below the just-noticeable
  difference for filter cutoff.
- **Per-buffer automation stepping** (§3.2/§4.3) — inaudible on the slow production
  envelopes; only a fast LFO-style automation would zipper. If future material adds fast
  modulation, this moves up the list.
- **1–3% binary-to-binary level drift** (§2.2) — not an audible artifact in itself; it
  explains why RMS measurements wander a little across machines/drivers.

---

## Triage — what to chase vs accept

A practical ordering for "perfectly replicate vs accept a high-impact difference":

**Chase first (high perceptual impact, mechanically tractable — restores gene intent on the
efx bus):**
1. **Gene HP/LP `choice()` on the efx return** (§3.4 #1) — recovers feedback *brightness/
   colour*. Biggest perceived win.
2. **Gene-driven `efx_fb_delay`** (§3.4 #2) — recovers feedback *spread/diffusion*.
3. **Gene-driven `efx_fb_passthrough` = `fc(wetOut)`** (§3.4 #3) — recovers *per-channel
   depth* of the wash.

**Tune (cheap knobs, dial to taste against the legacy reference):**
4. `feedbackGain` for efx decay rate (§3.4 #4); `REVERB_FRAMES` and line-delay distribution
   for reverb room size (§3.5).

**Accept for now (structural/expensive, or genuinely sub-perceptual / an improvement):**
- Block-rate feedback/automation re-entry (§3.2/§3.4/§4.3) — inherent to the block-parallel
  design; per-sample recurrence is a large structural change. Smaller buffers mitigate.
- Dual-mono master (§3.6) — true stereo is new capability, not parity; no audible loss on
  current (pan-free) content.
- Dry-bus apply echo (§3.1), FIR filter minutiae (§3.3), FP drift (§2.2), determinism
  (§2.1) — sub-perceptual or a behavioural improvement.

The headline: most of what reads as a *different FX colour* is concentrated in three
efx-feedback approximations (LP-only filter, fixed delay, fixed level), each of which is a
known stand-in for a gene-driven value and can be closed one at a time — so this is a
tractable "recover the character" list, not an unbounded chase.
