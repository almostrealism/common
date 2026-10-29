---
status: proposed
created: 2026-10-29
closed:
tracker: [d8ad5001-7d94-42c3-8a3b-51277dbcdc86]
---

# Make the self-hosted LM's key/value-cache decode faster than the sliding window

## Category

Performance (serving the self-hosted LM generation proof-of-value arc)

## Motivation

The self-hosted language model is the first concrete artifact of the project's
self-understanding aim: software that writes about its own architecture. The decode
path that produces those bytes is the thing standing between "the platform can describe
itself" and "the platform can describe itself at a usable rate."

The single-position key/value-cache decode (`KeyValueCacheAutoregressiveModel`, built by
`CausalLanguageModel.decodeModel` over the PDSL `attention` layer in
`engine/ml/src/main/resources/pdsl/attention.pdsl`) was introduced precisely because it
does **1/64th** of the arithmetic per byte that the full-forward sliding window does — it
reads cached keys and values instead of recomputing the whole context on every step. The
premise of the entire key/value-cache cycle was that this makes generation cheaper per
byte.

The `CausalLanguageModelDecodeThroughputTest` benchmark that landed with PR #768 measured
that premise for the first time, and it is **false as implemented**. On Metal, the cache
path is about **4× slower** than the sliding window:

| | sliding window | key/value cache |
|---|---|---|
| bytes/s | ~190–198 | ~50 |
| kernel runs/byte (`OperationProfileNode.getRunCount`) | 86 | 109 |
| Metal dispatches/byte | 135 | 181 |
| Metal commits/byte | 7 | 45 |

The path that does far less arithmetic issues *more* kernels, dispatches and commits per
byte. The benchmark attributed the cause (`MetalCommandRunner.hostCompleteRequesters`
delta, logged as `commitForcingWaits=`): the cache path takes **~30 host-blocking buffer
copies per byte** attributed to `mtlCopy` (`MetalComputeContext.copy`), plus a batch of
waits attributed to a `collectionConcatenate` computation — against **1** `mtlCopy` per
byte on the sliding path (the token load). Each host-waiting copy drains the open Metal
command buffer and forces a commit, so the GPU never overlaps work with the CPU encoding
the next step: the decode is dispatch- and host-wait-bound, not arithmetic-bound.

This is the sharpest lever on the board for the central vision, and it is the step the
Manager Log has earmarked as the next performance cycle ever since the throughput
measurement landed. The preceding cycle deliberately fixed the decode model's cache
*ownership* first (`53cb34deb`, PR #773) so this task inherits a decode graph whose cache
buffers have a clear owner.

## Scope

Concrete, actionable items:

1. **Re-measure on current `master` first.** `CausalLanguageModelDecodeThroughputTest`
   and the `MetalCommandRunner` held-buffer backpressure work
   (`MetalCommandRunner.MAX_HELD`, the `PendingBridges`/`CommittedBuffer` rewrite) both
   landed close together. Run the benchmark on the exact HEAD this task builds on and
   record the current bytes/s, `runsPerByte`, `dispatchesPerByte`, `commitsPerByte` and
   the `commitForcingWaits=` breakdown for both paths. The numbers in the table above are
   the premise; confirm they still hold before attacking them.

2. **Locate the ~30 host-blocking copies in the compiled decode graph** using
   `ar-profile-analyzer` on the benchmark's recorded operation profile (Rule 3b:
   profile-analyzer before any hand-instrumentation). Read the generated kernel source and
   argument bindings to attribute each `mtlCopy`/`collectionConcatenate` host wait to a
   construct in `attention.pdsl`. The candidates named by the measurement are the
   `branch keys` / `branch values` stages — `dense → rotate_heads/reshape → repeat_each →
   cache_write(cache, position)` — and the residual wrappers that join a branch's output
   back onto the main path.

3. **Remove or batch those copies** so they stop host-blocking. The target is that each
   per-step buffer copy either (a) is expressed as a dispatch that chains on the
   surrounding work through the existing `Submittable.submit(...)` completion-semaphore
   path (so it batches into the open command buffer instead of draining it), or (b) is
   eliminated where the copy is only there to materialize a view that the consumer could
   read directly. `cache_write` writing row `position` of a caller-owned
   `[seq_len, dim]` cache, and `repeat_each` duplicating KV heads for grouped-query
   attention, are the first places to look: a write into a persistent buffer and a
   broadcast should not require a host round-trip.

4. **Keep the decode numerically identical.** The existing
   `keyValueCacheDecodeMatchesFullForward` assertion (byte-identical to the full forward
   at every position) and `CausalLanguageModelDecodeThroughputTest`'s own path-agreement
   check must still pass unchanged. This is a dispatch/scheduling change, not a change to
   what the graph computes.

