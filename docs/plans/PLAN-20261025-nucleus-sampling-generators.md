# Expose nucleus (top-p) sampling through the autoregressive generators

**Category:** Proof of Value (self-hosted LM generation)
**Branch:** `project/plan-20261025-022810`
**Date:** 2026-10-25
**Estimated complexity:** Small–Medium

---

## Motivation

The self-hosted language model is the platform's first concrete step toward software that
understands its own architecture: a byte-level transformer trained on the project's own
documentation that, as of PR #685, writes readable text about itself. Every cycle since has
either made its gradients honest (the attention K/V and differential-lambda correctness arc) or
documented the machinery that produces it (the PDSL reference, merged 2026-10-24). With the
attention backward pass now honest end to end, the next step on the proof-of-value track —
named first in the "what comes next" list of the last four Manager Log entries — is better
generation. The most direct lever is the sampling strategy.

The generators today offer only two decoding modes to a caller:

- **Greedy** (`temperature == 0`): always the argmax.
- **Temperature** (`temperature > 0`): sample from the full tempered distribution.

Greedy decoding of a weakly-generalizing model collapses to the unconditional most-frequent byte
— the documented `"The computation graph is compiled to the the the the…"` from the 2026-10-19
run. Full-distribution temperature sampling is the opposite failure: it draws from the entire
vocabulary tail, so a low-probability byte occasionally derails the sequence. **Nucleus (top-p)
sampling** is the standard remedy for both: restrict sampling to the smallest set of most-probable
tokens whose cumulative probability reaches `p`, then renormalize and sample within it. It keeps
the varied, non-degenerate quality of sampling while cutting off the unreliable tail.

Here is the gap this task closes. `AutoregressiveModel.sampleToken(logits, vocabSize, temperature,
topP, random)` **already implements nucleus filtering in full** — the sorted-cumulative cutoff and
renormalization are there and correct by inspection. But **no public API lets any caller set
`topP`**, and both call sites hardcode it to `1.0` (no filtering):

- `SlidingWindowAutoregressiveModel.sample(...)` calls
  `sampleToken(logProbabilities, vocabSize, getTemperature(), 1.0, random)`.
- The sampling lambda built by `AutoregressiveModel.of(...)` calls
  `sampleToken(logits, vocabSize, 1.0, 1.0, random)`.

