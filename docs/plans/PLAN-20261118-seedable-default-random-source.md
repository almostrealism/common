---
status: completed
created: 2026-11-18
closed: 2026-11-19
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

### 2a. The default source is a dedicated class next to `RandomProducer`

The default source is a genuinely new concept — one process-wide holder of mutable state — so it
is its own class, `DefaultRandomSource`, in `compute/algebra`,
`org.almostrealism.collect.computations`, next to `RandomProducer`/`Random`/`CounterRandom`. (A
`RandomProducer` is an interface and cannot hold a mutable field, which is the mechanical reason
the state does not live on it.) Its members, all `static`:

```java
/** Seeds the default source; every later rand/randn without a source draws from it. */
static void seed(long seed);

/** Returns the default source to unseeded: each generator gets its own fresh source. */
static void clear();

/** The seeded default source, or null when unseeded. */
static java.util.Random current();

/** The active seed, or empty when unseeded. */
static OptionalLong getSeed();
```

The state is a `volatile java.util.Random` (`null` means unseeded) with a parallel `volatile
Long` seed. It is initialized from the `AR_RANDOM_SEED` setting through `SystemUtils.getProperty`,
which reads either a system property or an environment variable, in the same way as `AR_RANDOM`.
When the setting is absent, the source starts as `null`.

`RandomProducer.create(shape, normal, source)` substitutes `DefaultRandomSource.current()` when
`source` is `null`, before it dispatches to an `Implementation`. **When the default is unseeded,
`current()` returns `null`.** The code path is then exactly the one used today: one fresh
`java.util.Random` per generator. This is how "production is unchanged unless configured" is
guaranteed by construction, not just by testing.

Because the substitution happens in `create`, every caller that reaches it is covered with no
further edits: all `rand`/`randn` overloads, `LayerFeatures` weight initialization, and
`ProjectedGene`'s use of `randn`.

**Direct seedless construction is also routed.** `Random(shape, normal, (Long) null)` — reached by
`new Random(shape)` / `new Random(shape, normal)` — resolves the default source too, so a direct
construction of `computations.Random` without a seed behaves the same as going through `create`.

**Thread safety.** The shared source is one `java.util.Random`, which is thread-safe. If
generators are constructed on several threads at once, though, the order of their draws is
not deterministic. The javadoc of `DefaultRandomSource` states this. Graph construction in tests
is single-threaded.

### 2b. `TestSuiteBase` resets the source for every test

`TestSuiteBase` (in `engine/utils`) seeds the default source to the test seed in an `@Before`
method, `resetRandomSeed()`, and logs the seed.

- JUnit 4 runs a superclass's `@Before` before a subclass's, so a subclass that seeds explicitly
  in its own `@Before` still wins. `@Rule`s and the test body also run after `@Before`, so they
  see the seeded source.
- A subclass field initializer runs *before* any `@Before`, so a field such as
  `private final RandomProducer noise = randn(shape(64));` is **not** covered by the reset and
  should take an explicit source. The `TestSuiteBase` javadoc says so. (`@BeforeClass` and static
  initializers run before any instance exists and are likewise uncovered.)

An `@After` method, `clearDefaultRandomSource()`, calls `DefaultRandomSource.clear()`. This keeps
the default from staying seeded for code that runs after the test in the same fork and is not a
`TestSuiteBase` test. (It is named for the source rather than the seed because
`ParameterFunctionTest` already contributes an `@After clearRandomSeed()` that resets a different,
unrelated global source.)

**The seed.**
- The default is a fixed constant on `TestSuiteBase`, `DEFAULT_TEST_SEED`.
- The `AR_TEST_SEED` setting overrides it for the whole run: a number pins that seed, and
  `random` chooses a fresh seed for every test (logged each time) to hunt order- or
  seed-dependent flakes.
- A subclass can override `protected long testSeed()` to pin its own seed. This is for the rare
  class whose assertions were characterized against a particular draw; such a class should
  normally pass an explicit `Random` instead.
- The seed is logged as `randomSeed=<seed>` through `ConsoleFeatures.log` (no prefix, greppable
  key): once per test class for the stable default, and for every test under `AR_TEST_SEED=random`.

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

