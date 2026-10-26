# Decode the self-hosted LM one token at a time with a key/value cache

**Category:** Proof of Value (self-hosted LM generation)
**Branch:** `project/plan-20261026-022005`
**Date:** 2026-10-26
**Estimated complexity:** Medium–Large

---

## Motivation

The self-hosted language model is the platform's first concrete step toward software that
understands its own architecture: a byte-level transformer trained on the project's own
documentation that, since PR #685, writes readable text about itself, and since PR #741 can do so
with nucleus (top-p) sampling. The sampling cycle closed the "what can the model produce" question
for short continuations. The next item on the proof-of-value track — named in the "what comes
next" list of the last five Manager Log entries, every time directly after top-p — is the
**single-position key/value-cache decode**.

Here is why it matters now. `SlidingWindowAutoregressiveModel` decodes by re-running **a full
`(seqLen, vocabSize)` forward pass of the whole window for every byte it emits**. The training doc
measures the cost plainly: *"The cost is one full forward pass per byte, about 35 to 45 bytes per
second for this model."* Every generated token recomputes attention over every earlier token from
scratch, so the work per step grows with the window and the model spends almost all of generation
re-deriving keys and values it already computed. That is the standard reason a from-scratch
transformer is unusable for generation at length, and the standard fix is a KV cache: keep each
layer's past keys and values, and at each step run attention for **one** new position against the
cached past. Decode then costs one position of work per byte instead of one window.

Without this, the model cannot "speak at length" about itself — the visible proof the
self-understanding goal is built toward — in any reasonable time, and the sampling-quality
comparisons the last cycle set up (greedy vs. top-p over long samples) are gated behind a decode
that is an order of magnitude too slow to run at the lengths that would show a difference.

### The precise blocker

The capability is **not** missing from the platform in general. `AttentionFeatures` already ships
single-position KV-cache `attention(...)` overloads (the family with a `position` argument and a
`causalMask(shape, position)` variant), and the SkyTnt MIDI model decodes through them with an
independent per-layer cache — `SkyTntMidiTest.loaderBindsIndependentCachePerLayer` and
`laterPositionsReadEarlierCachedEntries` already pin that those overloads cache per layer and that
later positions read earlier cached entries. What is missing is a decode path **for the self-hosted
LM's weight layout**. The training doc states the gap exactly:

> *"A single-position decode with a key/value cache would avoid it, but the trained weights do not
> fit the single-position `AttentionFeatures.attention` overloads as stored: the model keeps one
> fused QKV weight per block and the rotary inverse frequencies, while those overloads take
> separate query, key and value weights and their own rotary inputs."*

Concretely (`CausalLanguageModelConfig.getWeightShapes()`): each block stores a **fused** `qkv`
weight of shape `(3·dim, dim)` and an output `wo` of `(dim, dim)`, and the model stores the rotary
**inverse frequencies** once under `rope_inv_freq` of shape `(dim/heads/2)`. `CausalLanguageModel`
builds its attention through `transformerBlock(...)` over the full `(1, seqLen, dim)` sequence with
a whole-window causal mask — the training path. The cached single-position `attention(...)`
overloads instead expect **separate** `wq`/`wk`/`wv`/`wo` and a precomputed rotary `freqCis` input
indexed by `position`. Bridging those two shapes — fused→split projection and
`rope_inv_freq`→position-indexed `freqCis` — is the whole of the missing capability.

### Connection to the larger vision

A model that can generate a page about its own architecture in seconds, not minutes, is the
difference between a demonstration and a usable artifact. It is also the prerequisite for every
later generation experiment (long-context sampling studies, prompting the model with its own source
rather than its docs, eventually letting it propose edits). This task turns "the platform can train
and run a model on itself" into "the platform can run that model at a speed that makes using it
practical."

---

## Scope

Concrete, in `engine/ml` (plus, if the cleanest home is there, the general attention/block
methods in `AttentionFeatures` / `TransformerBlockFeatures`). The committed deliverable is the
**decode capability plus a correctness test that pins it to the existing full-forward reference**;
throughput measurement is an explicit follow-up (see Machine).

1. **Bridge the fused-QKV + `rope_inv_freq` layout to the single-position cached attention.** Two
   candidate designs, to be chosen by evidence in step 1 of the Approach, both honoring the
   "generalize existing attention methods, do not duplicate" rule of `engine/ml/CLAUDE.md`:
   - **(a) Split at the boundary.** Expose the fused `qkv` `(3·dim, dim)` weight as three
     `(dim, dim)` views (`wq`/`wk`/`wv`) and derive the position-indexed `freqCis` the cached
     overload wants from the stored `rope_inv_freq`, then build the decode block from the existing
     cached `attention(...)` family unchanged. Preferred if the split is a cheap view/reshape and
     the rotary derivation matches what the full-sequence path already computes internally.
   - **(b) A fused-QKV cached overload.** If (a) forces a materializing copy of the weight or a
     second rotary convention, add a single-position cached `attention(...)`/`transformerBlock(...)`
     overload that accepts the fused `qkv` weight and `rope_inv_freq` directly and splits/derives
     internally — the same generalization pattern the existing overload family already uses for
     optional biases and QK-norm. This keeps one weight layout end to end and avoids a per-step copy.
   Whichever is chosen, the rotary frequencies the decode path uses at `position = p` **must equal**
   those the full-sequence training path applies at row `p`, or greedy output will not match the
   reference (step 4). Establish that equivalence from the code and a numeric check before building
   on it — do not assume it.

