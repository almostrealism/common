---
status: completed
created: 2026-10-27
closed: 2026-10-27
tracker: [97e964a3-4740-4e0b-846b-2a05c27e3da8]
---

# Make PdslBuiltins an instance type and remove the PdslFeatures static holder

**Tracker task:** `97e964a3-4740-4e0b-846b-2a05c27e3da8`, "Make PdslBuiltins an instance type
and remove the PdslFeatures static holder"
**Release:** the release under development (the version in the root `pom.xml`)
**Estimated complexity:** Small

---

## Motivation

`PdslBuiltins`, the PDSL built-in function library, is written entirely as static code. It has
a static entry point `call(String, List<Object>, Set<Block>)`, about thirty `private static
callXxx(...)` helpers, and a private constructor. Most helpers need framework feature default
methods (`shape`, `layer`, `c`, `cp`, `subset`, `concat`, `dense`, `rmsnorm`, `cacheWrite`, ...).
Static code cannot call default methods, so the library reaches them through a holder object:
`PdslFeatures`, a package-private class that implements the feature interfaces and exposes a
`static final INSTANCE`. `PdslBuiltins` keeps it in `private static final PdslFeatures FEATURES`.
`PdslInterpreter` has its own copy of the same field and uses it for `c`, `cp`, `shape`,
`subset`, `accumBlocks` and `concatBlocks`.

A features mixin is meant to be implemented by the type that uses it, not reached through a
holder. The holder exists only to work around the static methods. Making the library an
instance type removes both. It also follows the project rule that behaviour lives on the type
that owns it rather than in static helpers. `PdslFeatures` is a second name for the same
capabilities, and every reader has to trace through it.

## The code as it is today (corrections to the task description)

The task description was written before the PDSL core moved modules. Three of its details no
longer match the tree. The plan follows the code:

1. **Location.** Commit `d1d963f3b` ("Move the PDSL language core to domain/graph...") moved the
   language core from `engine/ml` to `ar-graph`. The files are now:
   - `domain/graph/src/main/java/org/almostrealism/dsl/PdslBuiltins.java` (1055 lines)
   - `domain/graph/src/main/java/org/almostrealism/dsl/PdslFeatures.java` (33 lines)
   - `domain/graph/src/main/java/org/almostrealism/dsl/PdslInterpreter.java` (1454 lines)

   The tests are still in `engine/ml/src/test/java/org/almostrealism/dsl/`, in the same Java
   package. They can therefore call package-private members.
2. **Interface set.** `PdslFeatures` is
   `class PdslFeatures implements LayerRoutingFeatures, TemporalFeatures`. It is not
   `AttentionFeatures, RotationFeatures, TemporalFeatures`. `AttentionFeatures` and
   `RotationFeatures` live in `engine/ml` (`org.almostrealism.ml`). `ar-graph` cannot depend on
   `engine/ml`: the dependency runs the other way, and `engine/ml`'s `AttentionPrimitives`
   (`implements PdslExtension, AttentionFeatures`) registers the attention primitives into the
   interpreter. So "at minimum the set `PdslFeatures` aggregates today" means
   `LayerRoutingFeatures` and `TemporalFeatures`. `LayerRoutingFeatures` extends
   `LayerFeatures`, which pulls in the convolution, normalization, activation, matrix and
   collection features.
3. **Helper count.** `PdslBuiltins` has 30 `private static` members. 28 of them are methods.
   The other two are the `FEATURES` field and the nested class `ConvolutionArguments`. The task
   says about 45.

`TemporalFeatures` does not provide any method that either class calls. Every `FEATURES.*`
call resolves to `LayerFeatures`, `LayerRoutingFeatures`, `ActivationFeatures`,
`NormalizationLayerFeatures`, `ConvolutionLayerFeatures`, `MatrixFeatures` or
`CollectionFeatures`. Of the 32 distinct methods called, the most common are `shape` (20), `c` (11),
`layer` (5) and `subset` (5). `PdslInterpreter` imports `org.almostrealism.time.TemporalFeatures`
but never uses it.

Callers of the static API:
- `PdslInterpreter.tryCallBuiltin` calls `PdslBuiltins.call(name, args, build.consumedBranchOperands)`.
- `PdslLanguageReferenceExamplesTest.dispatches` calls `PdslBuiltins.call(name, new ArrayList<>(), new HashSet<>())`.
- `Conv1dSnakePrimitivesTest` calls `PdslBuiltins.call(...)` three times, once each in
  `conv1dRejectsWrongArgumentCount`, `snakeRejectsWrongArgumentCount` and
  `convTranspose1dRejectsWrongArgumentCount`.