1. **`DefaultRandomSource`** (new class in `compute/algebra`): `seed(long)`, `clear()`,
   `current()`, `getSeed()` and the `AR_RANDOM_SEED` initialization.
2. **`RandomProducer`**: make `create(...)` substitute `DefaultRandomSource.current()` for a
   `null` source, and route `Random(shape, normal, (Long) null)` through the default source.
   Update the javadoc of `RandomProducer` and `Random` where they describe `null` as "unseeded".
3. **`CollectionCreationFeatures`**: no code change. Update the `rand`/`randn` javadoc to say the
   `null` source draws from the default source (unseeded unless configured).
4. **`TestSuiteBase`**: add the `@Before` reset `resetRandomSeed()`, the seed resolution
   (`AR_TEST_SEED` — `random` or a number — then `testSeed()`, then `DEFAULT_TEST_SEED`), the
   `randomSeed=` log line, and the `@After` `clearDefaultRandomSource()`. Document all of it in
   the class javadoc next to the existing `AR_TEST_DEPTH` and `AR_TEST_PROFILE` entries.
5. **Tests** — `DefaultRandomSourceTest` in `engine/utils/src/test/java`, next to the existing
   `CounterRandomTests` in `org.almostrealism.collect.computations.test` (`compute/algebra` has no
   test tree). These are new tests; no existing test is modified.
   - After `seed(s)`, two `randnFill()` results are identical; a different seed makes them differ,
     for both the host (`randnFill`) and counter (`Implementation.COUNTER.create(shape, normal,
     DefaultRandomSource.current())`) implementations.
   - After `clear()`, `current()` is `null` and two fills differ.
   - Calling `resetRandomSeed()` twice yields the same first `randn` draw.
   - An explicit source still overrides the default.
6. **Documentation**:
   - `docs/internals/collection-producer-operations.md` (the `rand`/`randn` and `AR_RANDOM`
     section): describe the default source and `AR_RANDOM_SEED`.
   - `engine/utils/README.md` (the TestSuiteBase section): describe the per-test seed,
     `AR_TEST_SEED`, `testSeed()` and how to replay a failing seed.

## 4. Risks

- **A fixed seed can turn an intermittent failure into a permanent one.** A test that passes
  for most draws but fails for some may fail on every run under the default seed. That is
  the point of the change: the failure becomes reproducible. **It must be fixed in the test's
  numerics or in production code, never by choosing a seed that happens to pass.** Changing
  `DEFAULT_TEST_SEED`, or adding a `testSeed()` override, to make a red test
  green is the "skip-gating" pattern described in `CLAUDE.md`, in another form. Each such
  failure gets its own tracker task.
- **The reverse can happen too.** A flaky test can become always green under the default
  seed, which hides a real fragility. A CI lane, or a periodic QA round, that runs with
  `AR_TEST_SEED=random` and logs it would restore coverage of the distribution.
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
`GrainSet` draw from `DefaultRandomSource.current()` when it is seeded, and keep the
explicit `setRandomSeed` as an override. `studio/music/pom.xml` declares `ar-audio`, and
`engine/audio/pom.xml` declares `ar-utils`, which reaches `ar-algebra`. So `studio/music`
can reach the source transitively. Whether a non-collection consumer should depend
on `DefaultRandomSource`, or whether the default source should move further down (for example
next to `SystemUtils` in `base/io`), is the placement question below. It has to be settled
before phase 2.

## Open questions for the owner

1. Should the seed be the same for every test (proposed), or derived from
   `defaultSeed ^ testName.hashCode()` so that different tests see different streams while
   each stays reproducible?
2. Should the default source live in `compute/algebra` (as built, `DefaultRandomSource`), or
   lower (in `base/io`, next to `SystemUtils`) so that the `Math.random()` sites in modules that
   do not use collections can share it in phase 2?
3. Should a CI lane or QA round run with `AR_TEST_SEED=random`, logged, to keep
   exploring the distribution? This needs a `ci/...` branch.
4. Should `AR_RANDOM_SEED` in production also log its seed once at startup?
