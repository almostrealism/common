# NumberConditioner: Build Fourier Frequencies in Instance Context

**Tracker task:** `2bf3efde-6a4f-4636-8a08-71c0e9463f1c` — "NumberConditioner: static expo()
factory and constructor use anonymous DiffusionTransformerFeatures although the class implements it"
**Release:** Common 0.76

## Motivation

`engine/ml/src/main/java/org/almostrealism/ml/audio/NumberConditioner.java` is declared
`public class NumberConditioner implements DiffusionTransformerFeatures`, yet two places mint a
throwaway anonymous `DiffusionTransformerFeatures` to reach mixin methods the class already has:

- The public 6-argument learned-frequency constructor chains with
  `this(..., new DiffusionTransformerFeatures() { }.cp(fourierWeights), ...)`, because Java forbids
  instance calls inside a `this(...)` argument list.
- `public static NumberConditioner expo(...)` creates
  `DiffusionTransformerFeatures features = new DiffusionTransformerFeatures() { };` only to call
  `features.expoFourierFrequencies(fourierDim, minFreq, maxFreq)`.

Both exist because the producer for the frequency matrix is built *before* the instance exists and
then passed into the private canonical constructor
(`NumberConditioner(double, double, int, int, Producer<PackedCollection>, PackedCollection, PackedCollection)`). The fix is to move that construction into the instance, where the class's own
`cp(...)` and `expoFourierFrequencies(...)` apply, as the task asks.

## Scope

Changes only `NumberConditioner.java` and adds coverage for the expo path in
`NumberConditionerTest.java`. In scope:

1. Remove both anonymous `DiffusionTransformerFeatures` instances from `NumberConditioner`.
2. The private canonical constructor receives the *raw* frequency inputs and builds the
   `frequencies` producer itself, in instance context.
3. `expo(...)` keeps its public signature (callers: `StableAudio3.smallConditioner`,
   `StableAudio3Test`, `StableAudio3ConditionerTest`)
   and becomes a one-line delegation to a constructor.
4. The public constructors keep their signatures (callers: `NumberConditionerTest`).

Out of scope (noted, not touched): other anonymous Features instances in `engine/ml`
(`AutoregressiveModel.DIST`, the anonymous-instance accessors in `RotationFeatures` and
`AttentionFeatures`).
These are in classes that are themselves Features interfaces or have no instance to call through,
so they are a different question from this task's — see Open Questions.

## Approach

`frequencies` is a `final` field, so every constructor path must end in one constructor that
assigns it. That constructor must therefore accept the *inputs* to the frequency matrix rather
than the finished producer. The two sources are:

- **learned**: a `PackedCollection` of shape `[fourierDim/2, 1]` → `cp(fourierWeights)`
- **expo**: `(fourierDim, minFreq, maxFreq)` → `expoFourierFrequencies(fourierDim, minFreq, maxFreq)`

Proposed shape:

```java
public NumberConditioner(double minVal, double maxVal, int outDim,
                         PackedCollection fourierWeights, PackedCollection projWeight,
                         PackedCollection projBias) {
    this(minVal, maxVal, outDim, learnedFourierDim(fourierWeights), fourierWeights,
            0.0, 0.0, projWeight, projBias);
}

public static NumberConditioner expo(double minVal, double maxVal, int outDim, int fourierDim,
                                     double minFreq, double maxFreq,
                                     PackedCollection projWeight, PackedCollection projBias) {
    return new NumberConditioner(minVal, maxVal, outDim, fourierDim, null,
            minFreq, maxFreq, projWeight, projBias);
}

/** Canonical constructor: learned frequencies when fourierWeights is non-null, else the expo ladder. */
private NumberConditioner(double minVal, double maxVal, int outDim, int fourier,
                          PackedCollection fourierWeights, double minFreq, double maxFreq,
                          PackedCollection projWeight, PackedCollection projBias) {
    // existing validation, unchanged
    ...
    this.frequencies = fourierWeights == null
            ? expoFourierFrequencies(fourier, minFreq, maxFreq)
            : cp(fourierWeights);
    ...
}
```

Notes on the design:

- `learnedFourierDim(...)` stays a `private static` helper in the `this(...)` argument list. It
  does shape arithmetic on a method argument and uses no Features method, so the static context is
  legitimate and needs no anonymous instance.
- The null-vs-non-null switch is confined to one private constructor and documented in its javadoc.
  The alternative — two public constructors each assigning every field — would duplicate the seven
  field assignments and the validation block, which the code rules forbid.
- A second alternative is to drop `final` from `frequencies` and assign it in each public
  constructor after a shared validation call. That weakens immutability for no gain and is not
  recommended.
- Graph construction is unchanged: the same `cp(...)` / `expoFourierFrequencies(...)` producers are
  built, once per instance, exactly as today. No host-side evaluation is introduced.
- Javadoc for the class and for `expo` stays; the canonical constructor's javadoc gains the
  description of the two frequency sources.

## Success Criteria

1. `grep -n "new DiffusionTransformerFeatures" engine/ml/src/main/java/org/almostrealism/ml/audio/NumberConditioner.java`
   returns nothing.
2. Public API of `NumberConditioner` (both public constructors, `expo`, `embed`, `normalize`,
   `normalizeRange`, `globalCond`, `crossAttentionToken`, getters) is unchanged; `StableAudio3` and
   all tests compile without edits.
3. Existing tests pass, unmodified:
   - `NumberConditionerTest` (all six methods — learned-frequency path)
   - `StableAudio3ConditionerTest#assemblesPromptPaddingAndDuration` (expo path via `expo(...)`)
   - `ExpoFourierFeaturesTest` (the frequency ladder the expo path builds)
4. A new test method in `NumberConditionerTest`, e.g. `expoEmbeddingMatchesReference`, constructs a
   conditioner through `NumberConditioner.expo(...)` and checks `embed(value)` element-wise against a
   reference computed from the geometric ladder (`f_k = exp(log(minFreq) + k * step)`), the
   `[cos(2πtf), sin(2πtf)]` features and the projection — so the expo path has direct, standalone
   coverage rather than only coverage through the Stable Audio 3 conditioner.
5. `mvn clean install -DskipTests` succeeds and the build validator (`checkstyle`, `code_policy`,
   `test_timeouts`, `duplicate_code`) reports no violations.

## Dependencies

None. The task has no blockers. Nothing here needs Metal or a particular platform; the tests run on
any backend, so no workstream settings file is required.

## Open Questions

1. **Nullable discriminator vs. a value type.** The plan uses a nullable `fourierWeights` to select
   learned vs. expo frequencies inside the private constructor. If the reviewer prefers a
   self-describing alternative, the canonical constructor could instead take a small
   `Function<DiffusionTransformerFeatures, Producer<PackedCollection>>` supplied by each public entry
   point and applied to `this`. That adds indirection for two cases and is not recommended, but it is
   the option if the null switch is judged unclear.
2. **Other anonymous Features instances in `engine/ml`.** `RotationFeatures` and `AttentionFeatures`
   expose `static` accessors returning an anonymous instance of themselves, and
   `AutoregressiveModel` holds a `static final DistributionFeatures DIST`. Those classes do not
   implement the interface they instantiate (or are the interface), so they are not the defect this
   task describes. If the release wants them reviewed, that should be a separate tracker task.

## Estimated Complexity

**Small** — one production class (~30 changed lines), one added test method.
