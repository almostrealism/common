# Variable Note Scheduling in a Fixed-Shape Batched Kernel

> The "pad-to-max vs pitch-class tiling" question, resolved. The framing was
> misleading: it bundled **three independent axes** of per-note variability into
> one decision. Two are already solved; only one (temporal placement) is a real
> design choice, and the real-time streaming model shrinks even that. Companion
> to [`NOTE_GRAPH_SHAPES.md`](NOTE_GRAPH_SHAPES.md). Evidence cited to source and
> to the `PATTERN_SYSTEM_PHASE3_DESIGN.md` / `PATTERN_RENDERING_FLOOR.md` prior
> art so we do not re-derive it.

---

## 0. The three axes (separate them)

A batched kernel wants a fixed shape `[N, W]`. Across the N notes in a batch,
three things vary independently:

| Axis | What varies | Status |
|------|-------------|--------|
| **Pitch** | resample ratio (note's key vs sample's root) | **Already solved** — per-note `[N]` ratio tensor |
| **Length** | how many frames the note sounds | **Decided** — uniform row width + mask; the envelope *is* the duration |
| **Placement** | where in the output window the note sits (start offset) | **The one real decision** |

"Pitch-class tiling" addresses only the Pitch axis, and only as a *caching*
optimization — it is not required. "Pad-to-max" addresses Length and Placement.
Conflating them is what made this look like one hard problem.

---

## 1. Pitch — already solved, no tiling needed

`BatchedPatternRenderer.buildBatchedChain` reads each note's source at
`sampleIdx × ratios[noteIdx]` (`BatchedPatternRenderer.java:169-181`). Pitch is a
per-row `[N]` data input; the compiled kernel is identical regardless of the
ratios. Per `PATTERN_SYSTEM_PHASE3_DESIGN.md §1.1`: "the kernel arithmetic is
identical but the ratio comes from a per-note input rather than a constant."

**Decision:** per-note `[N]` ratio tensor. **Pitch-class tiling is deferred** as
an optional resample-reuse optimization; it is not on the correctness or
ratio-of-1 path.

---

## 2. Length — uniform rows, envelope encodes duration

The per-note path today renders the note and sums an overlap slice
(`PatternFeatures.sumToDestination`, `PatternFeatures.java:315-343`):
`sourceOffset = overlapStart - noteStart`, `destOffset = overlapStart -
startFrame`, summed via `AudioProcessingUtils.getSum()` (element-wise add over a
`range(...)` slice).

`PATTERN_SYSTEM_PHASE3_DESIGN.md §5.4` settles the batched form: uniform-width
rows; a note shorter than the row zero-pads after it ends; **the volume
envelope's release brings the row to silence at the note's natural end — "the
envelopes are the duration,"** so no separate duration tensor is needed. The
filter-envelope's padded-row FIR (`±filterOrder/2 = 20` zeros per row,
`BatchedPatternRenderer.java:190-197`) is an orthogonal, already-solved padding
that prevents cross-row FIR bleed.

**Decision:** uniform row width `W`; per-note length handled by the envelope
(which we already compute per note) plus a cheap validity mask for the resample
gather. No explicit duration input.

---

## 3. Placement — the one real decision (and why it's small here)

### 3.1 What the existing kernel does vs. what production needs

The merged kernel's final stage (`BatchedPatternRenderer.java:208-209`) is
`permute [N,W] → [W,N]; traverse(1).sum()` — an **aligned** reduce that assumes
every note starts at output frame 0. Production notes start at different offsets
(`PATTERN_SYSTEM_PHASE3_DESIGN.md §1.4`). The aligned reduce is the special case
of an offset-aware **scatter-add** where every `destOffset = 0`. So the missing
piece is: replace the aligned reduce with `out[destOffset[n] + k] += row[n,k]`.

### 3.2 The framing clarification that shrinks the problem

The prior design reasoned mostly in the **optimizer framing**: render a whole
32-measure chunk at once → up to ~2048 notes/tick → placement across a huge tick
width → real memory concern. But the real-time goal is the **streaming framing**
(`AudioScene.runnerRealTime`): a3 renders one buffer of `bufferSize` frames
(1024/4096) per tick, ~93 ms budget, and a2 feeds it.

In the streaming framing:
- The batch is the notes **active in one window** `W` (= a3 buffer, or a small
  multiple if a2 runs ahead). At `bufferSize = 4096` (~93 ms) the active-note
  count is **polyphony-bounded — tens, not thousands.** The 2048-notes figure is
  the offline whole-arrangement render, not a real-time buffer.
- **Most active notes started in an earlier buffer and continue** → their
  `destOffset = 0`, and only their *sampling offset within the note* advances
  (the existing `offsetArg`/`sampling()` mechanism,
  `SamplingFeatures.java:188-191`, `RenderedNoteAudio.offsetArg`). Only
  **newly-triggered** notes have `destOffset > 0`.