No other file under `src/` refers to `PdslBuiltins`, `PdslFeatures` or `normalizeToProducer`
(`grep -rln` over every `*.java` outside `target/` returns only the three main classes,
`package-info.java` and the two tests above), and no other module calls the library.

The static `PdslInterpreter.normalizeToProducer(Object, TraversalPolicy, String)` also uses
`FEATURES`. It is the single conversion point for producer arguments. Its callers are
`PdslBuiltins` (seven call sites, in `producerArg`, `callScale` (twice),
`callScaleAlongAxis`, `callCacheRow` (twice) and `callScaledDotProduct`), the interpreter's `bindProducerParameter`, and the static nested
class `EnvContext` (the `PdslPrimitiveContext.toProducer` adapter). It has to change with the
rest, because otherwise the interpreter keeps a static path to a holder.

## Scope

In scope:
- `PdslBuiltins` becomes a package-private, instance-based class that implements
  `LayerRoutingFeatures` and `TemporalFeatures`. Its entry point and helpers become instance
  methods that call the default methods directly.
- `PdslInterpreter` owns one `PdslBuiltins` instance. It uses that instance for built-in
  dispatch, for producer normalization, and for the feature calls it makes itself. Its `FEATURES`
  field is removed.
- `normalizeToProducer` moves from a static method on `PdslInterpreter` to an instance method on
  `PdslBuiltins`.
- `PdslFeatures.java` is deleted.
- The two tests change to the instance call form, and only the call form.
- Documentation is updated to match: the `package-info.java` paragraph that names
  `PdslFeatures`, and the `docs/internals/pdsl-language.md` row that names
  `PdslInterpreter.normalizeToProducer`.

Out of scope (as the task states): what any built-in computes, its PDSL name or argument
contract, and the `registerPrimitive` path for domain libraries. The static helpers
`PdslPrimitiveContext.toInt`/`toInts`/`toDouble` are static methods on a different public
interface used by every primitive. They are also out of scope.

`PdslInterpreter`'s other static members do not use features and stay as they are: the
`EXTENSIONS` list (`PdslExtension.discover()`), the public `enableVectorizedForEach` switch
(set by `MixdownLayerPerformanceTest`), the `ALL_CHANNELS` sentinel, and the static nested
classes `Environment` and `Build`. This plan removes only the feature holder and the one static
method that reaches it.

Not already done: on `origin/master`, `PdslFeatures.java` still exists and both classes still
hold the `FEATURES` field. No other plan in `docs/plans/` covers this change; the two earlier
PDSL plans (`PLAN-20261023-pdsl-language-reference.md`, `PLAN-20261026-pdsl-authoring-tutorial.md`)
are `completed` and concern documentation only.

## Approach

### 1. Ownership: the interpreter owns an instance (no singleton)

`PdslInterpreter` gets `private final PdslBuiltins builtins = new PdslBuiltins();`. There is no
static accessor. Reasons, to be recorded in `PdslBuiltins`' class Javadoc:

- The library holds no state. The only per-build state, the set of consumed branch operands,
  belongs to the interpreter's `Build` and is passed in on every call. One instance per
  interpreter costs one small object and needs no shared global.
- A singleton accessor would keep a static member on the class. That is what this task removes,
  and it would bring back a "reach through a static" pattern under a new name.
- An instance that the interpreter owns can later be configured per interpreter without changing
  any call site.
- The tests share the package and can construct the library directly
  (`new PdslBuiltins().call(...)`), exactly as the interpreter does.

### 2. `PdslBuiltins`

- Declaration: `final class PdslBuiltins implements LayerRoutingFeatures, TemporalFeatures`.
- Remove `private static final PdslFeatures FEATURES`. Replace today's `private PdslBuiltins() { }`
  (whose Javadoc says built-ins are reached only through `call`) with a package-private no-arg
  constructor and a Javadoc that says the interpreter, and same-package tests, construct it.
- `static Object call(...)` becomes `Object call(...)`. Each `private static callXxx`,
  `producerArg` and `scaledDotProductLayer` becomes a `private` instance method.
