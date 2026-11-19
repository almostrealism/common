---
status: proposed
created: 2026-11-18
closed:
tracker: [9a68c5cb-c756-460c-9a2a-a8a41a0f2b52]
---

# One seedable default random source, reset before every test

Today, when `rand`/`randn` get no explicit source, every generator they return has its own
unseeded `java.util.Random`. A test that calls `rand(shape)` therefore sees different
inputs on every run, and a failure that depends on the draw cannot be reproduced. This
plan adds **one default random source**. `rand`/`randn` use it whenever the caller passes
no source. `TestSuiteBase` resets it to a fixed, logged and overridable seed before each
test. Production stays unseeded unless it is configured otherwise.

## 1. Where randomness comes from today

**`rand`/`randn`.** `CollectionCreationFeatures.rand(TraversalPolicy)` and
`randn(TraversalPolicy)` (and the `int...` and `mean, std` overloads) pass a `null` source
to `RandomProducer.create(shape, normal, source)`. That method picks an implementation:

- `Implementation.JAVA` (the `AR_RANDOM=java` default) turns a `null` source into
  `new Random(shape, normal)`. This becomes `new java.util.Random()` in the
  `Random(TraversalPolicy, boolean, Long)` constructor.
- `Implementation.COUNTER` (`AR_RANDOM=counter`, and every variable-count shape) passes the
  `null` to `CounterRandom(TraversalPolicy, boolean, java.util.Random)`. That constructor
  draws its seed from `new java.util.Random()`.

So both implementations already accept a `java.util.Random` and are reproducible when it is
seeded. The only thing missing is a seeded source for the `null` case.

**Draw order is already deterministic.** `Random` draws all of its values in its
constructor, not when it is first evaluated. `CounterRandom` draws its seed in its
constructor. Generators that share one source therefore consume it in the order they are
*constructed*, whatever order the compiled kernels later evaluate them in. This was fixed
earlier on purpose: see the constructor javadoc of `Random`. A shared default source keeps
that property: single-threaded graph construction gives the same values every run.

**Weight initialization.** `LayerFeatures.dense(...)` and `LayerFeatures.randnInit(...)`
call `randn(shape, getInitializationRandom())`. `getInitializationRandom()` returns `null`
by default, so layer weights go through the same `null` path. They need no change of their
own. Tests that override `getInitializationRandom()` (for example
`SyntheticNormTrainingTest`) keep their explicit source.

**Other process-global sources (out of the core scope, see §5).**
- `ParameterFunction.randomSource` in `studio/music` is a static `Random`, with
  `setRandomSeed(long)` and `clearRandomSeed()`. `AudioSceneTestBase` and
  `ParameterFunctionTest` manage it by hand.
- `Math.random()` is called in `ParameterSet.random()` and `GrainSet` (`studio/music`),
  `AudioSceneOptimizer` (`studio/compose`), and in many places in `domain/space`,
  `domain/physics`, `domain/chemistry` and `engine/render`.
- `new Random()` is called in `ProbabilisticFactory` (`domain/heredity`) and
  `DistributionFeatures` (`compute/stats`).

**`TestSuiteBase` resets nothing random.** Its only per-test hook is the `@After`
`clearProfile()`.

## 2. Design

### 2a. The default source lives with `RandomProducer`

`RandomProducer` (in `compute/algebra`, `org.almostrealism.collect.computations`) is the type
that turns a `null` source into an unseeded one, so the default source belongs on it. No new
helper class is needed. Proposed members:

```java
/** Returns the source rand/randn draw from when given none; null when unseeded. */
static java.util.Random defaultSource();

/** Seeds the default source; every later rand/randn without a source draws from it. */
static void seedDefaultSource(long seed);

/** Returns the default source to unseeded: each generator gets its own fresh source. */
static void clearDefaultSource();
```

An interface cannot hold mutable state in a plain field, so the state is a `static final
AtomicReference<java.util.Random>` (`null` means unseeded). It is initialized from the
`AR_RANDOM_SEED` setting through `SystemUtils.getProperty`, which reads either a system
property or an environment variable, in the same way as `AR_RANDOM`. When the setting is
absent, the reference starts as `null`.

`RandomProducer.create(shape, normal, source)` substitutes `defaultSource()` when `source` is
`null`, before it dispatches to an `Implementation`. **When the default is unseeded,
`defaultSource()` returns `null`.** The code path is then exactly the one used today: one fresh
`java.util.Random` per generator. This is how "production is unchanged unless configured" is
guaranteed by construction, not just by testing.

