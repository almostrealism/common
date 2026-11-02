---
status: proposed
created: 2026-11-02
closed:
tracker: []
---

# Decode the self-hosted LM past its trained context with a rolling key/value cache

**Category:** Proof of Value (self-hosted LM generation)
**Estimated complexity:** Medium–Large
**Runs on:** correctness on any backend; the throughput comparison on a macOS / Metal node
(see `plan-20261102-174749-workstream.yaml`), because the ~170–220 bytes/second figures the
claim rests on were measured on the Metal reference and the decode-throughput harness this plan
reuses reports Metal dispatch and command-buffer commit counts.

## Motivation

For two months the self-hosted language-model track has built toward one thing: the platform
writing readable prose about its own architecture, at a speed that makes it usable. Every piece is
now on `master` — it trains from scratch and beats its unigram baseline, it decodes greedily and
with nucleus sampling through one shared path, and it decodes **one token at a time against a
per-layer key/value cache** (`KeyValueCacheAutoregressiveModel`, built by
`CausalLanguageModel.decodeModel`), verified byte-for-byte identical to a full forward pass.
The 2026-10-29 cycle (PR #783) closed the last gap in that story: the cache decode is now the
*faster* path, not the slower one.

But the cache has a hard ceiling, and it is exactly the ceiling that matters for the vision. The
Generation section of `docs/internals/training-a-language-model.md` states it plainly: because each
layer's cache is a fixed `(seqLen, dim)` buffer that does not slide, the cache decode runs only for
sequences up to `seqLen` positions, and **"longer generation still uses the sliding window."**
`KeyValueCacheAutoregressiveModel.load(Integer)` enforces this — it throws `IllegalStateException`
at a step at or beyond `seqLen` rather than index past the cache. The self-hosted model's trained
context is 64 bytes. So the moment the model is asked to write *more* than 64 bytes about the
platform — which is the whole point of "prose about itself" — it falls back to the sliding-window
decode, which re-runs a full `(seqLen, vocabSize)` forward pass per byte.

This is the capability frontier the Manager Log has named as the next proof-of-value step for three
cycles, each time conditioned on the cache first being faster than the sliding window. That
condition is now met (the 2026-10-29 cycle confirmed it and the documentation records the measured
speedup). The frontier is a **rolling (sliding) key/value cache**: one that keeps the last `seqLen`
positions — evicting the oldest as each new token arrives — so that generation of *arbitrary*
length runs at cache cost, with sliding-window *semantics* at cache *speed*. Cashing it is the
difference between "the software can write 64 bytes about itself quickly" and "the software can
write as much about itself as we ask, at the fast rate." For the self-understanding aim that is the
step that makes self-description actually usable.

## The key observation that makes this tractable

A rolling cache sounds like it should require re-rotating every cached key each step (the
sliding-window forward pass *does* re-place its tokens at positions `0..seqLen-1` every step, so
each token's rotary angle changes as the window slides). It does not, and the reason is a property
of RoPE worth stating up front because it shapes the whole design:

> **Rotary attention scores depend only on the *relative* position of query and key.** For a query
> rotated at position `m` and a key rotated at position `n`, the score contribution is
> `R(m)ᵀ R(n) = R(n − m)` — a function of `n − m` alone. The absolute magnitudes cancel in the dot
> product.

Consequently, a key that is rotated **once**, at the absolute position it held when it entered the
cache, and **never re-rotated**, yields exactly the same attention score against the current query
as the sliding window's freshly re-rotated key — because the relative distance between the newest
query and each retained key is identical under both schemes (the newest token is `seqLen − 1` steps
ahead of the oldest retained token either way). Values are not rotated at all, and the
feed-forward, norms and projections are position-independent. So **a ring-buffer cache storing each
key rotated once at its absolute position reproduces sliding-window attention for the newest token
exactly** — and the newest token's row is the only one the generator reads to sample the next byte.

This reduces the work to two mechanical changes, not a redesign of the attention kernel:

1. **Ring-buffer cache indexing.** `cache_write(key_cache, position)` and
   `cache_write(value_cache, position)` in `attention.pdsl` currently write row `position`. They
   must write row `position mod seqLen`, so the `seqLen`-row buffer holds the most recent `seqLen`
   keys/values. The read side (`attention_scores(key_cache)` / `weighted_values(value_cache)` in
   `attend`) already sums over *all* cache rows, and softmax over keys is permutation-invariant, so
   once the ring is full the ring ordering does not affect the result as long as a key and its value
   share a slot (they do — both are written at the same `position`). `causal_mask(position)` masks
   columns after `position`; once `position ≥ seqLen` it masks nothing, which is exactly right for a
   full ring, so the mask needs no change for the steady state — only the transient
   (`position < seqLen`, before the ring fills) must stay correct, and there it already is.

2. **Rotary rotation beyond the frequency table.** `RotationFeatures.ropeRotation` reads the
   `freqCis` table at `row = position × freqSize`; the table built by
   `computeRopeFreqs(seqLen, invFreq)` has only `seqLen` rows, so a position `≥ seqLen` reads out of
   bounds. The fix is to supply the rotary angle for an *arbitrary* position computed directly from
   the stored inverse frequencies and the position scalar (`angle = position × invFreq`, then
   `cos`/`sin`), rather than from a fixed `(seqLen, …)` table. This is the one genuinely new piece of
   math; it generalizes the existing single-row rotary computation to an unbounded position and is a
   natural method on `RotationFeatures` alongside `computeRopeFreqs`.

Everything else — the generator's lifecycle, the `sampleToken` path, the decode block assembly —
is reused unchanged.

## Scope

Concrete and bounded. One new generator, two small shared-primitive changes, one correctness test
that pins the capability against the existing oracle, and one throughput measurement with a
documentation update grounded in it. No new Maven module; no new pom dependency.

### 1. Rotary rotation at an arbitrary position (`RotationFeatures`, `engine/ml`)

Add the rotation-for-one-arbitrary-position capability described above as a method on
`RotationFeatures` (the class that already owns `computeRopeFreqs` and `ropeRotation`), reusing
`computeInvFreq` and the existing split-half rotation expression so there is no duplicated rotary
math. It must produce, for any position `p ≥ 0`, exactly the rotation that
`computeRopeFreqs(…, invFreq)` produces at row `p` for `p < seqLen` — pinned by a direct numeric
test against the existing table rows (the same stance `decodeRopeFrequenciesMatchStoredInverseFrequencies`
already takes for the stored-inverse-frequency bridge). This is the enabling change and must land
with its own unit coverage independent of the end-to-end generation test.

### 2. Ring-buffer cache writes (`attention.pdsl` and the `cache_write` primitive, `engine/ml`)

Make the cache write address row `position mod seqLen`. Prefer expressing this where the cache's
own size is known (the write primitive knows the cache shape), so the ring modulus is the cache's
row count rather than a separately passed constant — the caller declares intent by the buffer it
supplies, not by a magic number. This change must be **opt-in or inert for the bounded decode**:
every existing caller (`Llama2`, Qwen, Moonbeam, and the bounded `KeyValueCacheAutoregressiveModel`)
decodes within `seqLen`, where `position mod seqLen == position`, so a correct modulus is a no-op
for them and cannot regress their behaviour. Verify that equivalence explicitly rather than assume
it. If the write primitive cannot take the modulus without disturbing the bounded path, introduce a
sibling rolling `cache_write` used only by the rolling decode block, keeping the bounded layers
byte-identical.

### 3. The rolling decode model and generator

A rolling decode variant that composes the `attention.pdsl` layers with the arbitrary-position
rotary and the ring-buffer write, and a generator that drives it:

- A rolling decode `Model` (the natural home is a `CausalLanguageModel.decodeModel` variant or an
  argument to it that selects rolling vs bounded rotary/write, reusing the existing fused-QKV split,
  the feed-forward and the head — no weight is copied, exactly as the bounded decode does today).
- `SlidingKeyValueCacheAutoregressiveModel` (an `AutoregressiveModel<Integer>`, sibling of
  `KeyValueCacheAutoregressiveModel`), which owns the rolling decode compilation, its input buffer,
  the per-layer `(seqLen, dim)` caches, the (unbounded) position and temperature, and shares the
  `sampleToken` path. Its `load(Integer)` does **not** throw at `seqLen` — that bound is precisely
  what this generator removes — and `reset()` clears the caches and returns the position to zero.
  It must be a real subclass that overrides the `load`/`forward`/`sample`/`reset`/`destroy` steps,
  following the owner-approved pattern the bounded generator established (compiles the model itself,
  reads only public accessors, owns and releases its own resources) — not a lambda-wired wrapper
  and not a caller-supplied `CompiledModel`.

### 4. Correctness pinned against the sliding window, past `seqLen`

The oracle already exists and is itself verified against a full forward pass:
`CausalLanguageModelTest.slidingWindowGenerationMatchesFullForward`. Add a test
(`slidingKeyValueCacheMatchesSlidingWindowPastSeqLen`, or the equivalent in the decode test class)
that generates **more than `seqLen` tokens** — enough that the ring wraps at least once — from a
fixed prompt and random weights, with both `SlidingWindowAutoregressiveModel` and the new rolling
generator, and asserts the greedy continuations are **byte-for-byte identical**, including after the
wrap. Because greedy decoding selects an argmax, this is robust to the small floating-point
differences between rotating at a large absolute angle and at a small window-relative one, and does
not require a loosened tolerance. Keep the existing bounded-cache tests
(`keyValueCacheDecodeMatchesFullForward`, `keyValueCacheRefusesDecodingPastSeqLen`,
`keyValueCacheResetClearsCacheAcrossSequences`, `keyValueCacheLaterPositionsReadEarlierEntries`)
passing untouched — the bounded generator is unchanged and still refuses past `seqLen`. Add a reset
and a lifecycle (`destroy` releases the caches) test for the rolling generator mirroring the bounded
generator's.

### 5. Throughput measurement and documentation update

Extend `CausalLanguageModelDecodeThroughputTest` (or add a sibling method) to measure the rolling
decode against the sliding window **over a run that exceeds `seqLen`**, where the sliding window pays
its full-forward-per-byte cost and the rolling cache pays one position per byte. Report bytes/second,
per-byte kernel/dispatch/commit counts, and the speedup, writing the profile XML, and assert only
the robust structural property (the rolling path issues strictly fewer kernels per byte than the
sliding window over the long run) — wall-clock figures reported, not asserted, exactly as the
existing throughput test does, so it stays a stable CI citizen and cannot be gamed by a tolerance.
Then rewrite the one sentence in the Generation section of `training-a-language-model.md` that
currently says "longer generation still uses the sliding window" to state that long-form generation
now runs at cache cost through the rolling cache, with the measured long-run speedup, and cite the
new generator and the benchmark by class name (never line numbers) as the drift guards. State the
relative-position property as the reason the rolling cache matches the sliding window without
re-rotating. Keep every other claim in the section intact, and keep the bounded cache's role (it is
the simplest decode within `seqLen`) accurately described.

## Approach

1. **Confirm the ground truth first.** Re-read `attention.pdsl` (the `attend`/`cache_write`/
   `rotate_heads` layers), `RotationFeatures.ropeRotation`/`computeRopeFreqs`,
   `CausalLanguageModel.decodeModel` and `KeyValueCacheAutoregressiveModel`. Confirm by reading the
   `cache_write`, `attention_scores`, `weighted_values` and `causal_mask` primitive implementations
   that (a) the read side sums over all cache rows, (b) softmax is over the key axis, and (c)
   `causal_mask(position)` with `position ≥ seqLen` masks nothing — the three facts the
   relative-position argument relies on. If any is not as stated, stop and record the discrepancy;
   the design above assumes all three.
2. **Land the arbitrary-position rotary first, with its own numeric test** against the existing
   table rows. Nothing else can be correct until this is.
3. **Land the ring-buffer write**, proving the `position mod seqLen == position` equivalence for the
   bounded path so no existing model regresses.
4. **Assemble the rolling decode model and generator**, then make
   `slidingKeyValueCacheMatchesSlidingWindowPastSeqLen` pass on any backend — this is the gate.
5. **Measure on the Metal node**: run the long-run throughput comparison, use `ar-profile-analyzer`
   on the written XML to read the per-byte kernel counts directly, and write the documentation
   rewrite from the measured output, not from expectation.
6. **Validate**: build validator (checkstyle, code_policy, test_timeouts, duplicate_code, javadoc);
   run the new correctness test plus `slidingWindowGenerationMatchesFullForward`,
   `keyValueCacheDecodeMatchesFullForward` and `keyValueCacheRefusesDecodingPastSeqLen` narrowly to
   confirm nothing regressed.

## Success Criteria

- `slidingKeyValueCacheMatchesSlidingWindowPastSeqLen` generates more than `seqLen` tokens with both
  the rolling generator and the sliding window and asserts byte-for-byte agreement, including after
  the ring wraps, on any backend — no loosened tolerance, no shrunk dimensions.
- The arbitrary-position rotary reproduces the existing `computeRopeFreqs` table rows numerically
  for `position < seqLen`, pinned by its own unit test.
- Every existing bounded-cache test and every other attention model's tests still pass; the bounded
  `KeyValueCacheAutoregressiveModel` still refuses a step at or beyond `seqLen`, and the
  ring-buffer write is proven inert for the bounded path.
- A long-run throughput measurement shows the rolling cache decoding past `seqLen` at a materially
  higher bytes/second than the sliding window, with a committed benchmark that asserts only the
  robust per-byte dispatch-count property and reports the rest.
- The Generation section of `training-a-language-model.md` states that long-form generation now runs
  at cache cost through the rolling cache, with the measured speedup, citing the new generator and
  benchmark as drift guards and explaining the relative-position property; no other claim altered.
- Build validator clean. No new Maven module, no pom edit.

## Dependencies

None outstanding. Every piece this builds on is on `master`: `KeyValueCacheAutoregressiveModel`,
`SlidingWindowAutoregressiveModel`, `CausalLanguageModel.decodeModel`, the `attention.pdsl` cached
layers, `RotationFeatures.computeRopeFreqs`/`ropeRotation`, the shared `sampleToken` / top-p path,
and the `CausalLanguageModelDecodeThroughputTest` harness. The throughput comparison needs a Metal
node for figures comparable to the ones already in the documentation (see the workstream settings
file).

## Risks and open questions

These do not block starting the work, but the implementer should surface the answers in the test
and the rewrite rather than decide them silently.

1. **Does `causal_mask(position)` behave correctly for `position ≥ seqLen`?** The relative-position
   argument needs the full ring to be unmasked. Confirm from the `causal_mask` implementation that a
   position at or beyond the cache size masks nothing (so all `seqLen` rows attend); if it instead
   clamps or wraps the position, the rolling path needs a mask that treats a full ring as fully
   visible. Confirm, do not assume.
2. **Numerical drift at large absolute angles.** Rotating at absolute position (e.g. 300) uses a
   larger angle than the sliding window's window-relative `0..seqLen-1`. Mathematically the dot
   product is identical; numerically the `cos`/`sin` of a large angle carries slightly more error.
   Greedy argmax is expected to be robust to this (that is why the test asserts byte match, not
   logit equality), but if a byte mismatch appears it is evidence the angle reduction needs care
   (reduce the angle mod 2π before `cos`/`sin`), not evidence to loosen the test.
3. **Scope boundary of the `cache_write` change.** The cleanest design makes the ring modulus the
   cache's own row count so every caller is correct automatically; if that cannot be done without
   touching the bounded path's generated kernel, a sibling rolling write keeps the bounded layers
   byte-identical. Decide which, and prove the bounded path unchanged either way.
4. **Attention-sink degradation is out of scope.** Published rolling-cache work (StreamingLLM) notes
   that evicting the very first tokens can degrade quality because early tokens act as attention
   sinks. This plan's correctness bar is *matching the sliding window*, which evicts them too, so
   matching it is success; keeping the first `k` tokens pinned as sinks is a separate quality
   improvement and, if it proves worthwhile, a follow-up tracker task — not part of this cycle.

## Estimated Complexity

**Medium–Large.** The generator and the test mirror an existing, approved pattern and are
straightforward. The real work is the two shared-primitive changes — the arbitrary-position rotary
and the ring-buffer write — which touch primitives used by every attention model and so must be
proven inert for the bounded path, and the empirical step of a stable long-run throughput
measurement on Metal. The design risk is low because the relative-position property makes the
correctness oracle an existing, already-verified test; the cost is in doing the primitive changes
carefully and confirming the three attention-kernel facts the argument rests on.