- Replace every `FEATURES.x(...)` with `x(...)`, and `FEATURES::cacheWrite`/`FEATURES::cacheRead`/
  `FEATURES::passThrough` with `this::cacheWrite`, and so on. This is a mechanical change, but
  watch for name collisions:
  - The helper `callShape` and the default method `shape(...)` have different names, so they do
    not collide.
  - The static imports `toInt`, `toInts` and `toDouble` from `PdslPrimitiveContext` do not
    collide with any default method in the `LayerRoutingFeatures`/`TemporalFeatures` hierarchy.
    Checked by reflection over the compiled `LayerRoutingFeatures` and `TemporalFeatures`
    (all inherited methods): neither declares `toInt`, `toInts`, `toDouble`, `call`,
    `normalizeToProducer`, `producerArg`, `scaledDotProductLayer` or any `call*` method, so the
    instance methods neither override nor shadow a feature default.
  - The call sites use 32 distinct feature methods; the convolution ones are `convolution1d` and
    `convTranspose1d` (`ConvolutionLayerFeatures`). Several names (`shape`, `c`, `subset`,
    `reshape`, `permute`, `concat`, `sigmoid`, `tanh`) are overloaded across more than one
    feature interface. Today they already resolve on a type implementing the same two
    interfaces (`PdslFeatures`), so the unqualified calls resolve to the same overloads.
  - Inside lambdas, `this::` and unqualified calls refer to the enclosing `PdslBuiltins`, which
    is the intended behaviour.
- `ConvolutionArguments` stays a `private static final` nested class. It does not use features,
  and a static nested type is not a static method. The completion condition this plan applies
  (see Success criteria) is about static methods and static fields, not nested types.
- Move `normalizeToProducer(Object, TraversalPolicy, String)` here as a package-private instance
  method, with its Javadoc. Its body is unchanged except that `FEATURES.c`/`cp`/`shape` become
  direct calls. The seven internal call sites change from `PdslInterpreter.normalizeToProducer` to
  `normalizeToProducer`.
- Update the class Javadoc: it is an instance type that implements the features it needs, the
  interpreter owns one instance, and the reasons are those in step 1. The sentence "Every method
  is stateless" becomes "The library holds no state".

### 3. `PdslInterpreter`

- Remove `private static final PdslFeatures FEATURES` and the unused
  `import org.almostrealism.time.TemporalFeatures`.
- Add the `builtins` field described in step 1.
- `tryCallBuiltin` becomes `builtins.call(name, args, build.consumedBranchOperands)`.
- `bindProducerParameter` calls `builtins.normalizeToProducer(...)`.
- `EnvContext` changes from a `private static class` to a `private class` (an inner class), so
  that `toProducer` can call `builtins.normalizeToProducer(...)`. The other choice is to pass the
  instance to its constructor. Either works. An inner class is the smaller change, because the
  only construction site is `new EnvContext(env)` in `evaluateFunctionCall`.
- The interpreter's own feature calls go through `builtins.` instead of `FEATURES.`. There are
  ten of them: `accumBlocks` in `interpretAccumBlocks`, `concatBlocks` in
  `interpretConcatBlocks`, `shape(...)` in `evaluateShape` and twice in
  `interpretForEachChannel`, and the per-channel slicing in the `PdslNode.Subscript` branch of
  `evaluateExpression` (four `shape(...)` calls and one `subset(...)`). `evaluateFieldAccess`
  makes no feature calls. The task offers two choices here: the
  interpreter implements the features itself, or it gets them from the library instance. The
  plan takes the library instance, for this reason. `PdslInterpreter` is `public`. If it
  implemented `LayerRoutingFeatures`, the whole feature default-method surface (`dense`,
  `convolution1d`, `fft`, ...) would become part of the interpreter's public API, which would be
  a much larger and unintended surface. Getting the features from the package-private library
  keeps the public API unchanged. Note the tension with the Motivation, recorded as open
  question 3: calling `builtins.shape(...)` from the interpreter is still "reaching a features
  mixin through another object", only through a non-static one.
- Update the Javadoc references `{@link #normalizeToProducer(...)}` in `bindProducerParameter`
  and `EnvContext` so that they point to `PdslBuiltins#normalizeToProducer`.

### 4. Delete `PdslFeatures.java`

Once steps 2 and 3 compile, nothing refers to it. Check this with
`grep -rn PdslFeatures --include=*.java .`, which should return nothing outside `target/`.

### 5. Tests: change the call form only

- `Conv1dSnakePrimitivesTest`: change `PdslBuiltins.call(...)` to
  `new PdslBuiltins().call(...)` at all three sites (`conv1dRejectsWrongArgumentCount`,
  `snakeRejectsWrongArgumentCount`, `convTranspose1dRejectsWrongArgumentCount`). No assertion, argument, tolerance or
  annotation changes.
