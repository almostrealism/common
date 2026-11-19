---
status: proposed
created: 2026-11-19
closed:
tracker: []
---

# Document composing audio in latent space: the AutoEncoder blend pipeline

## Category

Documentation.

## Motivation

Generative audio is one of the platform's crown-jewel proof-of-value capabilities, and
the subsystem that composes audio by blending encoded sources in **latent space** has been
under active development for a month — four pull requests in a row refined it
(per-source merge weights #715, batched ranked selection #972, the normalized blend #980,
and the similarity-failure trace #988), and the review trail records a genuinely subtle
numerical contract (L2-normalized weight vectors, negative-contribution clamping to avoid a
`NaN` divisor, the weighted-mean-vs-weighted-sum distinction, near-origin behaviour). Yet
the subsystem is named in **no internals page** and does not appear in `llms.txt`'s
internals index. Its only prose home is a handful of class-description sections in
`studio/compose/README.md` ("ML Audio Package" → `AudioComposer` / `ComposableAudioFeatures`)
plus Javadoc. A newcomer — human or model — who asks "how does this platform compose audio
from several sources?" gets class names, not a worked, runnable explanation.

This is exactly the flagship-gap shape the recent documentation cycles were built to close
(text tokenization, the vector index, the two connectome/neural-population simulations): a
real, actively-used subsystem whose behaviour lives only in code and review notes. Closing
it matters for the larger vision twice over. First, it is a **clean, non-training exemplar
of THE FUNDAMENTAL RULE**: the entire blend — dot-product scales, the selection guard and the
weighted mean — is one `CollectionProducer` graph compiled to native code, evaluated through
`evaluateOptimized()` because, in the normalized blend, several consumers read the same
reductions. (The autoencoder decode is *not* part of that graph: both real implementations
wrap a host-side model call in a `Producer` — see "Accuracy notes" below.) A model (or a person) studying the platform should be able to read
one page and see latent-space arithmetic expressed as a computation graph rather than as Java
loops. Second, the `AutoEncoder` abstraction this page documents is the shared seam between
the on-platform composition path and the heavier text-conditioned diffusion generation path,
so the page becomes the natural anchor the generative-audio story grows from.

## Scope

Concrete, actionable items:

1. **Write `docs/internals/latent-audio-composition.md`** — a tested worked example that
   walks from encoded sources to a composed, decoded waveform:
   - The `AutoEncoder` abstraction (`org.almostrealism.ml.audio.AutoEncoder`): `encode`/
     `decode` as `Producer` transforms, the latent/audio sample-rate compression ratio, and
     why composition happens in the compact latent space rather than on raw samples.
   - `AudioComposer` and `ComposableAudioFeatures` (`studio/compose`,
     `org.almostrealism.studio.ml`): adding sources (`addAudio` encodes raw audio;
     `addSource(features)` / `addSource(features, weights)` take an already-encoded latent),
     the per-source merge **weight vector** — sampled from a Gaussian by `createWeights` and
     L2-normalized only when the static flag `AudioComposer.normalizeWeights` is set (default
     `true`), whereas an explicit vector from `addSource(features, weights)` or
     `setWeights(index, weights)` is used verbatim and never normalized — the scalar
     **contribution** (`ComposableAudioFeatures.setContribution`), and how a source's scale is
     the dot product of its weight vector with the position vector, clamped to be
     non-negative and then multiplied by the contribution (`ComposableAudioFeatures.getScale`
     → `c(weights).multiply(tiled).traverse(2).sum()`, then `max(…, 0)`). State that a
     length-`L` weight vector is broadcast to every `(T, B)` cell (`broadcastWeights`), so
     with vector weights every cell of a source gets the same scale; per-cell variation needs
     a `(T, B, L)` weight tensor.
   - The blend graph itself, **both modes**: by default (`normalizedBlend == false`, and the
     mode every production caller uses today — no production code calls
     `setNormalizedBlend`), `getInterpolatedLatent` is the weighted **sum**
     `Σ source.getResultant(value)`. With `setNormalizedBlend(true)` it re-clamps each scale
     with `max(getScale(value), 0)`, sums them into `total`, and uses the per-cell
     `greaterThan(total, 0, 0, 1)` **unselected guard**: where `total > 0` the result is the
     exact weighted mean `Σ s_i·x_i / total`; where `total == 0` it is the plain mean
     `Σ x_i / n` (never a divide-by-zero). Cover negative-contribution exclusion and the
     loudness rationale from the `setNormalizedBlend` Javadoc. `getResultant` =
     `autoencoder.decode(getInterpolatedLatent(value))`.
   - **THE FUNDAMENTAL RULE in the small**: state plainly that the composed latent is a
     computation graph, and that the production boundaries evaluate it through
     `evaluateOptimized()` (`AudioGenerator` and `LegacyAudioGenerator` on
     `getInterpolatedLatent`, `AudioModulator.project` on `getResultant`). Be precise about
     why: in the normalized blend each `getScale` reduction is read twice (by `total` and by
     its own term) and `total` is read by `unselected`, every term and the divisor, so a
     plain `.evaluate()` would embed those reductions per consumer; in the default weighted
     sum each reduction has a single reader. Show the wrong-vs-right form briefly.
   - **The decode boundary, honestly**: `decode` is where the graph ends. Neither real
     implementation is a `CollectionProducer` composition — `CompiledModelAutoEncoder.decode`
     returns a lambda producer that evaluates the latent and calls `decoder.forward`, and
     `OnnxAutoEncoder.decode` (`extern/ml-onnx`) wraps an ONNX Runtime call in `func(…)`. The
     page must not imply the decode is compiled into the blend kernel.
   - Known limits and the boundary to the rest of generative audio: this page covers the
     **on-platform latent-composition** slice; the text-conditioned diffusion generation
     path (`AudioGenerator` → `AudioDiffusionGenerator` → `DiffusionSampler`, with an ONNX
     conditioner/autoencoder) is download-gated and owns the sampling loop — cross-reference
     `sampling-loop-examples.md` rather than re-documenting it here.

2. **Add a drift-guard test** `LatentAudioCompositionExamplesTest` (in `studio/compose`,
   `TestSuiteBase`, `@Test(timeout=…)`), modelled on the existing doc-cycle tests
   (`VectorIndexExamplesTest` in `engine/ml` `org.almostrealism.persist.index.test`,
   `TextTokenizationExamplesTest` in `engine/ml` `org.almostrealism.ml.tokenization`). It uses
   a **synthetic autoencoder** so it runs on **any backend with no download**. Note that
   `AudioComposerTest`'s `StubAutoEncoder` throws `UnsupportedOperationException` from both
   `encode` and `decode` (its tests only evaluate `getInterpolatedLatent`), so asserting a
   decoded waveform needs a new stub whose `decode` is a small `CollectionProducer` transform
   (e.g. identity or an affine map); the page must say that stub is a test device and that
   real decoders are host-side model calls (see the decode-boundary item above). It should pin
   the exact code shown in the doc: a two-source weighted-sum latent (the default mode), the
   same two sources under the normalized blend, the unselected-guard plain-mean fallback, the
   decoded result through the stub, and the equality of `.evaluate()` and
   `evaluateOptimized()` on the composed latent.

   **Overlap with existing tests.** `AudioComposerTest` already asserts most of these
   behaviours (`explicitWeightsAreDeterministic`, `negativeScaleIsClamped`,
   `contributionWeightsSources`, `normalizedBlendIsWeightedMean`,
   `normalizedBlendWithNoSelectedSourceIsMean`, `normalizedBlendNearOriginKeepsProportions`,
   `normalizedBlendExcludesNegativeContribution`, `normalizedBlendWithAllNegativeContributionsIsMean`).
   The new test's job is to pin the *page's snippets*, not to re-prove the semantics; keep it
   to the few examples the page shows rather than duplicating those cases (the
   `duplicate_code` check fails on 10+ identical lines across files).

3. **Wire the new page into the index**: add it to `llms.txt` under "Internals
   Documentation → ML and Training" (next to the `Sampling Loop Examples` entry), cross-link
   it from `studio/compose/README.md` ("ML Audio Package", which today has `AudioComposer` and
   `ComposableAudioFeatures` subsections but no mention of the normalized blend) and from
   `docs/internals/sampling-loop-examples.md` (its "AutoEncoder Abstraction" section, as the
   composition counterpart to the diffusion loop), and fix the
   `org.almostrealism.studio.ml` `package-info.java` Javadoc, which currently **omits**
   `ComposableAudioFeatures` and **misdescribes** `AudioComposer` as a composer "that
   coordinates model inference and scene generation" — it blends encoded sources in latent
   space. (The `ComposableAudioFeatures` class Javadoc's "attention-conditioned audio
   generation" wording is worth checking in the same pass.)

Out of scope: any change to the composition code itself; documenting the ONNX diffusion
generation path end to end; any audio-output/playback documentation.

## Approach

- Follow Rule 1: `consult` the composition classes first, then read `AudioComposer`,
  `ComposableAudioFeatures`, `AutoEncoder`, and `AudioComposerTest` in full before writing a
  word of prose, so every claim cites current source by stable identifier (no line numbers,
  per `docs/CLAUDE.md`).
- Write the worked example as code that actually compiles and runs inside the drift-guard
  test, and copy the doc's snippets verbatim from that test. There is no snippet-extraction
  tooling (the earlier doc-cycle tests are kept in step the same way, by hand), so "cannot
  drift" means: every snippet has a test method that runs the same code, and every number
  the doc prints is an assertion in that method.
- Keep the doc honest about THE FUNDAMENTAL RULE: show the `evaluateOptimized()` requirement
  as a first-class lesson, since it is the exact mistake the #980 review had to correct.
- Run the drift-guard test narrowly (one class) via the MCP test runner, and run the build
  validator (`checkstyle`, `code_policy`) before completing.

## Success Criteria

- `docs/internals/latent-audio-composition.md` exists, reads as a worked example from
  encoded sources to a decoded waveform, and every code snippet and numeric result in it is
  reproduced by `LatentAudioCompositionExamplesTest`.
- `LatentAudioCompositionExamplesTest` passes on a non-Metal backend with no network access
  or model download, extends `TestSuiteBase`, and carries a timeout.
- The page is reachable: linked from `llms.txt`, `studio/compose/README.md`, and
  `sampling-loop-examples.md`; the `org.almostrealism.studio.ml` package Javadoc names the
  composition classes.
- Build validator reports zero `checkstyle` and `code_policy` violations; the
  temporary-identifier check passes.
- No production code is modified other than Javadoc (the `package-info.java` fix in item 3
  lives under `src/main`, so the change set is not docs-only and gets a code review).
- The page states which blend mode is the default, and does not describe the decode as part
  of the compiled blend graph.

## Dependencies

None. The subsystem (`AudioComposer`, `ComposableAudioFeatures`, `AutoEncoder`) is merged on
`master`, and `AudioComposerTest` already demonstrates the synthetic-autoencoder test path,
so the drift-guard test has a proven template. Runs on any node — no ONNX model, no Metal, no
download — so there is **no workstream settings file** for this branch.

## Estimated Complexity

Medium. One new internals page plus one new drift-guard test, three small index/cross-link
edits, and a package-info Javadoc fix; no production code changes beyond that Javadoc;
verifiable on any backend.

## Accuracy notes (from review against the code)

These correct readings an earlier draft of this plan got wrong or left implicit; the page
must follow them.

- **Default mode is the weighted sum.** `normalizedBlend` defaults to `false`, and the only
  callers of `setNormalizedBlend` are in `AudioComposerTest`. The normalized weighted mean is
  an opt-in mode that no production path enables today.
- **The decode is outside the compiled graph.** `CompiledModelAutoEncoder.decode` is a lambda
  producer around `decoder.forward`; `OnnxAutoEncoder.decode` wraps a host ONNX call in
  `func(…)`. Only the blend is a `CollectionProducer` composition.
- **`normalizeWeights` applies only to sampled weights**, and is a public static mutable flag
  (process-wide), not a per-composer setting.
- **Vector weights give a uniform scale per source.** Broadcasting a length-`L` vector to every
  `(T, B)` cell means the "per-cell scale" is one number per source unless a `(T, B, L)` tensor
  is supplied.

## Open questions

- **Optimization through the decoder.** Because `CompiledModelAutoEncoder.decode` returns a
  non-`Process` lambda that calls `latent.get().evaluate(args)`, `AudioModulator.project`'s
  `getResultant(...).evaluateOptimized()` does not actually optimize the latent graph when that
  decoder is used — `Process.optimized` returns a non-`Process` unchanged. It is harmless in
  the default weighted sum (single-reader reductions) but would bite a normalized blend. The
  page should not present `getResultant(...).evaluateOptimized()` as the model to copy until
  this is resolved; filed as tracker task `232983ee-121b-4df8-8a28-b2c1acdc95ef`.
- **Which mode should the page lead with?** The normalized blend is the better-specified and
  better-tested contract, but it is not what production runs. Leading with it risks teaching a
  mode nobody uses; leading with the sum hides the subtlest numerical contract. The reviewer
  should decide (the plan as written documents both, sum first).
- **Is a separate drift-guard class worth it** given `AudioComposerTest` already covers the
  semantics? The alternative is to have the page cite `AudioComposerTest` methods directly and
  add only the decode-through-stub example. Either satisfies the success criteria if each
  snippet maps to a test method.

## Relationship to existing work

Does not overlap the three open proposed plans — `PLAN-20261106-ferrofluid-simulation`
(rendering proof-of-value roadmap), `PLAN-20261114-jni-native-precision-symbols` (hardware
correctness), and `PLAN-20261114-manual-isolation-prevention` (optimizer discipline). It
continues the documentation arc that filled the tokenizer, vector-index, and
simulation-pipeline gaps, now applied to the latent-audio-composition subsystem, and it sets
the anchor page the broader generative-audio proof-of-value track can grow from.