2. **Build a single-position decode model from the same `StateDictionary`.** A way to obtain, from a
   trained `CausalLanguageModel` (or its config + weights), an inference model whose input is one
   token's embedding (or one token id) at a given `position` and whose output is one
   `(vocabSize)` row of log-probabilities, reading and writing a per-layer KV cache. It must decode
   **the same weights** — built over the model's own `StateDictionary`, following the existing
   second-compilation pattern (`new CausalLanguageModel(lm.getConfig(), lm.getWeights())`), so no
   weights are copied and lifecycle stays the caller's. Per the "own your collaborators" and
   "a class IS what its name says" rules, this builder belongs on the model (the model compiles its
   own decode variant) — not on a helper that takes a `CompiledModel` from outside.

3. **Wire it into a generator.** The decode must drive an `AutoregressiveModel` so it shares the
   sampling path unified in PR #741 (greedy, temperature, and top-p via `getTopP`/`setTopP`). Two
   routes, decided in Approach: extend the existing `AutoregressiveModel.of(...)` factory (which is
   already the "one token's embedding → one vocabulary row" shape, advancing a `position` on the
   device and owning its input buffer, temperature and sampling ops — the KV cache would live in
   the compiled decode model it wraps), or a dedicated `AutoregressiveModel` subclass mirroring
   `SlidingWindowAutoregressiveModel` (which overrides `load`/`forward`/`sample` and owns its
   buffers). The generator advances `position`, writes the new token into the cache, and samples the
   returned row — **no Java loop over tokens outside the generator**, consistent with the
   `AutoregressiveModel`/`DiffusionSampler` loop-ownership rule. `reset()` must clear the cache and
   position so a new sequence starts clean, exactly as the sliding-window generator's `reset()`
   clears its window.

4. **Correctness test — byte-identical to the full-forward reference.** The committed proof, modeled
   directly on `CausalLanguageModelTest.slidingWindowGenerationMatchesFullForward`: for a small
   fixed-seed model and a fixed prompt, assert that **every** greedily decoded byte from the
   KV-cache path equals the most-probable byte of the corresponding row of an independent full
   `(seqLen, vocabSize)` forward pass — including positions after the first, where the cache is the
   only thing carrying the past. Add determinism (same prompt → identical output after `reset()`)
   and a cache-isolation/causality check in the spirit of the SkyTnt tests (perturbing an earlier
   position changes a later output; a cleared cache does not leak across sequences). These are
   **new** tests; no existing acceptance assertion is touched.

5. **Documentation.** Rewrite the "Generation" section's cost paragraph of
   `docs/internals/training-a-language-model.md` (which currently states the full-forward-per-byte
   cost and names this exact blocker) to describe the KV-cache decode, and update the
   "Autoregressive Generation Loop" section of `docs/internals/ml-inference-pipeline.md` if the
   generator surface changes. Complete Javadoc on every new method/overload. Follow
   `docs/CLAUDE.md`: reference code by stable identifier, never by line number.

**Explicitly out of scope** (follow-ups, not this branch): a macOS/Metal run that *measures* the
throughput improvement and long-sample sampling-quality comparison; batch sizes above 1; changing
`CausalLanguageModelTest`'s existing acceptance assertions; conditional-diffusion decode. The
training path (`transformerBlock` over the full window) is unchanged — this adds a decode variant,
it does not replace the training model.

---

## Approach

1. **Characterize before building.** Confirm from source (and a small numeric check) three facts the
   design rests on, following the no-speculation rule: (i) exactly how the full-sequence
   `transformerBlock(...)` path turns `rope_inv_freq` into the per-position rotary it applies, so the
   decode path can reproduce the *same* frequencies at `position = p`; (ii) whether the fused `qkv`
   `(3·dim, dim)` weight can be exposed as `wq`/`wk`/`wv` views without a materializing copy; (iii)
   what the existing cached `attention(...)` overload and its `causalMask(shape, position)` expect as
   inputs. This decides design (a) vs. (b) in Scope step 1 on evidence, not guess. Use
   `consult`/`ar-docs` and read `AttentionFeatures` and the SkyTnt cached-attention call site as the
   worked template.
2. **Stand up the decode model and pin it to the reference first.** Before wiring a generator, build
   the single-position model from a tiny fixed-seed `StateDictionary` and assert one decoded row
   against one row of the full forward at the same position. Get byte-identical agreement at a single
   step before decoding a sequence — this isolates the fused→split / rotary bridge from the
   cache-advance logic.
