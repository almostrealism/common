# Unresolved review comments carried forward from PR #385 (phase 23)

Every review comment still open on PR #385 at the time it was merged, recorded verbatim so
that closing the PR does not lose them. Line numbers are as of the phase 23 branch head and
will drift; the file and the quoted intent are what matter.

Companion to `HOST_ARRAY_AUDIT.md`, which lists the 631 undefended host-array and loop sites
across the branch. Several comments below are specific instances of that general problem.

**Status: every comment recorded here is closed.** The only work still carried by this
document is the deferred `HnswIndex` batch scoring described in the first section, which
was postponed deliberately pending CI results. What remains after that is the audit.

---

## engine/ml — SimilarityMetric

**DONE in phase 24.** `similarity` and `normalize` return `CollectionProducer`,
`Node.cachedData` is a `PackedCollection`, persistence encodes the node's collection
directly, and no `double[]` remains in either file. All four comments below are closed.

| Line | Comment | Status |
| --- | --- | --- |
| 62 | Must return PackedCollection. | done |
| 91 | Remove this method. | done |
| 100 | You MAY NOT normalize on the host. | done |
| 100 | This method should not exist. | done |

**Still open:** `HnswSearchTest.performanceSearch10kRecords128dim` times out under the
conversion (57 of 58 tests pass). The cause is not kernel-compilation caching — compiling the
scorer once against `x()`/`y()` arguments changed nothing. Each evaluation constructs
`AcceleratedProcessDetails` and `Evaluable.async()` starts a new OS thread per request
(`Evaluable.java:144`), so a per-pair evaluation is expensive however well kernels cache.
The fix is to score the whole candidate frontier in one computation, a redesign of
`HnswIndex.searchLayer` / `greedyClosest`. Deferred deliberately pending CI results.

`HnswIndex.insert`, `search` and `score` are listed in
`ProducerPatternDetector.EVALUATE_ALLOWED_FILE_METHODS` / `TODOUBLE_ALLOWED_FILE_METHODS`
as graph-walk boundaries. Those entries should be removed once batch scoring lands.

---

## engine/utils — FirFilterTestFeatures

**DONE.** `assertConvolutionEquals` no longer exists anywhere in the tree; it was
redundant with `TestFeatures.assertEquals` apart from ignoring shape, and callers
reshape instead.

| Line | Comment | Status |
| --- | --- | --- |
| 78 | How is this different than assetEquals defined in the TestFeatures interface? because it accepts variable length? is the length every different than the length of the expected collection? | done |

---

## engine/utils — TemporalFeaturesTest

**DONE.** `highPassCoefficients` returns `CollectionProducer` and is expressed as
`subtract(oneHot(filterOrder + 1, filterOrder / 2), ...)` using the `VectorFeatures`
one-hot rather than a local construction.

| Line | Comment | Status |
| --- | --- | --- |
| 42 | Should be CollectionProducer. | done |

---

## studio/compose — MixdownManagerFilterAutomationTest

**DONE.** The branch reads `if (!opts.zeroGenome) { params.randFill(); }` — a new
collection is already zero, so the zero-genome case needs no body.

| Line | Comment | Status |
| --- | --- | --- |
| 279 | An empty if block? You don't even read the code you write? | done |

---

## studio/compose — DelayNetworkBehaviorTest

**DONE.** The test calls `identity(channels)` from `MatrixFeatures` and `oneHot(...)`
from `VectorFeatures`; no local copy of either remains.

| Line | Comment | Status |
| --- | --- | --- |
| 238 | There is already a dedicated MatrixFeatures method for this, do not create a new one. | done |
| 251 | We should just add the CollectionProducer form of this to VectorFeatures. | done |

---

## studio/compose — MixdownManagerPdslTest

**DONE in phase 24.** The comment sat on the tap-echo assertion loop in
`testMixdownManagerReverbPath`, which walked a host `double[]` of pass 2 output and
indexed it per tap. All of the taps are now gathered in one computation —
`cp(pass2Output).valueAt(cp(delays).subtract(c(REVERB_SIGNAL_SIZE))).abs()` — and the
remaining loop only formats one assertion message per tap from that result.

| Line | Comment | Status |
| --- | --- | --- |
| 915 | Why isn't this a CollectionProducer? | done |

The rest of that test method went with it: pass energies and the impulse tail are
collections, energy per pass is `sum(passOut.sq())`, the WAV tail is clamped by
`bound(passOut, -1.0, 1.0)` and written through the `PackedCollection` overload of
`writeDemoWav`, and the total is `sum(cp(passEnergies))`. Verified by running the
method (it is `knownIssue`, so it needs `AR_LONG_TESTS=enabled AR_KNOWN_ISSUES=enabled`
to execute rather than skip); energies came out `[0.0, 4.0, 0.72, 0.275, 0.119]` —
silence, four unit-magnitude tap echoes, then decay.

Other host arrays remain in this file; they are audit items, not this comment.

---

## studio/compose — MixdownManagerPdslVerificationTest

**DONE in phase 24.** The render and WAV chain carries collections end to end and the file
went from 65 host-array occurrences to zero.

| Line | Comment | Status |
| --- | --- | --- |
| 653 | Why is this a mix of CollectionProducer and host math? | done |
| 1018 | This should return PackedCollection. | done |
| 1375 | STOP USING double[] | done |
| 1524 | Should return PackedCollection. | done |

`renderJavaPath`, `renderPdslPath`, both `renderPdslMaster` overloads,
`renderFeedbackCombMono`, `loadLoopSource` and `tryLoadClip` take and return collections;
`writeMonoWav` and `writeDiffWav` take collections; `firstNonFinite` is a device reduction.
`loopedSource(totalFrames)` returns one device-resident signal built by repeating the clip on
the device, replacing an `IntToDoubleFunction` sampled per frame — that is what let the rest
of the chain drop its arrays. Per-pass input is `repeat(0, channels, ...)` into the input
collection; channel summing is a `matmul` against a row of ones. `PdslAudioDemoTest` gained a
`writeDemoWav(File, PackedCollection, int)` overload, so the WAV writer owns the one place
samples leave the system, and `MixdownChannelPdslTest` / `PdslAudioDemoTest` accumulate their
demo signals as collections.

---

## studio/compose — MoonbeamValueDistributionTest

**DONE in phase 24.** The file went from 36 host-array occurrences to zero.
`gruStep` and `linearForward` are `CollectionProducer` compositions; the reported
aggregates come from `max`, `mean`, `variance` and `indexOfMax` rather than local
reimplementations.

Two performance properties are load-bearing and should not be undone:

- The step input and the logits are read back into fixed collections. An operand's
  offset forms part of its signature, so a fresh allocation per step makes every
  step a distinct graph and recompiles the pipeline once per step. This alone was
  the difference between exceeding the 60s timeout and finishing in 32s.
- The six aggregates in `statistics` are concatenated into one computation. Six
  separate evaluations meant six kernels compiled per shape.

`testGruDecoderLogitDistribution` ("value 4365 exceeds max 4098") and
`testDecodedAttributeRangeValidation` ("greedy trial 0: onset 5953") fail, and
did so identically at HEAD with the change stashed. They look like a genuine
decoder defect — decoded attribute values overshooting their vocab ranges — and
are worth investigating on their own.

---

## Standing instruction from review

No `double[]`. Use `PackedCollection` / `CollectionProducer`. Where a conversion appears
impossible, the reason may not be the prohibition on calling `evaluate()` — that rule exists
to move work onto the device, and citing it to justify leaving work on the host inverts it.
