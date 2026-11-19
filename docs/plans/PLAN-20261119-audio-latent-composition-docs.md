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
of THE FUNDAMENTAL RULE**: the entire blend — dot-product scales, the selection guard, the
weighted mean, and the autoencoder decode — is one `CollectionProducer` graph compiled to a
native kernel, evaluated through `evaluateOptimized()` precisely because several consumers
read the same reductions. A model (or a person) studying the platform should be able to read
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
     `org.almostrealism.studio.ml`): adding sources, the per-source merge **weight vector**
     and its L2 normalization (`AudioComposer.normalizeWeights`), the scalar **contribution**,
     and how a source's scale is the dot product of its weight vector with the position
     vector (`ComposableAudioFeatures.getScale` → `c(weights).multiply(tiled).traverse(2).sum()`).
   - The blend graph itself: `AudioComposer.getInterpolatedLatent` building the scales, the
     `total`, the `greaterThan(total, 0, …)` **unselected guard** that makes the result the
     exact weighted mean when any source is selected and a plain mean otherwise (never a
     divide-by-zero), negative-contribution clamping, and the weighted-mean-vs-weighted-sum
     choice (`setNormalizedBlend`) with its loudness rationale. `getResultant` =
     `autoencoder.decode(getInterpolatedLatent(value))`.
   - **THE FUNDAMENTAL RULE in the small**: state plainly that the composed latent is a
     computation graph, and that it must be evaluated through `evaluateOptimized()` (as the
     production boundaries `AudioGenerator`, `LegacyAudioGenerator`, `AudioModulator` do)
     because the shared `getScale` reductions and the shared `total` are read by several
     consumers; a plain `.evaluate()` would embed each reduction per consumer. Show the
     wrong-vs-right form briefly.
   - Known limits and the boundary to the rest of generative audio: this page covers the
     **on-platform latent-composition** slice; the text-conditioned diffusion generation
     path (`AudioGenerator` → `AudioDiffusionGenerator` → `DiffusionSampler`, with an ONNX
     conditioner/autoencoder) is download-gated and owns the sampling loop — cross-reference
     `sampling-loop-examples.md` rather than re-documenting it here.

2. **Add a drift-guard test** `LatentAudioCompositionExamplesTest` (in `studio/compose`,
   `TestSuiteBase`, `@Test(timeout=…)`), modelled on the existing doc-cycle tests
   (`VectorIndexExamplesTest`, `TextTokenizationExamplesTest`). It uses a **synthetic
   autoencoder** — the same approach `AudioComposerTest`'s `StubAutoEncoder` already uses, or
   a tiny identity/affine encoder so a `decode` can be asserted too — so it runs on **any
   backend with no download**. It should pin the exact code shown in the doc: a two-source
   weighted blend's expected latent, the unselected-guard plain-mean fallback, and the
   evaluate-vs-evaluate-optimized equivalence of the composed result.

3. **Wire the new page into the index**: add it to `llms.txt` under the appropriate
   heading, cross-link it from `studio/compose/README.md` (ML Audio Package) and from
   `sampling-loop-examples.md` (as the composition counterpart to the diffusion loop), and
   complete the `org.almostrealism.studio.ml` `package-info.java` Javadoc if it omits the
   composition classes.

Out of scope: any change to the composition code itself; documenting the ONNX diffusion
generation path end to end; any audio-output/playback documentation.

## Approach

- Follow Rule 1: `consult` the composition classes first, then read `AudioComposer`,
  `ComposableAudioFeatures`, `AutoEncoder`, and `AudioComposerTest` in full before writing a
  word of prose, so every claim cites current source by stable identifier (no line numbers,
  per `docs/CLAUDE.md`).
- Write the worked example as code that actually compiles and runs inside the drift-guard
  test; the doc's snippets are extracted from the test, not hand-written, so they cannot
  drift. Every numeric assertion in the doc is reproduced by the test.
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
- No production code is modified.

## Dependencies

None. The subsystem (`AudioComposer`, `ComposableAudioFeatures`, `AutoEncoder`) is merged on
`master`, and `AudioComposerTest` already demonstrates the synthetic-autoencoder test path,
so the drift-guard test has a proven template. Runs on any node — no ONNX model, no Metal, no
download — so there is **no workstream settings file** for this branch.

## Estimated Complexity

Medium. One new internals page plus one new drift-guard test, three small index/cross-link
edits, and a package-info completion; no production code changes; verifiable on any backend.

## Relationship to existing work

Does not overlap the three open proposed plans — `PLAN-20261106-ferrofluid-simulation`
(rendering proof-of-value roadmap), `PLAN-20261114-jni-native-precision-symbols` (hardware
correctness), and `PLAN-20261114-manual-isolation-prevention` (optimizer discipline). It
continues the documentation arc that filled the tokenizer, vector-index, and
simulation-pipeline gaps, now applied to the latent-audio-composition subsystem, and it sets
the anchor page the broader generative-audio proof-of-value track can grow from.