So scatter matters for a minority of notes per buffer, the batch is small, and
even a handful-to-dozens of notes batched to one dispatch is far under budget.

### 3.3 Decision

Set **row width `W` = the a2 window = `bufferSize`** for the first cut (a2 runs
one buffer ahead of a3). Each note's row is its audio for *this* window, taken at
the note's current sampling offset (continuing notes) and placed at its
`destOffset` (new notes). Then:

- **Replace the aligned final reduce with a scatter-add** keyed by a per-note
  `[N]` `destOffset` tensor — i.e. the batched form of today's
  `dest.range(shape, destOffset) += audio.range(shape, sourceOffset)`. This is
  the single new primitive; it *generalizes* the existing reduce (offset 0 ⇒
  current behavior), so the proven RMS=0 chain upstream of it is untouched.
- Envelopes stay **note-local** `[N, W]` (evaluated over `[elapsed_n, elapsed_n +
  W)` via the existing offset mechanism) and are applied **before** the scatter,
  so the postmortem's "reuse the proven kernel" rule holds.

**Deferred (all optimizations, none on the first-cut path):**
- Larger a2 windows (multi-buffer / seconds) for more dispatch amortization —
  grow `W` once per-buffer is proven.
- Sub-batching notes by offset (`§1.4` option b) and the optimizer-scale
  32-measure reduction — only if profiling shows the scatter or zero-row cost.
- Pitch-class tiling (resample caching).

---

## 4. The first-cut kernel, end to end (melodic SSS note)

```
per active note n in window W (= bufferSize):
  for layer i∈{0,1,2}:
      resampleᵢ = gather(sourceᵢ[n] at samplingOffset[n] + k·ratioᵢ[n]), masked to note length
      Lᵢ        = resampleᵢ · perLayerEnvᵢ[n]            ← [N, W] per-layer envelope
  merged[n]   = L0 + L1 + L2                              ← SSS = plain sum
  filtered[n] = paddedRowFIR(merged[n], filterCutoffEnv[n])   ← reused back-half
  voiced[n]   = filtered[n] · volumeEnv[n]                     ← reused back-half
out[f] = Σ_n  scatter(voiced[n], destOffset[n])          ← NEW: offset-aware scatter-add
                                                            (aligned reduce is the destOffset≡0 case)
```

**Per-note input record (flat, `[N]`/`[N,W]` tensors):**
`3×{ sourceBuffer, pitchRatio, perLayerEnvParams }`, `filterEnvParams`,
`volumeEnvParams`, **`samplingOffset` (frames into the note)**, **`destOffset`
(frames into the window)**, `length` (for the mask). All gatherable across the N
active notes; no `aggregationChoice` (fixed SSS), no synth, no FREQUENCY/VolEnv.

---

## 5. Spikes to resolve during implementation (small, bounded)

1. **Scatter-add correctness.** Implement the offset-aware scatter-add and prove
   RMS = 0 vs the per-note `sumToDestination` path, including notes with
   `destOffset > 0` (new) and `samplingOffset > 0` (continuing).
2. **Scatter-add cost at `W = bufferSize`.** Spot-check the scatter cost; the
   prior art only validated the *aligned* reduce at `NOTE_SIZE = 1024`
   (`PATTERN_SYSTEM_PHASE3_DESIGN.md §7`).
3. **Active-note count distribution.** Measure how many notes are actually active
   per `bufferSize` window in production scenes — confirms the batch is small and
   informs whether `W` should grow for amortization.
4. **Envelope-in-window evaluation.** Confirm the existing `offsetArg`/`sampling`
   mechanism yields the correct `[N, W]` envelope slice for a note whose ADSR
   started buffers ago (continuing-note tail).

---

## 6. Summary of decisions

| Axis | Decision | Deferred |
|------|----------|----------|
| Pitch | per-note `[N]` ratio tensor | pitch-class tiling (resample cache) |
| Length | uniform `W`-wide rows; envelope = duration; validity mask | — |
| Placement | scatter-add by per-note `[N]` `destOffset`; generalizes the aligned reduce | sub-batch-by-offset; optimizer-scale reduction |
| Window `W` | `= bufferSize` (a2 one buffer ahead of a3) | multi-buffer/seconds windows for amortization |
| Batch contents | notes active in the window (polyphony-bounded, tens) | the 2048-note optimizer-scale batch |

The residual §8 question in `NOTE_GRAPH_SHAPES.md` is now answered: within the
melodic SSS class, pitch is a data input, length is the envelope, and placement
is a scatter-add that generalizes the kernel's existing reduce. No tiling, no new
arbitrary-graph machinery.
