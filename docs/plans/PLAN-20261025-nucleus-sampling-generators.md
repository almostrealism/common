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

Note who each defect affects. The self-hosted LM generates through
`SlidingWindowAutoregressiveModel` (the generator `CausalLanguageModelTest` builds over a
`CausalLanguageModel`), which is already the clean path, so for the self-hosted LM this task is
only about reaching top-p. The double-softmax lives in `of(...)`, whose production callers are
`Qwen3` and `Llama2`; fixing it changes pretrained-model generation, not the self-hosted LM (see
Open questions).

### What already exists elsewhere

Top-p is already reachable in one place, outside these two generators: the MIDI generator
`MoonbeamMidiGenerator` (`studio/compose`) keeps its own `double topP` field with a public
`setTopP(double)` (no range validation; its Javadoc says "0.0 to 1.0") and passes it to its GRU
decoder from the `sample` function it hands to the public `AutoregressiveModel` constructor. It does
not go through `sampleToken`. So this plan does not introduce top-p to the platform; it brings the
text generators up to what the MIDI generator already offers. The earlier
`PLAN-20261005-self-hosted-lm-beat-baseline-and-generate.md` names "static `sampleToken` with
top-p" as an existing piece of infrastructure but never exposes it. No other plan in `docs/plans/`
covers it.

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
   branch (`temperature == 0`) is unchanged. Once `of(...)` stops tempering and softmaxing on the
   device, its `rescale` and `softmax` evaluables (and their entries in the anonymous subclass's
   `destroy()`) have no remaining use and should be removed rather than left as dead code.
   `of(...)` is not used only by the self-hosted LM: `Qwen3` and `Llama2` build their generators
   with it, so this step changes their sampling at `temperature > 0` too (see Open questions).

4. **Deterministic tests** (host-side, no training, no Metal — see Approach), extending
   `TestSuiteBase`, every `@Test` with a `timeout`:
   - A nucleus-filtering test that pins the top-p branch of `sampleToken` the way
     `ArgmaxConsistencyTest` pins the greedy branch: with a fixed-seed `Random` and a crafted
     distribution, assert that tokens outside the nucleus are **never** selected across many
     draws, that tightening `p` shrinks the reachable support monotonically, and that `topP == 1.0`
     reproduces the unfiltered tempered distribution.
   - A test that the two generators now sample **identically** from the same logits given the same
     temperature, top-p and seed (closing the discrepancy, and guarding against its return).
     This is not reachable with today's API: `of(...)` creates its own unseeded `new Random()`
     inside the factory, and neither the factory nor `AutoregressiveModel` lets a caller supply
     one, whereas `SlidingWindowAutoregressiveModel` takes its `Random` as a constructor argument.
     The test therefore needs a way to give `of(...)` a seeded `Random` (for example an overload
     that accepts one, with the existing three-argument form delegating to it). Exercising the
     generators also means constructing them: `SlidingWindowAutoregressiveModel` compiles the
     `CausalLanguageModel` it is given, and `of(...)` needs a `CompiledModel` and compiles its
     `indexOfMax` evaluable, so this test (unlike the `sampleToken` tests) compiles kernels, on
     whatever backend the node has. `sample(PackedCollection)` is `protected`, so a test in
     `org.almostrealism.ml` can call it on both generators directly with crafted logits rather
     than running a forward pass. See Open questions.
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

2. **Choose the top-p representation that keeps both call sites clean.** Three candidates, all
   consistent with existing code:
   - A plain `double topP` field on `AutoregressiveModel` with `get/set`, read through a `sample`
     **lambda**. Clean for `SlidingWindow` (which overrides `sample()` and has `this`), but the
     `of(...)` sampling lambda is constructed *before* the instance exists and cannot see `this` —
     so the lambda would need the value threaded in another way.
   - The same plain `double` field, with `of(...)` **overriding `sample(PackedCollection)`** in the
     anonymous `AutoregressiveModel` subclass it already returns (it already overrides `destroy()`
     there) and passing `null` as the `sample` function — the pattern `SlidingWindow` already
     uses. Inside the override, `getTopP()` and `getTemperature()` are reachable on `this`, so the
     closure problem disappears without a new constructor parameter.
   - A single-element `PackedCollection topP` that `setTopP` writes and the `of(...)` lambda reads
     with `topP.toDouble(0)` — the shared-collection mechanism `temperature` already uses. The
     collection would have to reach `AutoregressiveModel` the way `temperature` does, i.e. as a
     **new constructor parameter** on both the public five-argument constructor and the protected
     two-argument one. That changes the signature `SlidingWindowAutoregressiveModel` and
     `MoonbeamMidiGenerator` (in `studio/compose`, a different module) call, unless an overload is
     kept. It also puts a value that never enters a computation graph into device-addressable
     memory for no reason.
   The plan originally named the `PackedCollection` option as the expected choice; given that the
   anonymous subclass in `of(...)` already exists, the plain-field-plus-override option is
   arguably the smaller change (see Open questions). On lifecycle, note that the `temperature`
   precedent is **not** "`AutoregressiveModel.destroy()` releases it": the base class's
   `destroy()` explicitly does not release the position or temperature passed to its constructor.
   The **owner** releases it — the anonymous subclass returned by `of(...)`,
   `SlidingWindowAutoregressiveModel.destroy()` and `MoonbeamMidiGenerator`. A `PackedCollection`
   top-p would need the same owner-side release at each of those sites. Whichever is chosen, the
   accessors live on `AutoregressiveModel` (generator state belongs on the generator), and no
   member exposes a concrete subclass or reaches into another object's fields.

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
  same logits under the same `(temperature, topP, seed)` — proven by a test (which requires a
  way to seed `of(...)`; see Open question 6).