5. **Add the backend-independent regression assertion.** Once the cache path is ahead,
   add an assertion to `CausalLanguageModelDecodeThroughputTest.keyValueCacheDecodeThroughput`
   that the cache path invokes **strictly fewer kernels per byte** than the sliding window
   (`cache.runsPerByte() < sliding.runsPerByte()`). `runsPerByte` is derived from
   `OperationProfileNode.getRunCount()` and is backend-independent, so the gate is not a
   flaky wall-clock timing threshold and cannot be satisfied by weakening a tolerance.
   Continue to *report* bytes/s, dispatches/byte and commits/byte without asserting a
   host-dependent threshold on them.

6. **Rewrite the Generation figures.** Update the Generation section of
   `docs/internals/training-a-language-model.md` with the new measured figures so the
   documentation states the cache path's actual throughput and speedup, drift-guarded by
   the benchmark.

## Approach

- **Evidence first, mechanism second.** This is a "computes-the-wrong-cost" investigation,
  which Rule 3b assigns to `ar-profile-analyzer` before any framework `log()` probe. Load
  the benchmark's profile, walk the compiled operation tree (`list_children`), find the
  slow/host-waiting operations (`find_slowest`, `get_source`), and read the argument
  offsets to see exactly which copy each host wait corresponds to. Do not theorize about
  execution ordering — read it off the generated source.

- **Fix at the construct, not at the caller.** When a host-blocking copy is traced to a
  PDSL builtin (`cache_write`, `repeat_each`, a reshape view, `collectionConcatenate`),
  the fix belongs on that construct / its lowering so every model that uses the
  `attention` layer family benefits — `Llama2`, `SkyTntMidi`, `MoonbeamMidi`, `Qwen3` all
  decode through `attention.pdsl`. Avoid a decode-model-specific special case.

- **Honor the asynchronous-submission model.** The correct shape for a copy that must
  happen is to chain it behind the dispatch's completion via `Submittable.submit(...)`,
  never a caller-side host wait and never a backend-conditioned branch. If chaining a copy
  is what forces the commit, the receiver (the copy's own submission path) is where to look
  — consistent with the platform's asynchronous-submission guidance.

- **Scope discipline.** If the investigation shows a host wait that is genuinely required
  for correctness on Metal (e.g. a cross-provider copy that cannot yet be deferred), record
  it as an evidenced follow-up with the profile reference rather than forcing it; the
  success bar is the backend-independent `runsPerByte` reduction, which does not depend on
  any single copy being removed.

## Success Criteria

- `CausalLanguageModelDecodeThroughputTest.keyValueCacheDecodeThroughput` reports the
  cache path ahead of the sliding window on bytes/s **and** on `runsPerByte` /
  `dispatchesPerByte` on Metal.
- The test carries a new, passing assertion that `cache.runsPerByte() < sliding.runsPerByte()`
  (backend-independent), in addition to the existing path-agreement and `runsPerByte > 0`
  checks.
- `keyValueCacheDecodeMatchesFullForward` and the benchmark's path-agreement check still
  pass unchanged — the decode is byte-identical to before.
- The Generation section of `docs/internals/training-a-language-model.md` states the new
  measured cache-path throughput and speedup.
- `mcp__ar-build-validator__start_validation` is clean (checkstyle, code_policy,
  test_timeouts, duplicate_code).

## Dependencies

- **A macOS / Metal node.** The ~30 host-blocking copies, the commit counts and the
  bytes/s comparison are Metal-backend phenomena measured by `MetalCommandRunner`
  counters; the investigation and verification must run on Metal. A workstream settings
  file (`docs/plans/plan-20261029-151904-workstream.yaml`, `requiredLabels: platform:
  macos`) accompanies this plan so the implementation runs on a matching machine. The
  final `runsPerByte` assertion is backend-independent and runs anywhere, but reaching it
  requires measuring on Metal.
- Builds on the landed cache-ownership fix (PR #773), which gives the decode graph's
  cache buffers a clear owner.
- No owner-provided data or external downloads required.

## Estimated Complexity

Medium–Large. The measurement harness, the byte-identical correctness guard and the
`runsPerByte` counter already exist, which bounds the work. The depth is in the
`ar-profile-analyzer` attribution of each host-blocking copy to a PDSL construct and in
making that construct's lowering batch instead of host-block without changing what it
computes — a scheduling change to shared infrastructure that every attention-based model
decodes through, so it needs its own verification.

## What comes next

- **Proof of value, if the cap then binds:** a sliding (rolling, position-remapped)
  key/value cache so long-form generation runs at cache speed past `seqLen`. Explicitly
  deferred in the decode-throughput plan until the cache path is faster than the sliding
  window at every length — which is exactly what this task establishes.
- **Proof of value, connectome:** the MaleCNS ingest into CSR form and the Shiu et al.
  reproduction, once the owner provides the MaleCNS tables and a Brian2 reference.
- **Proof of value:** conditional diffusion training.
- **Performance:** the reduced form for the weighted-sum `matmul` lowering (outputs wider
  than 1000); and a reduced form for the convolution input gradient, which still contracts
  the full Jacobian.