3. **Then the generator and the sequence-level test.** Wire the decode model into the
   `AutoregressiveModel` sampling path, add `reset()` cache-clearing, and bring up the
   full-sequence correctness test (Scope step 4). If the KV-cache row and the full-forward row
   disagree at any position, that is a real defect to diagnose (most likely the rotary convention or
   a cache index), investigated with `ar-profile-analyzer` on the generated kernels per Rule 3b —
   **not** worked around by loosening the assertion.
4. **Verify narrowly.** Run the new tests and the existing `slidingWindowGenerationMatchesFullForward`
   and `destroyingGeneratorReleasesItsBuffers` via the MCP test runner with `test_classes` /
   `test_methods`, each under an explicit timeout ≤ 2400 s; and the build validator (`checkstyle`,
   `code_policy`, `test_timeouts`, `duplicate_code`). No full-module or full-suite run.
5. **Honor enforcement.** Existing acceptance tests are read-only except for additions; the new
   capability is pinned by new tests that assert real behavior (exact agreement with the full
   forward, causality, determinism), never trivially-true ones. `test-integrity-check` must pass.

---

## Success Criteria

- A single-position KV-cache decode path exists for `CausalLanguageModel`'s weight layout (fused
  `qkv` + `rope_inv_freq`), built over the model's own `StateDictionary` with no weight copy, and
  reached through an `AutoregressiveModel` so it shares the greedy/temperature/top-p sampling path.
- A new test asserts that **every** greedily decoded byte from the KV-cache path equals the
  most-probable byte of the corresponding row of an independent full `(seqLen, vocabSize)` forward
  pass, for positions beyond the first, and that decoding is reproducible after `reset()`.
- A cache-isolation/causality test shows later positions read earlier cached entries and that a
  cleared cache does not leak across sequences.
- The rotary frequencies the decode path applies at a position equal those the full-sequence path
  applies at that row (established, not assumed).
- The existing generation tests still pass; the build validator is clean; `test-integrity-check`
  passes; no existing acceptance assertion is weakened.
- `training-a-language-model.md` and (if the generator surface changed) `ml-inference-pipeline.md`
  describe the KV-cache decode; Javadoc on new methods is complete; no source line numbers in docs.

## Dependencies

None blocking. The cached single-position `attention(...)` family and `causalMask(shape, position)`
already exist and are exercised by the SkyTnt tests; the unified sampling path (PR #741) and the
honest attention backward pass (the prior correctness cycles) are on `master`. This task bridges the
self-hosted LM's weight layout to that existing cache machinery.

## Open questions

These are decisions or risks the approver should see; they are to be resolved by evidence in the
Approach, not pre-judged here.

- **Clearing the cache on `reset()` is the deciding constraint between the two generator routes.**
  The per-layer key and value caches are allocated inside the attention layer itself —
  `AttentionFeatures.attentionArguments(...)` creates and clears a `(seqLen, dim)` key cache and
  value cache and binds them as layer arguments, "the state the block writes on every forward pass
  and reads on every later one." They therefore live in the compiled decode model, not in any
  `AutoregressiveModel` wrapper. `AutoregressiveModel.of(...)` owns only its input buffer,
  `position`, temperature and sampling ops; it runs the model with `model.forward(in)` and has no
  handle on those caches, so `of(...).reset()` returns `position` to the start but leaves the
  previous sequence's keys and values in the model — which contradicts Scope step 3's requirement
  that `reset()` start a new sequence clean. Whichever route is chosen, `reset()` must reach into
  the decode model to re-clear its layer caches; `of(...)` as it stands offers no such hook, while
  a dedicated subclass (mirroring how `SlidingWindowAutoregressiveModel.reset()` clears its window)
  can drive a model-level cache-clear it exposes. Cache clearing, not just the sampling path, is
  what picks between the two routes, and the cache-isolation test (Scope step 4) is what proves the
  chosen route actually clears on reset.
- **The single-step pin compares the selected byte, not exact row values.** Single-position
  attention sums over the cached past while the full forward sums over the whole masked window, so
  the two softmax rows are mathematically equal but need not be bit-identical in floating point
  (summation order differs). The committed assertion is byte-identical *greedy output* — the argmax
  byte of each row — which is robust to that difference and is what
  `slidingWindowGenerationMatchesFullForward` already compares; the single-step pin in Approach step
  2 should likewise compare the selected byte (or use a tolerance on row values), not assert exact
  row equality, or a correct decode could fail on summation-order noise alone.

## Machine

The committed deliverable — the decode capability and its correctness test — **runs on any
backend**: it compiles two small models (the KV-cache decode and a full forward) and asserts their
greedy output agrees byte for byte, which any backend can do. **No workstream settings file is
written.** The follow-up that *measures* the throughput improvement and runs long-sample
sampling-quality comparisons needs a macOS/Metal node and a trained checkpoint, and belongs to that
separate measurement cycle — exactly as the nucleus-sampling cycle deferred its Metal measurement.