- The `of(...)` double-softmax / hardcoded-temperature discrepancy is gone and cannot return
  without failing a test, and the `rescale`/`softmax` evaluables it leaves unused are removed
  from `of(...)` and its `destroy()`.
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

## Open questions

These are for the person deciding whether the plan goes ahead. They do not change what the plan
proposes.

1. **The default-case change reaches pretrained-model generation.** "Behavior unchanged unless a
   caller opts in" holds for top-p, but not for the `of(...)` unification. `Qwen3` and `Llama2`
   build their generators with `AutoregressiveModel.of(...)`, so at any `temperature > 0` they
   will stop double-softmaxing and sample from a sharper distribution than today. That is the
   intended fix, but it is a visible behavior change for those models, not only for the
   self-hosted LM. Their greedy output (`temperature == 0`) is unaffected. Does the approver
   accept that, and should the release notes or the pretrained-model docs say so? No existing test
   pins their tempered output (the two `of(...)` uses in `CausalLanguageModelTest` are a
   generation/destroy check, not a distribution check), so nothing will flag the change on its own.
2. **Which top-p representation.** The plan first named the single-element `PackedCollection`
   (mirroring `temperature`) as the expected choice. Overriding `sample(...)` in the anonymous
   subclass `of(...)` already returns, with a plain `double` field, avoids a constructor-signature
   change that reaches `studio/compose`. The approver may want to settle this up front rather than
   leave it to the implementer.
3. **An inert accessor on generators that do not use `sampleToken`.** `AutoregressiveModel` can
   also be built with an arbitrary `sample` function, and `MoonbeamMidiGenerator` does exactly
   that, keeping its own `topP`. Once `setTopP` exists on `AutoregressiveModel`, calling it on
   such an instance silently does nothing. `getTemperature()`/`setTemperature()` already work this
   way, since a custom `sample` function decides whether to read the temperature collection, so
   this follows existing precedent rather than adding a new kind of contract gap. Still, the
   project's "honor the interface" rule makes it worth an explicit decision: either document on
   the accessor that it applies to the `sampleToken`-based generators, or route
   `MoonbeamMidiGenerator`'s `topP` through the base-class accessor too. The second is a
   `studio/compose` change and is out of scope here unless the approver adds it.
4. **Validation inconsistency with the existing `setTopP`.** The plan validates `(0, 1]` on
   `AutoregressiveModel.setTopP`. `MoonbeamMidiGenerator.setTopP` does not validate, and its
   Javadoc gives the range as "0.0 to 1.0". Leaving them inconsistent is harmless but untidy.
   Aligning them is a one-line follow-up outside this plan's module.
5. **Temperature stays device-resident for no graph reason.** After the unification, nothing in
   `of(...)` reads `temperature` inside a computation graph. `rescale` was its only graph
   consumer. The single-element collection then exists only for API compatibility with the
   constructors. That is fine to leave, but the plan's Scope sentence explaining why `temperature`
   is device-resident describes today's code, not the code after this change.
6. **`of(...)` cannot be seeded.** The factory creates `new Random()` internally and offers no way
   to replace it, so the "same seed" agreement test in Scope step 4 needs a small API addition
   (an `of(...)` overload taking a `Random`, or the `Random` becoming generator state settable on
   `AutoregressiveModel`). That addition is not otherwise listed in Scope or Success Criteria. It
   also makes `Qwen3`/`Llama2` sampling reproducible, which they cannot be today. The approver
   should decide whether that API change is part of this task. If it is not, the agreement test
   has to be dropped or weakened to a distribution-level comparison.

## Machine

Runs on **any backend**. The committed deliverable is a host-side API change plus deterministic
tests that construct logits directly and call the sampling path with a seeded `Random`; no
training and no Metal-specific kernel is involved. The `sampleToken` tests are pure host Java;
the two-generators-agree test constructs both generators and so compiles a small model and the
`of(...)` evaluables on the node's default backend, which any backend can do. **No workstream
settings file is written.** (The follow-up that *measures* generation quality under top-p will
need a macOS/Metal node, but that is a separate cycle.)