- `PdslLanguageReferenceExamplesTest.dispatches`: the same change. This is a `private static`
  helper in a test class, so it can either construct an instance inline or use a
  `private static final PdslBuiltins BUILTINS = new PdslBuiltins()` field. Constructing inline
  is the smaller diff. The `CATALOG_COUNT` regex ``The (\d+) built-ins dispatched by `PdslBuiltins` ``
  does not change, so the matching sentence in the doc must stay word for word.

### 6. Documentation

- `domain/graph/src/main/java/org/almostrealism/dsl/package-info.java`: replace the paragraph
  "The built-in function library (`PdslBuiltins`) and the feature mixin it and the interpreter
  use (`PdslFeatures`) are package-private..." with one that names only `PdslBuiltins`. The new
  paragraph says that the library is a package-private instance type, owned by each
  interpreter, that implements the feature interfaces it needs.
- `docs/internals/pdsl-language.md`:
  - The `producer([shape])` row of the parameter type table names
    `PdslInterpreter.normalizeToProducer`. Change it to `PdslBuiltins.normalizeToProducer`.
  - The other references (resolution order item 3 "a built-in in `PdslBuiltins`", the catalog
    sentence "The 29 built-ins dispatched by `PdslBuiltins` (package-private)", the placement
    rule, and the two `PdslBuiltins.callCapture` mentions) stay accurate, because the class name
    and `callCapture` stay the same. Keep the catalog sentence exactly as it is.
- `docs/internals/pdsl-audio-dsp.md` mentions only `PdslBuiltins.java` and its role. No change
  is needed.

## Success criteria

- `PdslBuiltins` has no static methods and no static fields. Its only static member is the
  nested type `ConvolutionArguments`. It implements `LayerRoutingFeatures` and
  `TemporalFeatures`, and its methods call feature defaults directly.
- `PdslFeatures.java` no longer exists, and no Java source or durable document outside
  `docs/plans/` refers to it (completed plans are historical records; see open question 5).
- `PdslInterpreter` has no `FEATURES` field and no static method that uses features.
  `normalizeToProducer` lives on `PdslBuiltins`.
- The only test changes are the four call sites in step 5 (`git diff` shows nothing else in
  those files). `tools/ci/agent-protection/detect-test-hiding.sh` and `test-integrity-check`
  pass: no assertion, tolerance, dimension, timeout or `@TestDepth` changes.
- `mvn clean install -DskipTests` succeeds.
- These targeted tests pass, run through `ar-test-runner` in `engine/ml` with at most five
  classes per invocation:
  - `PdslLanguageReferenceExamplesTest`, `Conv1dSnakePrimitivesTest`, `PdslLoaderTest`,
    `PdslProgramScopeTest`, `PdslNestedLayerShapeTest`
  - `PdslStackTest`, `PdslTutorialExamplesTest`, `CacheReadPrimitiveTest`,
    `AttentionPrimitivesTest`, `SdpaPrimitivesTest`
  - `SequenceAttentionPrimitivesTest`, `TransformerBlockFixtureParityTest`, `SdpaAssetTest`,
    `FeedForwardAssetTest`, `SequenceAttentionAssetTest`
  - `DifferentialAttentionAssetTest`, `T5GemmaEncoderLayerAssetTest`, `GruDecoderAssetTest`,
    `SkytntBlockAssetTest`
  - In `studio/compose`, as a check of the audio registrar path that runs alongside the
    built-ins: `PdslAudioDspTest`, `DelayFeedbackBankPdslTest`, `MixdownManagerPdslTest`.
    The last is needed because none of the `engine/ml` classes above use `for each channel`,
    so they never reach the interpreter's `FEATURES` calls in `interpretForEachChannel` or the
    per-channel subscript slicing in `evaluateExpression`. `MixdownManagerPdslTest` reaches
    them at default depth: `testMixdownEfxBusBuilds` builds a `for each channel` bus with
    `[channel]` subscripts (the vectorized path, which calls `shape(channels, signalSize)`), and
    `testMixdownManagerRectangularRoute` builds `mixdown_efx_bus_rectangular`, whose second
    `for each channel` follows a rectangular `route` and is therefore not channel-aligned. That
    forces the per-channel dispatch, which calls `shape(1, signalSize)` and slices
    `buffers[channel]`/`heads[channel]` through the subscript branch. Whether the
    `CollectionProducer` branch (`subset(...)`) is reached depends on the argument types the
    test passes; the implementer should confirm it, and add a test if no existing one does.
    `DelayFeedbackBankPdslTest`'s only `for each channel` test is `@TestDepth(2)`, so it does
    not cover this path at default depth.
  - A skipped test is not a pass. `GruDecoderAssetTest` has a `@TestDepth(2)` method guarded by
    `Assume.assumeTrue` on the presence of real model weights, and several of these classes have
    depth-gated methods. Report which methods ran and which were skipped rather than reading a
    green run as full coverage.
