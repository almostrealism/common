---
status: in-progress
created: 2026-10-28
closed:
tracker: [d8ad5001-7d94-42c3-8a3b-51277dbcdc86, 9825196c-0c15-44c5-90d6-475aa0d503ff]
---

# Measure the self-hosted LM's key/value-cache decode speed, and generate prose about the platform at that speed

**Category:** Proof of Value (self-hosted LM generation)
**Estimated complexity:** Medium
**Runs on:** a macOS / Metal node (see `plan-20261028-004435-workstream.yaml`). The comparison is
measured against the Metal reference the documented ~100 bytes/second figure was measured on, and
the profile harness this plan reuses reports Metal dispatch and command-buffer commit counts.

## Motivation

For the better part of two months the self-hosted language-model track has been building toward one
thing: the platform writing readable prose about itself, at a speed that makes it usable. The pieces
are now all on `master`:

- it trains from scratch and beats its unigram baseline (`docs/internals/training-a-language-model.md`);
- it generates a readable continuation greedily (`SlidingWindowAutoregressiveModel`);
- it samples with temperature and nucleus (top-p) filtering through one shared `sampleToken` path;
- and, most recently, it decodes **one token at a time against a per-layer key/value cache**
  (`KeyValueCacheAutoregressiveModel`, built by `CausalLanguageModel.decodeModel`), verified
  byte-for-byte identical to a full forward pass by
  `CausalLanguageModelTest.keyValueCacheDecodeMatchesFullForward`.

The KV-cache decode was added precisely because sliding-window decode re-runs a full
`(seqLen, vocabSize)` forward pass per byte. The Generation section of
`training-a-language-model.md` states that cost for the trained documentation-run configuration
(byte vocabulary 256, context 64, embed 64, depth 2; 115,008 parameters) — "one full forward pass
per byte, about 35 to 45 bytes per second" where the sliding window is introduced, and "about 100
bytes per second" where `trainOnDocumentation` generates from the reloaded checkpoint — and then
says only that the key/value-cache decode "avoids it." **The section never says by how much the
cache improves on either.** There is no throughput figure for the cache path anywhere in the corpus,
and no benchmark that measures the two decode paths against each other. The one performance claim the
whole KV-cache cycle was justified by is, today, unquantified. (The two sliding-window figures
quoted above describe the same configuration yet differ by more than a factor of two; this benchmark
is also the first chance to reconcile them — see "Open questions".)

