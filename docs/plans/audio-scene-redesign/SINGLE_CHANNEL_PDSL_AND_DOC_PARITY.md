# Single-Channel PDSL Support, Lean Pattern Prep, and Doc Parity

> **Origin:** handoff request from the **ringsdesktop** side (the "Combine" tab). Filed
> 2026-06-15 against `feature/audio-scene-pdsl`. Three scoped asks, all already
> foreshadowed by this folder's existing docs — this doc collects them into one
> actionable request and points at the concrete code. Read
> [STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5 and [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §5
> first; this builds directly on both.

## Why this is being asked (the ringsdesktop motivation)

The ringsdesktop "Combine" tab renders a **single channel's** pattern to a mono clip via
`AudioScene.renderChannel(int channel, int frames, String outputPath)`
(`studio/compose/.../AudioScene.java:1224`). That method already drives the real-time
runner in an offline buffer loop:

```java
TemporalCellular cells = runnerRealTime(new MultiChannelAudioOutput(out), List.of(channel), bufferSize);
int bufferCount = (frames + bufferSize - 1) / bufferSize;   // bufferSize = DEFAULT_REALTIME_BUFFER_SIZE (4096)
setup.run();
for (int b = 0; b < bufferCount; b++) tick.run();
out.write().get().run();   // accumulate all buffers, write WAV at the end
```

**Two implications worth stating up front, because they shape the request:**

1. **The 4096-frame batch model is *not* a blocker for offline full-clip rendering.**
   `renderChannel` already renders a whole clip by ticking the buffered runner
   `ceil(frames / 4096)` times and writing the accumulated `WaveOutput` at the end —
   exactly how `AudioScenePdslCutoverTest.renderBothPaths` drives the PDSL path offline.
   So "PDSL only does real-time / batches" does not prevent its use for Combine.

2. **The only thing actually stopping Combine from using PDSL is single-channel
   support.** `renderChannel` passes `List.of(channel)`, and the runner's gate rejects
   any selection that is not a zero-based contiguous prefix of size ≥ 2, falling back to
   CellList (`AudioSceneRealtimeRunner.supportsPdsl`, `:151`). So with
   `AR_PDSL_MIXDOWN=true`, Combine silently stays on CellList.

This is **not** an intentional "single channel is unsupported" design decision — the
CellList path performs mixdown, cross-channel routing, reverb, etc. for one channel just
fine. It is a missing feature in the PDSL arg-construction plumbing. That framing is the
core of ask (1).

---

## Ask 1 — Let the PDSL path render a single channel

**Current behavior.** `AudioSceneRealtimeRunner.supportsPdsl` (`:151-156`):

```java
private boolean supportsPdsl(List<Integer> channels) {
    if (channels.size() < 2) return false;                       // <-- rejects single channel
    return IntStream.range(0, channels.size())
            .allMatch(i -> channels.get(i) != null && channels.get(i) == i);
}
```

**Root cause of the `< 2` guard** (already documented in [KNOWN_ISSUES.md](KNOWN_ISSUES.md)
§5): `MixdownManagerPdslAdapter` builds its `[channels]` and `[channels, taps]` argument
producers by concatenating one column per channel via `ADAPTER.concat(perChannel)` —
e.g. `:659`, `:708`, `:839`, `:873` — and `concat` requires **two or more** inputs. The
gene/automation reads are also positional over those concatenated banks. So a
single-channel selection both (a) trips `concat`'s arity floor and (b) needs its
positional reads to resolve to channel 0.

**The work.** Make the adapter's per-channel argument construction degenerate cleanly to
a single channel — when `perChannel.size() == 1`, build the `[1]` / `[1, taps]` bank
directly (reshape the single column) instead of calling `concat`. Then relax
`supportsPdsl` to accept `channels.size() >= 1` (keeping the zero-based-contiguous check;
a lone `[0]` satisfies it, but confirm what `renderChannel(channel, …)` passes for a
non-zero channel index — see the open question below). Per CLAUDE.md's
no-duplication rule, factor the "concat ≥2, else reshape the single column" branch into
one helper used by all four `concat(perChannel)` sites rather than repeating the guard.

**Open question for the implementer — non-zero channel indices.** `renderChannel` passes
`List.of(channel)` where `channel` may be any index (1, 2, …), but `supportsPdsl`'s
contiguity check expects `channels.get(i) == i`, i.e. `[0]`. Decide and document whether:
(a) the gene/automation positional reads must map to the *requested* channel's genes
(so a single `[channel]` selection reads channel `channel`'s volume/cutoff/transmission),
or (b) Combine's single-channel render is semantically "render this channel as channel 0."
The CellList path's existing behavior for `renderChannel(channel, …)` is the parity
reference — match whatever it does for gene selection so the PDSL output for a given
channel equals the CellList output for that same channel.

**Acceptance.** A single-channel A/B (CellList vs PDSL) on `renderChannel(channel, …)`
for at least one non-zero channel, asserting finite, non-silent, and windowed-RMS parity
in line with the multi-channel standard in [EFX_PDSL_PARITY_PLAN.md](EFX_PDSL_PARITY_PLAN.md).
Extend `AudioScenePdslCutoverTest` rather than adding a parallel harness.

---

## Ask 2 — Stop using `getCells` for pattern preparation on the PDSL path

**Current behavior** (already on the to-do list as "Lean pattern prep",
[STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5). `createPdsl` reuses
`scene.getCells(output, channels, bufferSize, …)`
(`AudioSceneRealtimeRunner.java:275`) purely to populate the render cells and the
consolidated render buffer for the Java pattern-prepare phase — but `getCells` also
builds (and the build path compiles parts of) the **Java mixdown `CellList`**, which the
PDSL path never ticks (it does all DSP in the compiled `mixdown_master[_wet]` forward).

**The work.** Extract the pattern-preparation wiring (`PatternAudioBuffer` render cells +
consolidated render buffer construction) from `getCells` so the PDSL runner can prepare
pattern audio **without** constructing the unused mixdown `CellList`. The CellList path
keeps calling the full `getCells`; the PDSL path calls only the prep half. Watch for
shared setup ordering — `createCellList` and `createPdsl` both currently rely on
`getCells` side effects (render-cell registration, buffer allocation, frame-index
wiring), so the extracted method must reproduce exactly those for the PDSL path.

**Scope note (set expectations).** Per STATE_OF_PLAY §5 this is a **build-time-only**
saving (PDSL build ≈52 s vs CellList ≈70 s today; the PDSL build already skips compiling
the CellList tick loop). It is **not** the per-tick-rate problem and will not change
realtime ratios — it removes wasted build work and the confusing "unused CellList" in the
PDSL path. Worth doing for clarity and build time; don't oversell it as a perf win.

---

## Ask 3 — Update documentation to state the true CellList-vs-PDSL parity level

The `createPdsl` javadoc is **stale** and understates how far the migration has come.
`AudioSceneRealtimeRunner.java:247-256` currently reads (paraphrased): "wire-first scope
… renders the `mixdown_master`/`mixdown_master_wet` DSP only — the full per-channel
`EfxManager` chain and the reverb network are not yet in PDSL, so it is not bit-parity
with the Java path."

That contradicts this folder's own done-record: per [STATE_OF_PLAY.md](STATE_OF_PLAY.md)
§2/§5 and [EFX_PDSL_PARITY_PLAN.md](EFX_PDSL_PARITY_PLAN.md), the **full
mixdown/efx/reverb path is migrated to PDSL and parity-validated by ear + windowed RMS**
on the real-scene A/B. So the "EfxManager chain and reverb not yet in PDSL" bullet is
wrong and should be corrected.

**Be precise — separate what is done from what is genuinely still open**, so the
corrected docs don't over-claim. As of this branch:

| Claim in stale javadoc | Reality |
|---|---|
| EfxManager per-channel chain not in PDSL | **Done** — migrated, parity-validated. |
| Reverb network not in PDSL | **Done** — `delay_network` reverb migrated, parity-validated. |
| Mono master duplicated to both writers (dual-mono) | **Still true** — true stereo is outstanding (STATE_OF_PLAY §5 "True stereo"). |
| Still builds the unused Java mixdown CellList | **Still true** until Ask 2 lands. |
| `channels ≥ 2`, zero-based contiguous required | **Still true** until Ask 1 lands. |

**Places to update** (sweep for all of them — don't fix only the javadoc):
- `AudioSceneRealtimeRunner.createPdsl` javadoc (`:247-256`) — rewrite the "wire-first
  scope" paragraph to the table above: efx/reverb DONE, dual-mono + unused-CellList +
  channel-count the genuine remaining caveats (the latter two retire as Asks 2 and 1
  land — update them in the same change that closes each).
- `AudioSceneRealtimeRunner.supportsPdsl` javadoc (`:139-150`) and the inline rationale
  in `create` (`:132-133`) — update once Ask 1 changes the channel floor.
- [KNOWN_ISSUES.md](KNOWN_ISSUES.md) §5 — the `channels ≥ 2` bullet retires with Ask 1.
- [STATE_OF_PLAY.md](STATE_OF_PLAY.md) §5 — tick off "Lean pattern prep" (Ask 2) and
  "Variable channel count"/single-channel fallback (Ask 1) as they land.
- Any `mixdown_manager.pdsl` header comments or `MixdownManagerPdslAdapter` class javadoc
  that still describe the path as effects-incomplete.

**Acceptance.** A reader of the code (javadoc) and of this folder's docs gets the same,
current answer to "what does PDSL do that CellList does, and where do they still differ?"
— no place still says efx/reverb are unmigrated.

---

## Suggested order

1. **Ask 3 first, partially** — correct the efx/reverb parity claims immediately (pure
   doc change, no risk, unblocks anyone reading the code to understand the path).
2. **Ask 1** — single-channel support (the actual ringsdesktop unblock). Update the
   channel-count caveats in the docs as part of the same change.
3. **Ask 2** — lean pattern prep. Update the "unused CellList" caveat as part of it.

Items 1–3 are independent; Asks 1 and 2 do not depend on each other.

## What this does *not* ask for

Out of scope here (tracked elsewhere in STATE_OF_PLAY §5): true stereo, flipping
`enablePdslMixdown` on by default, per-frame automation, gene HP/LP `choice()`, and the
a1/a2/a3 ring decoupling. Single-channel Combine rendering does not need any of them —
it needs the channel floor lowered and honest docs.