- `ar-build-validator` reports no violations for checkstyle, code_policy, test_timeouts and
  duplicate_code. `PdslInterpreter` gets smaller, so it stays under the 1600-line `FileLength`
  cap.
- `docs/internals/pdsl-language.md` and `package-info.java` describe where the built-ins live and
  how producer arguments are normalized, and neither mentions `PdslFeatures`.

## Dependencies

None. The task has no blockers, and every file involved is in `domain/graph` (main),
`engine/ml` (tests) and `docs/internals/pdsl-language.md`. No `pom.xml` changes are needed: `ar-graph` already reaches `ar-time`
through `ar-heredity`, which is why `PdslFeatures` compiles today. Nothing needs Metal or other
hardware specific to one node, so this plan has no workstream settings file.

## Open questions

1. **The task description is out of date.** It gives the old `engine/ml/.../ml/dsl` path and an
   interface set (`AttentionFeatures`, `RotationFeatures`) that `ar-graph` cannot implement. The
   plan uses `LayerRoutingFeatures` and `TemporalFeatures`, the set `PdslFeatures` aggregates
   today. The tracker record should be corrected to match. The tracker tools were not available
   in this planning session, so the record has not been updated.
2. **Keep `TemporalFeatures`?** Neither class calls any `TemporalFeatures` method. The task asks
   for at least the set `PdslFeatures` has today, so the plan keeps it. The approver may prefer
   to implement only `LayerRoutingFeatures`, which removes the unused capability and the
   `ar-time` import from the language core. That would be a one-line change in the declaration.
3. **How the interpreter gets its features.** The plan routes the interpreter's few feature calls
   through its `builtins` instance so that `PdslInterpreter`'s public API stays unchanged
   (step 3). If the approver would rather `PdslInterpreter` implement `LayerRoutingFeatures`
   directly, the change is just as small, but it exposes the whole feature surface on a public
   class.

   The plan's choice does not fully meet its own Motivation ("a features mixin is meant to be
   implemented by the type that uses it, not reached through a holder"): the interpreter would
   still call feature defaults on another object, now an instance field rather than a static
   one, and the post-session `ObjectOrientedDesignRule` review may flag it. A third option the
   approver may prefer: move each interpreter operation that needs features into a named
   package-private method on `PdslBuiltins` (for example the per-channel subscript slicing in
   `evaluateExpression`, and the `accumBlocks`/`concatBlocks` wrapping), so the interpreter
   calls library operations rather than raw feature defaults. That is a somewhat larger diff
   (the ten `FEATURES.` references in `PdslInterpreter` outside `normalizeToProducer`) but leaves no feature call outside a type that
   implements the features.

   **Resolved by the design review.** The interpreter now asks the objects that own each
   operation and calls no feature defaults at all. `accum_blocks` and `concat_blocks` call the new
   `SequentialBlock.accum(List<Block>)` and `SequentialBlock.concat(List<Block>)` on the block
   being built. Per-channel slicing uses the `CollectionProducer`'s own `getShape`, `reshape` and
   `subset`. Shapes are built with `new TraversalPolicy(...)`. The `builtins` instance serves
   only `call` and `normalizeToProducer`.
4. **Existing test methods are edited.** Step 5 changes call sites inside three existing
   `@Test` methods of `Conv1dSnakePrimitivesTest` and one helper of
   `PdslLanguageReferenceExamplesTest`. This is allowed under the test-integrity rules (no
   weakening), but if the implementation job is submitted with test-file protection
   (`protectTestFiles`), `FileStager` asks `TestMethodProtection` about each base-branch test
   file. That check compares only `@Test` methods, so the `PdslLanguageReferenceExamplesTest`
   helper edit is allowed. `Conv1dSnakePrimitivesTest` changes three `@Test` bodies, so that whole
   file is left unstaged. The commit would then delete the static `call` while the file still
   calls `PdslBuiltins.call(...)`, and `engine/ml` test compilation would break. The
   implementation job must be submitted without that protection.
5. **`docs/plans/PLAN-20261023-pdsl-language-reference.md` still names `PdslFeatures`.** That
   plan is `completed`, so it is a historical record and is left as it is; it will be removed by
   the plan sweep. No durable document outside `docs/plans/` names `PdslFeatures`, so the only
   durable mention to update is the `package-info.java` paragraph in step 6.
