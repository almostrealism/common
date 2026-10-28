---
status: proposed
created: 2026-10-28
closed:
tracker: [9825196c-0c15-44c5-90d6-475aa0d503ff]
---

# Give the attention key/value caches an owner the caller can clear and release

**Category:** Code Quality (correctness)
**Estimated complexity:** Small–Medium
**Runs on:** any backend. The defect is a buffer-ownership mismatch and its drift guard is a
reset/clear assertion, neither of which depends on Metal — no workstream settings file.

## Motivation

The self-hosted language-model track spent two months reaching the point where the platform decodes
one token at a time against a per-layer key/value cache (`KeyValueCacheAutoregressiveModel`, built by
`CausalLanguageModel.decodeModel`). Last cycle's decode-throughput benchmark
(`CausalLanguageModelDecodeThroughputTest`, PR #768) measured that path and, besides the headline
performance finding, surfaced a concrete correctness defect in how the decode model owns its caches.
Filed as tracker task `9825196c`, it is this:

`CausalLanguageModel.decodeModel(position, keyCaches, valueCaches)` allocates a `(seqLen, dim)` key
cache and value cache per layer, clears them, and appends them to the caller's lists — but the
`attention(...)` call it then builds is an overload that takes **no cache arguments**. The caches the
compiled decode model actually reads and writes are the ones
`AttentionFeatures.attentionArguments(..., int seqLen, Producer position, double epsilon)` allocates
internally and binds into the layer's argument map (`key_cache`, `value_cache`). So the caches
`decodeModel` hands back are **orphans** — never bound to anything.

The consequences are real, not cosmetic:

- **`reset()` clears the wrong buffers.** `KeyValueCacheAutoregressiveModel.reset()` clears the
  orphan caches; the caches the model reads are never cleared between sequences. This passes its
  guard test (`keyValueCacheResetClearsCacheAcrossSequences`) today *only* because the causal mask
  hides rows at or beyond the current position and decoding overwrites rows in order — so a stale row
  from a previous sequence is never read. The invariant the method's javadoc promises ("a new
  sequence cannot read this one's cached keys and values") is not actually enforced; it holds by
  accident of two unrelated mechanisms.
- **`destroy()` leaks the real caches.** It releases the orphans; the bound caches are never freed.
  `2 · depth · seqLen · dim` floats leak per generator.
- **The documentation describes a mechanism that does not exist.** The javadoc of
  `KeyValueCacheAutoregressiveModel` / `decodeModel` and the Generation section of
  `training-a-language-model.md` say `reset()` clears "the caches the decode uses." It does not.

The root cause is **general**, and that is what makes this worth a plan rather than a one-line patch:
`attentionArguments` allocates the caches with **no owner**. Every caller of the cached `attention(...)`
family — the self-hosted LM, Qwen3, the SkyTnt MIDI model — gets a compiled block whose caches are
sealed inside it, with no handle to clear them at a sequence boundary or free them at end of life.
`decodeModel` only *looks* like it has a handle because it allocates a parallel set and discards them.
Fixing this properly means giving the caller a way to own the caches the block really uses, which is
the honest form of the "own your collaborators" and "a method returns the thing it builds" principles
the project holds every class to.

For the self-understanding aim, this is foundation hygiene directly under the LM generation path: the
same path a trained model will drive to write about the platform. A decode wrapper whose `reset()` is
a no-op-by-accident and whose `destroy()` leaks is exactly the kind of quiet incorrectness that
should not sit beneath the flagship capability.

## Scope

Concrete and bounded. No new concept, no new class — one new overload on an existing type, its use
from one call site, the deletion of the dead allocation, the doc/javadoc correction, and a drift
guard that fails on the real bug.

### 1. Let the caller own the caches: a caller-supplied-cache path through `attentionArguments`

The single allocation site is `attentionArguments(int heads, int kvHeads, PackedCollection
rmsAttWeight, PackedCollection wk, PackedCollection wv, PackedCollection wq, PackedCollection wo, int
seqLen, Producer<PackedCollection> position, double epsilon)` in `AttentionFeatures` — every other
overload of the *cached* attention family funnels through it: the QK-Norm and MRA `attentionArguments`
variants delegate to it, and the `attention(...)` block builders call it. (The cross-attention family —
`sequenceAttention`/`sequenceCrossAttention` in `SequenceAttentionFeatures`, which `DiffusionTransformer`
and the resampling callers use — is a separate, cache-free path that does not go through
`attentionArguments`; it has no `key_cache`/`value_cache` and this change does not touch it.) Introduce a sibling overload that
accepts a caller-owned `PackedCollection keyCache` and `PackedCollection valueCache` and binds those
into the argument map instead of allocating, and make the existing self-allocating overload delegate
to it (allocate, clear, delegate) so there remains exactly one place that builds the map and exactly
one place that allocates. Validate the supplied caches' shape against `(seqLen, dim)` with a clear
message (metadata-first signature, per the project's method-signature convention) rather than letting
a mis-shaped cache fail deep in compilation.

The decision to record in the plan, for the approver: whether the caller-supplied caches should
thread all the way up through a new **`attention(...)`** block overload (so a model builds the block
and holds the caches in one call), or whether `decodeModel` should call `attentionArguments` +
`attentionLayer` directly (the lower-level path the convenience `attention(...)` overloads wrap).
The former is the better public API and the one Qwen3/SkyTnt would eventually want; the latter is a
smaller diff. Lead with the `attention(...)` overload unless it forces a cache argument onto callers
that do not want to own caches — which it must not (the existing no-cache overloads stay, unchanged,
for every caller that is content to let the block allocate).

This is additive and backward compatible: every current caller keeps the self-allocating behaviour
untouched; only a caller that *wants* to own its caches passes them in.

### 2. Use it from `decodeModel`, and delete the orphan allocation

`CausalLanguageModel.decodeModel` keeps allocating and clearing one `(seqLen, dim)` key cache and
value cache per layer and appending them to `keyCaches`/`valueCaches` — but now **passes them to the
attention call** through the new path, so the lists hold the caches the compiled model truly reads
and writes. The current dead `attention(..., null, null, null, null, null, freqCis, p(position))`
call is replaced by the cache-owning form. Net: the `keyCaches`/`valueCaches` contract in
`decodeModel`'s javadoc becomes true as written, with no change to its signature.

### 3. Correct the javadoc and the one documentation sentence

- `KeyValueCacheAutoregressiveModel` and `decodeModel` javadoc: keep the "reset clears the caches /
  destroy releases them" statements — they become *true* once the caches are the bound ones — but
  remove any wording that implies the generator allocates a parallel set, and make the "the attention
  layer allocates them" sentence reflect that the caller now supplies them.
- `docs/internals/training-a-language-model.md` Generation section: the sentence stating that
  `reset()` clears the decode caches is correct in intent and stays, but verify it now matches the
  code; adjust only if the cache-ownership wording drifted. (No figures change — this plan does not
  touch throughput.)

### 4. A drift guard that fails on the real bug

Add a test (in `CausalLanguageModelTest`, a focused sibling of the existing cache tests) that would
**fail on `master`** — i.e. that exercises the caches the compiled model actually reads, not the
orphans. The test must be able to read cache *contents*, and the only existing code path that holds
the cache objects the compiled decode model binds is the directly-built-`decodeModel` pattern of
`keyValueCacheLaterPositionsReadEarlierEntries`: it builds `lm.decodeModel(position, keyCaches,
valueCaches)`, holds those two lists, and compiles. `KeyValueCacheAutoregressiveModel` keeps its own
`keyCaches`/`valueCaches` private with no accessor, so a *generator-driven* variant of this test
cannot see the buffers without a new accessor — see the open decision below. Model the drift guard on
the `decodeModel`-direct pattern, holding the lists itself:

- Decode a sequence of several positions, then read back the rows written so far in the held
  `keyCaches`/`valueCaches` and assert they are **non-zero** (the lists `decodeModel` populated are
  the caches the compiled model reads and writes). On `master` those lists are the orphans, which
  stay zero, so this fails today. This single assertion is the one that discriminates master from the
  fix, and it is the structural core of the guard.
- Then clear those same held buffers (the operation `reset()` performs) and show that clearing them
  erases the past the model attends to. This half needs care, because the accident that masks the
  bug masks a naive version of it too: a decode that restarts at position 0 overwrites row 0 before
  reading it, and the causal mask hides every later row, so restarting from 0 reads no stale data on
  `master` either. The assertion must therefore decode at a **later** position `k > 0` straight after
  the clear, *without* re-decoding positions `0..k-1`, and compare that output row against the same
  token decoded at position `k` by a freshly compiled decode model over the same weights (whose caches
  are all zero). With the fix the cleared rows `0..k-1` are zero in both, so the rows match; on
  `master` the bound caches still hold the earlier sequence's rows `0..k-1`, the decode attends to
  them, and the rows differ — so this fails today as well.

Note that the existing `decodeRow` helper in `CausalLanguageModelTest` (used by
`keyValueCacheLaterPositionsReadEarlierEntries`) also clears the held `keyCaches`/`valueCaches`
before each run and always restarts at position 0, so it currently clears orphans too and passes
only by the same accident. It needs no change — it stays correct once the lists are the bound caches —
but the new test must not reuse its restart-from-0 shape for the clearing assertion.

The test asserts the ownership invariant structurally (which buffer the decode wrote, and that
clearing *that* buffer erases the past), so it cannot be satisfied by the accidental causal-mask
behaviour that makes the current generator guard (`keyValueCacheResetClearsCacheAcrossSequences`,
which only compares decoded bytes) pass. It needs no Metal and no trained weights — random weights
decode fine for a buffer-contents check. Do not weaken or duplicate
`keyValueCacheDecodeMatchesFullForward`; it still pins correctness and must still pass.

**Open decision for the approver (test surface vs. a new accessor):** the guard above proves ownership
at the `decodeModel` level, which is where the bug lives, and needs no production change beyond the
fix itself. Driving the assertion through the *generator*'s `reset()` instead — the public surface a
caller actually uses — would additionally pin that `KeyValueCacheAutoregressiveModel.reset()` clears
the bound caches, but requires giving the generator a (package-private or test-visible) accessor to
its `keyCaches`/`valueCaches`, which it does not have today. That accessor is a small, defensible
production addition, but it is a design choice to make deliberately rather than smuggle in; decide
whether the `decodeModel`-level guard is sufficient or the generator-level one is wanted.

## Approach

1. **Confirm ground truth first.** Re-read the `attentionArguments` overload chain in
   `AttentionFeatures`, `CausalLanguageModel.decodeModel`, and `KeyValueCacheAutoregressiveModel`'s
   `reset()`/`destroy()`/`keyCaches` fields. Confirm, by reading `pdsl/attention.pdsl`'s argument
   names, that `key_cache`/`value_cache` are exactly the bound layer arguments the block reads and
   writes, so the caller-supplied collections land on the same bindings. Confirm the self-allocating
   overload is the sole allocation site every other overload funnels through.
2. **Add the caller-supplied-cache overload** (plus the `attention(...)` block overload if that route
   is chosen), make the self-allocating overload delegate to it, and add the shape validation.
3. **Rewire `decodeModel`** to pass its per-layer caches through the new path; delete the dead
   allocation-then-discard.
4. **Write the drift-guard test**, confirm it fails on `master` (run it against the unmodified code
   first to prove it catches the bug), then make it pass with the fix.
5. **Correct the javadoc and the doc sentence** from the now-true behaviour.
6. **Validate:** build validator (checkstyle, code_policy, test_timeouts, duplicate_code, javadoc),
   then run narrowly `keyValueCacheDecodeMatchesFullForward`, `keyValueCacheResetClearsCacheAcrossSequences`,
   the new test, and `slidingWindowGenerationMatchesFullForward`; plus `AttentionAssetTest` and
   `SkyTntPdslTest`/`PdslLoaderTest` to confirm the self-allocating callers are untouched.

## Success Criteria

- A caller-supplied-cache `attentionArguments` overload (and, if chosen, an `attention(...)` block
  overload) exists on `AttentionFeatures`; the existing self-allocating overload delegates to it, so
  there is exactly one allocation site and one map-building site. No existing caller's behaviour
  changes.
- `CausalLanguageModel.decodeModel` binds the caches it appends to `keyCaches`/`valueCaches` into the
  attention layers, with no orphan allocation; `KeyValueCacheAutoregressiveModel.reset()` clears, and
  `destroy()` releases, the caches the compiled model actually reads and writes.
- A new test fails on `master` and passes with the fix, asserting that the `keyCaches`/`valueCaches`
  lists `decodeModel` populates are the buffers the decode writes (non-zero after decoding), and that
  clearing those buffers erases the attended past (a decode at position `k > 0` straight after the
  clear matches a fresh decode model at `k`) — independent of the causal-mask accident.
- `keyValueCacheDecodeMatchesFullForward`, `keyValueCacheResetClearsCacheAcrossSequences`,
  `slidingWindowGenerationMatchesFullForward` and the asset tests still pass; no test weakened. Build
  validator clean. Javadoc and the training-doc sentence match the code.

## Dependencies

None. Every piece is on `master`: `AttentionFeatures.attentionArguments`/`attentionLayer`,
`CausalLanguageModel.decodeModel`, `KeyValueCacheAutoregressiveModel`, and the
`keyValueCacheResetClearsCacheAcrossSequences` guard this strengthens. Independent of the sibling
performance task `d8ad5001` (host-blocking copies in the decode graph); this one corrects ownership,
that one makes the path fast. Doing this first gives that task a decode graph whose cache buffers have
a clear owner.

## Out of scope

- The decode-throughput regression (`d8ad5001`): the ~4× slowdown from ~30 host-blocking `mtlCopy`
  waits per byte is a Metal performance investigation, the next proof-of-value-adjacent cycle, not
  this correctness fix.
- Threading caller-owned caches through Qwen3 and SkyTnt's own generators. This plan makes the
  capability exist on `AttentionFeatures` and proves it on the self-hosted LM decode; adopting it in
  the other models is a follow-up if and when they need sequence-boundary cache control.

## Estimated Complexity

**Small–Medium.** The change is localized — one new overload, one rewired call site, one deleted
allocation, a doc/javadoc correction, and one test — but it touches a shared, heavily delegated
method family (`attentionArguments`) that many callers funnel through, so the care is in keeping the
self-allocating path byte-for-byte unchanged while adding the owned-cache path, and in writing a
guard that provably catches the real defect rather than the accident that currently masks it.