So the platform ships a nucleus-sampling implementation that is dead code from a consumer's point
of view. The capability exists; it simply cannot be reached. Exposing it is the smallest change
that materially improves what the self-hosted LM can produce, and it is a prerequisite for the
honest sampling-quality comparison the training doc already promises ("a temperature or top-p
cheap enough to compare against greedy decoding").

### A second, related defect in the same code

Reading the two sampling paths side by side surfaces an inconsistency that must be characterized
before it is changed (no speculation — the plan's first step pins it with a host test):

- The **`SlidingWindow`** path passes the **raw** log-probabilities and the **configured**
  temperature straight into `sampleToken`, which tempers and softmaxes them once. This is the
  clean path.
- The **`of(...)`** path first evaluates `rescale` (`logits / temperature`) and `softmax` on the
  device, **then** calls `sampleToken(logits, vocabSize, 1.0, 1.0, random)` with a **hardcoded
  temperature of `1.0`**. Because `sampleToken` with `temperature == 1.0` re-exponentiates and
  renormalizes its input, the `of(...)` path applies softmax to an already-softmaxed distribution
  — a second, flattening transform — and never reads the caller's configured temperature inside
  `sampleToken`. The two generators therefore sample from **different distributions** for the same
  logits and the same `setTemperature(...)` value.

This is squarely in scope: the fix for both defects is the same unification — route raw logits
through `sampleToken` once, with the configured temperature and the configured top-p, from both
generators.

### Connection to the larger vision

Readable, non-degenerate generation is the visible proof that the platform can train and run a
real model end to end on its own documentation. Nucleus sampling is table stakes for that, and the
implementation is already written — this task is almost entirely about making an existing
capability honest and reachable, and making the two generators agree. It is the disciplined,
high-confidence next step before the heavier generation work (a single-position KV-cache decode;
conditional diffusion training).

---

## Scope

Concrete, in `engine/ml`:

1. **Add top-p as first-class generator state on `AutoregressiveModel`.** A `getTopP()` /
   `setTopP(double)` pair mirroring the existing `getTemperature()` / `setTemperature(double)`,
   defaulting to `1.0` so that **behavior is unchanged unless a caller opts in**. Unlike
   `temperature` (device-resident because `of(...)`'s `rescale` reads it as `cp(temperature)` in
   the graph), top-p never enters any computation graph — it is consumed only host-side inside
   `sampleToken` — so it is represented in whatever form keeps the two call sites clean without a
   reach-around (see Approach for the two candidate representations). Validate the input range
   (`0 < topP <= 1`) at the setter, the call site that owns the contract — not silently inside
   `sampleToken`.

2. **Wire top-p through `SlidingWindowAutoregressiveModel.sample(...)`:** pass `getTopP()` in
   place of the hardcoded `1.0`.

3. **Unify the `of(...)` sampling path with the `SlidingWindow` path.** Route the raw logits
   through `sampleToken` once with the configured temperature and configured top-p, eliminating
   the double-softmax / hardcoded-`1.0`-temperature discrepancy identified above. The greedy
   branch (`temperature == 0`) is unchanged.

4. **Deterministic tests** (host-side, no training, no Metal — see Approach), extending
   `TestSuiteBase`, every `@Test` with a `timeout`:
   - A nucleus-filtering test that pins the top-p branch of `sampleToken` the way
     `ArgmaxConsistencyTest` pins the greedy branch: with a fixed-seed `Random` and a crafted
     distribution, assert that tokens outside the nucleus are **never** selected across many
     draws, that tightening `p` shrinks the reachable support monotonically, and that `topP == 1.0`
     reproduces the unfiltered tempered distribution.
   - A test that the two generators now sample **identically** from the same logits given the same
     temperature, top-p and seed (closing the discrepancy, and guarding against its return).
   - A test that `setTopP` rejects out-of-range values and that the default (`1.0`) leaves
     generation behavior unchanged.

5. **Documentation:** update the sampling sections of `docs/internals/ml-inference-pipeline.md`
   (which currently reproduces the `sampleToken(logits, vocabSize, 1.0, 1.0, random)` line and the
   "Temperature = 0 / Temperature > 0" list) and the generation paragraph of
   `docs/internals/training-a-language-model.md` to describe nucleus sampling and the unified path.
   Complete/refresh the Javadoc on the new accessors and on `sampleToken`'s `topP` parameter.

**Explicitly out of scope** (follow-ups, not this branch): the single-position KV-cache decode; a
training run that *measures* generation quality under top-p (that needs a macOS/Metal node and a
~20-minute training run, and belongs to the cycle that does the measurement); batch sizes above 1;
any change to `CausalLanguageModelTest`'s existing acceptance assertions.

---

## Approach

1. **Characterize before changing.** Write the host test that evaluates `sampleToken` directly on
   a small crafted distribution for both the `SlidingWindow`-style call (raw logits + temperature)
   and the `of(...)`-style call (pre-softmaxed logits + temperature `1.0`), and record the two
   distributions they sample from. This converts the "double-softmax" reading above from inspection
   into an asserted fact and fixes the expected post-unification behavior, so the change is
   evidence-led. `ar-profile-analyzer` is not needed — `sampleToken` is pure host Java over a
   `double[]`, not a compiled kernel.

2. **Choose the top-p representation that keeps both call sites clean.** Two candidates, both
   consistent with existing code:
   - A plain `double topP` field on `AutoregressiveModel` with `get/set`. Clean for
     `SlidingWindow` (which overrides `sample()` and has `this`), but the `of(...)` sampling
     lambda is constructed *before* the instance exists and cannot see `this` — so the lambda
     would need the value threaded in another way.
   - A single-element `PackedCollection topP` that `setTopP` writes and the `of(...)` lambda reads
     with `topP.toDouble(0)` — **exactly** the shared-collection mechanism `temperature` already
     uses, which the `of(...)` lambda already closes over. This avoids the closure problem and
     introduces no new pattern.
   The second mirrors the accepted `temperature` design and is the expected choice; the
   implementer should confirm it reads cleanly and that `destroy()` releases the collection,
   following the `temperature` precedent. Whichever is chosen, the accessors live on
   `AutoregressiveModel` (generator state belongs on the generator), and no member exposes a
   concrete subclass or reaches into another object's fields.

3. **Make the change minimal and behavior-preserving by default.** `topP` defaults to `1.0`;
   with the default, the unified path must reproduce today's `SlidingWindow` behavior exactly
   (raw logits, configured temperature, no filtering). The only intended behavioral change at the
   default is that `of(...)` stops double-softmaxing — which is the defect fix, covered by the
   two-generators-agree test.

4. **Verify narrowly.** Run `ArgmaxConsistencyTest` (greedy branch must stay green), the new
   nucleus test, and the two-generators-agree test via the MCP test runner with
   `test_classes`/`test_methods`, each under an explicit timeout. Run the build validator
   (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`). No full-module or full-suite
   run.

5. **Honor the enforcement rules.** `ArgmaxConsistencyTest` is an existing test and is read-only
   except for additions; the new behavior is pinned by **new** tests, never by weakening an
   existing one. The new tests assert real behavior (nucleus membership, monotonic support
   shrinkage, cross-generator equality) — they must not be trivially-true.

---

## Success Criteria

- `getTopP()` / `setTopP(double)` exist on `AutoregressiveModel`, default to `1.0`, and
  `setTopP` rejects values outside `(0, 1]`.
- `SlidingWindowAutoregressiveModel` and `AutoregressiveModel.of(...)` both pass the configured
  top-p and the configured temperature into `sampleToken`, and sample **identically** from the
  same logits under the same `(temperature, topP, seed)` — proven by a test.
- The `of(...)` double-softmax / hardcoded-temperature discrepancy is gone and cannot return
  without failing a test.
- A new nucleus-sampling test pins the top-p branch of `sampleToken`: out-of-nucleus tokens never
  appear, tightening `p` monotonically shrinks the reachable support, and `topP == 1.0` matches the
  unfiltered tempered distribution.
- `ArgmaxConsistencyTest` and the new tests pass via the MCP test runner; the build validator is
  clean.
- `ml-inference-pipeline.md` and `training-a-language-model.md` describe nucleus sampling and the
  unified sampling path; Javadoc on the accessors and on `sampleToken`'s `topP` is complete.
- No existing acceptance test is weakened; `test-integrity-check` passes.

## Dependencies

None. `sampleToken`'s nucleus implementation already exists; this task exposes and unifies it. The
honest attention backward pass (the prior correctness cycles) and the PDSL reference (prior doc
cycle) are already on `master`.

## Machine

Runs on **any backend**. The committed deliverable is a host-side API change plus deterministic
tests that construct logits directly and call the sampling path with a seeded `Random`; no
training, no Metal kernel, no GPU is involved. **No workstream settings file is written.** (The
follow-up that *measures* generation quality under top-p will need a macOS/Metal node, but that is
a separate cycle.)