Because the substitution happens in `create`, every caller that reaches it is covered with no
further edits: all `rand`/`randn` overloads, `LayerFeatures` weight initialization, and
`ProjectedGene`'s use of `randn`.

**Direct constructors are not routed.** `new Random(shape, normal)` and
`new CounterRandom(shape, normal, (java.util.Random) null)` keep their current meaning. The
javadoc of `Random` already tells callers to use `rand`/`randn` rather than construct it.
The plan lists the direct `null`-source construction sites in `src/main` and moves them to
`rand`/`randn` if any exist, instead of changing what the constructors mean.

**Thread safety.** The shared source is one `java.util.Random`, which is thread-safe. If
generators are constructed on several threads at once, though, the order of their draws is
not deterministic. The javadoc of `seedDefaultSource` states this, just as the
`ParameterFunction.setRandomSeed` javadoc does ("provided the construction is
single-threaded"). Graph construction in tests is single-threaded.

### 2b. `TestSuiteBase` resets the source for every test

`TestSuiteBase` (in `engine/utils`) resets the default source to the test seed **in its
constructor** and logs the seed. It does not do this in an `@Before` method.

- JUnit 4 builds a new test-class instance for every test method. The superclass
  constructor runs *before* subclass field initializers. A test class with a field such as
  `private final RandomProducer noise = randn(shape(64));` therefore draws after the reset.
  An `@Before` reset would come too late for that field, and the field would draw from
  whatever state the previous test left behind. The order of test methods would then change
  the values.
- `@Before` methods, `@Rule`s and the test body all run after the constructor, so all of
  them see the seeded source.
- `@BeforeClass` and static initializers run before any instance exists, so the reset does
  not cover them. The `TestSuiteBase` javadoc says so. Such draws should take an explicit
  source.

An `@After` method calls `RandomProducer.clearDefaultSource()`. This keeps the default from
staying seeded for code that runs after the test in the same fork and is not a
`TestSuiteBase` test. It is the hidden cross-class coupling that `ParameterFunction` has today.

**The seed.**
- The default is a fixed constant on `TestSuiteBase`, such as `DEFAULT_TEST_RANDOM_SEED`.
- The `AR_TEST_RANDOM_SEED` setting overrides it for the whole run. This is how CI or a
  developer tries a different seed, or replays the seed of a failure.
- A subclass can override a `protected long getTestRandomSeed()` method to pin its own seed.
  This is for the rare class whose assertions were characterized against a particular draw.
  Such a class should normally pass an explicit `Random` instead.
- Every test logs one line, `randomSeed=<seed>`, through `ConsoleFeatures.log`. Following
  the logging rules, the line has no prefix, and its key can be found with grep. It is logged
  even when the seed is the default, so a CI log always records the seed in use.

**The same seed for every test.** Every test starts from the same seed, not from a seed
derived from its name. This makes a test's draws independent of which tests ran before it.
That independence is the property the tracker task asks for. Deriving the seed from the test
name is listed as an open question.

### 2c. Interaction with `AR_RANDOM`

The default source is independent of the implementation that `AR_RANDOM` selects.
- With `java`, the seeded source supplies every value.
- With `counter`, it supplies every `CounterRandom` seed. That generator's in-kernel stream
  is then reproducible as well.

Under `AR_RANDOM=counter`, a given seed gives different values than under `java`. That is
expected, and the documentation states it. Reproducibility is promised within one
implementation, not across implementations.

## 3. Work items

1. **`RandomProducer`**: add `defaultSource()`, `seedDefaultSource(long)`,
   `clearDefaultSource()` and the `AR_RANDOM_SEED` initialization. Make `create(...)`
   substitute the default for a `null` source. Update the javadoc of `RandomProducer`,
   `Random` and `CounterRandom` where they describe `null` as "unseeded".
2. **`CollectionCreationFeatures`**: no code change. Update the `rand`/`randn` javadoc to
   say "draws from the default source (see `RandomProducer.defaultSource()`)" instead of
   "unseeded".
3. **`LayerFeatures.getInitializationRandom()`**: no code change. Update the javadoc: `null`
   now means "the default source", which is unseeded unless configured.
4. **`TestSuiteBase`**: add the constructor reset, the seed resolution
   (`AR_TEST_RANDOM_SEED`, then `getTestRandomSeed()`, then the constant), the
   `randomSeed=` log line, and the `@After` clear. Document all of it in the class javadoc
   next to the existing `AR_TEST_DEPTH` and `AR_TEST_PROFILE` entries.
5. **Tests**, in `engine/utils/src/test/java`. `compute/algebra` has no test tree, and the
   existing `CounterRandomTests` lives in `org.almostrealism.collect.computations.test` in
   `engine/utils`. These are new tests; no existing test is modified.
   - After `seedDefaultSource(s)`, two `rand(shape)` sequences produce identical values,
     under both `Implementation.JAVA` and `Implementation.COUNTER`. Construct through
     `Implementation.X.create(shape, normal, RandomProducer.defaultSource())` so that the
     test does not depend on the run's `AR_RANDOM` setting.
   - After `clearDefaultSource()`, `defaultSource()` is `null` and two generators differ.
   - Two generators from one seeded source differ from each other, so sharing the source
     does not make sibling generators identical.
   - A `TestSuiteBase` subclass whose field initializer calls `randn(...)` gets the same
     values in two test methods. This guards the constructor-reset decision in §2b.
6. **Documentation**:
   - `docs/internals/collection-producer-operations.md` (the `rand`/`randn` and `AR_RANDOM`
     section): describe the default source and `AR_RANDOM_SEED`.
   - `engine/utils/README.md` (the TestSuiteBase section) and
     `docs/internals/test-examples.md`: describe the per-test seed, `AR_TEST_RANDOM_SEED`,
     `getTestRandomSeed()` and how to replay a failing seed.

## 4. Risks

- **A fixed seed can turn an intermittent failure into a permanent one.** A test that passes
  for most draws but fails for some may fail on every run under the default seed. That is
  the point of the change: the failure becomes reproducible. **It must be fixed in the test's
  numerics or in production code, never by choosing a seed that happens to pass.** Changing
  `DEFAULT_TEST_RANDOM_SEED`, or adding a `getTestRandomSeed()` override, to make a red test
  green is the "skip-gating" pattern described in `CLAUDE.md`, in another form. Each such
  failure gets its own tracker task.
- **The reverse can happen too.** A flaky test can become always green under the default
  seed, which hides a real fragility. A CI lane, or a periodic QA round, that runs with a
  random `AR_TEST_RANDOM_SEED` and logs it would restore coverage of the distribution.
  Adding one is a CI change that needs a `ci/...` branch, so it is listed as an open
  question and not done here.
- **Explicit seeds stay explicit.** Tests that pass their own `Random`, such as
  `DifferentialAttentionTest` and `SyntheticNormTrainingTest`, are not affected.
- **Tests that assert two draws differ** still pass, because a shared source keeps
  advancing between generators.

## 5. Out of scope, with a proposed follow-up

The tracker task also names "the studio/music call sites" (`ParameterFunction`,
`ParameterSet.random()`, `GrainSet`) and other `Math.random()` uses. This plan keeps them as
a second phase, for two reasons.
- `ParameterFunction` already has a seeding mechanism. `AudioSceneTestBase` drives it with
  per-attempt arrangement seeds, and that must keep working.
- The `Math.random()` sites are spread across many domain modules.

The follow-up would make `ParameterFunction.random(double)`, `ParameterSet.random()` and
`GrainSet` draw from `RandomProducer.defaultSource()` when it is seeded, and keep the
explicit `setRandomSeed` as an override. `studio/music/pom.xml` declares `ar-audio`, and
`engine/audio/pom.xml` declares `ar-utils`, which reaches `ar-algebra`. So `studio/music`
can reach the source transitively. Whether a non-collection consumer should depend
on `RandomProducer`, or whether the default source should move further down (for example
next to `SystemUtils` in `base/io`), is the placement question below. It has to be settled
before phase 2.

## Open questions for the owner

1. Should the seed be the same for every test (proposed), or derived from
   `defaultSeed ^ testName.hashCode()` so that different tests see different streams while
   each stays reproducible?
2. Should the default source live on `RandomProducer` (proposed, `compute/algebra`), or lower
   (in `base/io`, next to `SystemUtils`) so that the `Math.random()` sites in modules that do
   not use collections can share it in phase 2?
3. Should a CI lane or QA round run with a random, logged `AR_TEST_RANDOM_SEED` to keep
   exploring the distribution? This needs a `ci/...` branch.
4. Should `AR_RANDOM_SEED` in production also log its seed once at startup?
