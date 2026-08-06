# Unresolved review comments carried forward from PR #385 (phase 23)

Every review comment still open on PR #385 at the time it was merged, recorded verbatim so
that closing the PR does not lose them. Line numbers are as of the phase 23 branch head and
will drift; the file and the quoted intent are what matter.

Companion to `HOST_ARRAY_AUDIT.md`, which lists the 631 undefended host-array and loop sites
across the branch. Several comments below are specific instances of that general problem.

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

| Line | Comment |
| --- | --- |
| 78 | How is this different than assetEquals defined in the TestFeatures interface? because it accepts variable length? is the length every different than the length of the expected collection? |

**Carried forward as:** determine whether `assertConvolutionEquals` is redundant with
`TestFeatures.assertEquals`, and delete it if the length parameter is never anything other
than the expected collection's own length.

---

## engine/utils — TemporalFeaturesTest

| Line | Comment |
| --- | --- |
| 42 | Should be CollectionProducer. |

---

## studio/compose — MixdownManagerFilterAutomationTest

| Line | Comment |
| --- | --- |
| 279 | An empty if block? You don't even read the code you write? |

**Carried forward as:** an empty `if` block was left behind at this site. Read the
surrounding method and either restore the intended body or remove the branch.

---

## studio/compose — DelayNetworkBehaviorTest

| Line | Comment |
| --- | --- |
| 238 | There is already a dedicated MatrixFeatures method for this, do not create a new one. |
| 251 | We should just add the CollectionProducer form of this to VectorFeatures. |

**Carried forward as:** replace the locally written matrix construction with the existing
`MatrixFeatures` method, and move the vector operation at 251 up to `VectorFeatures` in its
`CollectionProducer` form rather than keeping a local copy.

---

## studio/compose — MixdownManagerPdslTest

| Line | Comment |
| --- | --- |
| 915 | Why isn't this a CollectionProducer? |

---

## studio/compose — MixdownManagerPdslVerificationTest

| Line | Comment |
| --- | --- |
| 653 | Why is this a mix of CollectionProducer and host math? |
| 1018 | This should return PackedCollection. |
| 1375 | STOP USING double[] |
| 1524 | Should return PackedCollection. |

**Carried forward as:** this file holds the largest concentration of undefended sites in the
audit. The render and WAV-writing chain (`renderJavaPath`, `renderPdslPath`,
`renderPdslMaster`, `renderFeedbackCombMono`, `writeDiffWav`, `writeMonoWav`,
`loadLoopSource`, `tryLoadClip`) still threads `double[]` end to end, and the per-pass input
assembly still builds host arrays. Converting it means changing that whole chain to carry
collections, not patching individual lines.

---

## Standing instruction from review

No `double[]`. Use `PackedCollection` / `CollectionProducer`. Where a conversion appears
impossible, the reason may not be the prohibition on calling `evaluate()` — that rule exists
to move work onto the device, and citing it to justify leaving work on the host inverts it.