This is the measurement that cashes the arc. It is the proof-of-value step the Manager Log has
telegraphed in its "what comes next" list five cycles running ("measure the throughput improvement
over full-forward decode, and a greedy-vs-top-p comparison over long samples now that decoding is
cheap enough to run at length"). It turns "we built a cache" into "the self-hosted model now writes
about the platform at *N* bytes per second, *K*× faster than before," with a committed benchmark
that keeps the number honest. For the self-understanding aim it is the concrete confirmation that the
software can now describe its own architecture at a usable rate — the first time that is true, and
measured.

## Scope

Concrete, bounded, and reusing what exists. No new production class, no new concept — one benchmark
test and a documentation rewrite grounded in its output.

### 1. A decode-throughput benchmark: `CausalLanguageModelDecodeThroughputTest` (`engine/ml`)

Modeled directly on the existing `CausalLanguageModelForwardProfileTest` (same
`OperationProfileNode` + `Hardware.assignProfile` + `MetalCommandRunner.resetBatchSizeCounters()` +
wall-clock-loop pattern, writing its profile to `results/causal_lm_decode_throughput.xml`). It:

1. Builds a `CausalLanguageModel` with random weights at a configuration whose `seqLen`
   lets the cache path run a meaningful number of positions (the cache path decodes positions
   `0 .. seqLen - 1`; it deliberately refuses a step at or beyond `seqLen`, so the measured run
   stays inside the cache's length — see the `seqLen`-cap note in item 4 below). The **speedup** and
   per-byte dispatch ratio this reports are properties of the two decode paths and carry across
   configurations; an **absolute** bytes/second is comparable to the documentation run's figure only
   when measured at (or close to) that configuration (byte vocabulary 256, context 64, embed 64,
   depth 2), which random weights permit since throughput does not depend on the weight values. Which
   configuration to run, and whether it fits the CI time and Metal-node budget, is an open question
   below — but it must be decided before the benchmark's absolute number can be quoted in the
   documentation.
2. Warms up each generator once (first-call compilation is not part of steady-state throughput),
   then measures, over the same prompt and token budget:
   - `SlidingWindowAutoregressiveModel` decode: bytes/second, and the Metal dispatch and
     command-buffer commit counts per byte from the profile;
   - `KeyValueCacheAutoregressiveModel` decode: the same metrics.
3. Reports the **speedup** (sliding-window seconds-per-byte ÷ cache seconds-per-byte) and the
   per-byte dispatch-count ratio, and writes all of it through `log(...)` and into the profile XML.
4. Asserts the **structural** property that makes the cache worth having and cannot be gamed by host
   timing noise: the cache path issues strictly fewer kernels/dispatches per decoded byte than the
   sliding-window path. Wall-clock numbers are **reported, not asserted** (host timing varies by
   node), exactly as `CausalLanguageModelForwardProfileTest` reports its per-pass time rather than
   asserting a threshold. This keeps the benchmark a stable CI citizen while still producing the
   figure the documentation needs.
5. Reuses `CausalLanguageModelTest`'s `generate(generator, prompt, n)` helper pattern (lift the
   shared helper to a place both tests can call, or replicate the minimal loop) so the two tests
   measure the same decode path the correctness tests pin.

### 2. A short greedy-vs-nucleus demonstration over a real continuation

Within the same test (or a sibling method), continue a fixed documentation prompt with the trained
decode path at (a) temperature 0 (greedy), and (b) a positive temperature with `setTopP` at a
representative nucleus threshold, from the same seed. Log both continuations and whether each is
valid UTF-8 under a strict decoder (reported, not asserted — a byte-level model may emit a partial
multi-byte sequence, the same stance `trainOnDocumentation` already takes). This demonstrates that
the nucleus-sampling path and the cache path compose. It does **not** re-assert generation quality —
`trainOnDocumentation` owns that bar and must not be weakened or duplicated here. Note that with the
random-weight benchmark model the continuation shows only that the two sampling modes compose with
the cache, not readable prose; a *readable* greedy-vs-nucleus sample for the documentation requires
the trained checkpoint `trainOnDocumentation` reloads, so decide (with the configuration question
below) whether this demonstration reuses that checkpoint or stays a composition check over random
weights.

### 3. Rewrite the Generation section's throughput claim in `training-a-language-model.md`

Replace the unquantified "the single-position key/value-cache decode avoids it" with the measured
result from the benchmark. The **speedup** (cache vs sliding window on the same model) and the
**per-byte dispatch-count reduction** are the portable claims and should be stated as such — they
explain *why* the cache is faster (its cost per byte is one position, not one `seqLen`-wide window)
and do not depend on which configuration was benchmarked. An **absolute** cache bytes/second may be
stated next to the sliding window's figure only if the benchmark was run at a configuration
comparable to the documentation run's; if it was run at a smaller configuration instead, give the
speedup and dispatch ratio and label the absolute number as the benchmark model's, not the
documentation model's — do not present a small-model throughput as the trained configuration's. If
this rewrite resolves the existing 35–45-vs-100 bytes/second discrepancy (see "Open questions"),
make the surviving figure consistent across the section rather than leaving both. State the
`seqLen` cap plainly as the reason long-form generation past the trained context still falls back to
the sliding window, and cite the new benchmark by class name (never line numbers) as the drift guard
for the figure, the way the section already cites `keyValueCacheDecodeMatchesFullForward` for
correctness. Keep every other claim in the section intact.

### 4. If the `seqLen` cap is the binding limit for long-form speed, file a follow-up — do not build it here

The fast path is capped at `seqLen` positions by design (fixed `(seqLen, dim)` caches). If the
measurement makes clear that usable long-form generation therefore remains stuck at sliding-window
speed once the text exceeds the trained context, that is a real capability frontier — a **sliding**
(rolling, position-remapped) key/value cache that keeps the last `seqLen` positions, giving
sliding-window *semantics* at cache *cost*. It is out of scope for this measurement cycle. File it as
a tracker task with the benchmark numbers as evidence; do not start it on this branch.

## Approach

1. **Confirm the ground truth first.** Re-read `CausalLanguageModelForwardProfileTest` and the two
   generators; confirm `MetalCommandRunner` exposes the dispatch/commit counters the profile test
   already uses, and that `OperationProfileNode` records per-operation timing for the decode graph.
   Use `ar-profile-analyzer` on the written XML to read the decode kernel counts directly rather than
   inferring them.
2. **Write the benchmark, run it on the Metal node**, iterate on the configuration until both paths
   run cleanly within the cache's `seqLen` and the steady-state measurement is stable across repeats.
3. **Record the numbers**, regenerate the greedy/nucleus samples, and write the documentation rewrite
   from the measured output — not from expectation.
4. **Validate**: build validator (checkstyle, code_policy, test_timeouts, duplicate_code, javadoc),
   and run the new test plus `keyValueCacheDecodeMatchesFullForward` and
   `slidingWindowGenerationMatchesFullForward` narrowly to confirm nothing regressed.

## Success Criteria

- A committed `CausalLanguageModelDecodeThroughputTest` that measures both decode paths on the same
  model and reports bytes/second, per-byte dispatch counts, and the cache speedup, writing a profile
  XML; it asserts only the robust structural property (cache issues strictly fewer dispatches per
  byte) and reports wall-clock figures without asserting a host-dependent threshold.
- The Generation section of `training-a-language-model.md` states the measured key/value-cache decode
  speedup and per-byte dispatch reduction, replacing the current unquantified sentence, and cites the
  new benchmark as the figure's drift guard. Any absolute bytes/second it quotes is attributed to the
  configuration it was measured at, not silently presented as the trained documentation model's.
- A logged greedy-vs-nucleus continuation of a documentation prompt through the cache path,
  demonstrating the two sampling modes compose with the cache.
- No existing test weakened; `keyValueCacheDecodeMatchesFullForward` and
  `slidingWindowGenerationMatchesFullForward` still pass. Build validator clean.
- If the `seqLen` cap is found to bind long-form speed, a tracker task for a sliding key/value cache
  is filed with the measured evidence, and its id is recorded here.

## Dependencies

None outstanding. Every piece this measures is already on `master`:
`KeyValueCacheAutoregressiveModel`, `SlidingWindowAutoregressiveModel`,
`CausalLanguageModel.decodeModel`, the shared `sampleToken` / top-p path, and the
`CausalLanguageModelForwardProfileTest` harness. It needs a Metal node for comparable numbers (see
the workstream settings file).

## Open questions

These do not block the work, but the person approving the plan should decide them, and the
implementer should surface the answers in the benchmark output and the rewrite rather than pick one
silently.

1. **The 35–45-vs-100 bytes/second discrepancy already in the doc.** The Generation section states
   two sliding-window throughput figures for the same documentation-run configuration — "about 35 to
   45 bytes per second" where the sliding window is introduced, and "about 100 bytes per second"
   where `trainOnDocumentation` generates — that differ by more than 2×. They may reflect different
   machines, different measurement points, or drift as the corpus changed. The benchmark measures one
   sliding-window number on one node under controlled conditions; the rewrite should reconcile the
   two existing figures (keep the measured one, explain or drop the other) instead of adding a third.
2. **Which configuration to benchmark, given the Metal-node and CI-time budget.** The portable
   claims (speedup, per-byte dispatch ratio) hold at any configuration, so a small model suffices for
   them and keeps the test fast. A directly comparable *absolute* bytes/second, however, requires the
   documentation-run configuration (context 64, 115,008 parameters), which is heavier and may push
   the test past a comfortable CI budget on the Metal node — note that `trainOnDocumentation` itself
   is a long test excluded from the pipeline profile. Decide whether the absolute figure is worth the
   larger configuration, or whether the rewrite should lead with the speedup and dispatch ratio and
   quote absolute numbers only as the benchmark model's.
3. **Does the structural dispatch-count assertion hold for very short sequences?** The cache wins per
   byte because it decodes one position while the sliding window runs a full `seqLen`-wide pass. At
   very small `seqLen` the gap narrows; the chosen configuration must keep the cache strictly ahead
   on dispatches per byte so the one assertion the test makes is not marginal. The benchmark should
   confirm the margin, not assume it.

## Outcome of the implementation (2026-10-27)

Measured on the Mac Studio Metal node by `CausalLanguageModelDecodeThroughputTest` at the
documentation-run configuration (random weights; 37-byte prompt + 27 generated = 64 positions; three
timed decodes after a warm-up; profile attached), stable across three runs:

| | sliding window | key/value cache |
|---|---|---|
| bytes/second | ~190–198 | ~50 |
| kernel invocations/byte (`OperationProfileNode.getRunCount`, new) | 86 | 109 |
| Metal dispatches/byte | 135 | 181 |
| Metal commits/byte | 7 | 45 |

Both paths decode the same greedy continuation. **The premise of item 1.4 is false on `master`:** the
cache path issues *more* kernels, dispatches and commits per byte and is ~4× slower. The commit-cause
breakdown (`MetalCommandRunner.hostCompleteRequesters`) attributes ~30 host-blocking waits per byte to
`mtlCopy` inside the decode graph. Asserting "strictly fewer dispatches per byte" would be asserting a
falsehood, so the benchmark asserts only path agreement and that each path's kernels were profiled,
and reports everything else. Answers to the open questions: (1) the steady-state sliding-window figure
is ~190 B/s; the doc's ~100 B/s times the first decode after construction, and 35–45 B/s is dropped;
(2) the documentation-run configuration fits easily (the test takes well under a minute warm); (3) the
margin is negative at this configuration.

Follow-ups filed:
- `d8ad5001-7d94-42c3-8a3b-51277dbcdc86` — remove the host-blocking copies so the cache decode is
  actually faster; then add the strictly-fewer-kernels assertion.
- `9825196c-0c15-44c5-90d6-475aa0d503ff` — `decodeModel` hands out caches the attention layer never
  uses (`reset()` clears and `destroy()` frees the wrong buffers).

Item 4 (sliding key/value cache) was not filed: the `seqLen` cap is not the binding limit while the
cache path is slower than the sliding window at every length; it becomes relevant once
`d8ad5001` lands.

## Estimated Complexity

**Medium.** One focused benchmark test and a grounded documentation rewrite. The risk is not
algorithmic — the decode paths exist and are correctness-pinned — but empirical: getting a stable
steady-state measurement, and choosing a configuration that exercises the cache meaningfully within
its `seqLen`. Asserting only the structural dispatch-count property keeps the test from becoming a
flaky timing gate.
