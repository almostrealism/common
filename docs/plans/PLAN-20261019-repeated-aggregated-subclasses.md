# Define the remaining repeated and aggregated computations by subclass

- **Tracker task:** `c4c9a8da-eed2-4842-9012-414ddf084527` — "Define the
  remaining repeated and aggregated computations by subclass instead of caller
  lambdas"
- **Release:** Common 0.76
- **Estimated complexity:** Medium

## Motivation

A computation's behavior must be set by its class, not by lambdas a caller
passes to a shared base. `ProducerComputationBase.signature()` is
`md5(name + requirements + input signatures)`, so two caller-configured
computations that share a name and inputs but carry different lambdas get the
same signature and can collide in the signature-keyed instruction cache —
executing one compiled kernel in place of another. Behavior the class and name
do not determine also weakens the guarantees every computation is expected to
provide.

`feature/delta-consolidation` (commit `c7f3d51b7`) established the remedy for
one family: `TraversableRepeatedProducerComputation` became abstract, its
`Step` constructor argument was dropped, the `indexOfMax` lambda became the
named `IndexOfMaxComputation`, and `signature()` now appends
`getClass().getName()` so two repeated computations of the same name, shape and
inputs but different classes — and therefore different loop bodies — produce
different signatures. `PrefixAccumulationComputation` already follows the same
shape (abstract `combine`/`term`/`identity`, signature extended with its `pad`
flag). This task finishes the job for the three lambda-configured classes that
were left out because they have more subclasses and callers and warrant their
own review and test pass.

## Scope

Three base classes in `compute/algebra`
(`org.almostrealism.collect.computations`) still accept behavior as caller
lambdas:

1. **`RepeatedProducerComputation`** — `initial`, `condition`, `expression`
   lambdas; `generate()` rebuilds a raw instance.
2. **`ConstantRepeatedProducerComputation`** — `initial`, `expression`
   lambdas (condition is fixed to `index < count`); `generate()`
   rebuilds a raw instance; its class javadoc constructs it directly.
3. **`AggregatedProducerComputation`** — `initial`, `expression` lambdas plus a
   `replaceLoop` flag; constructs itself in `generate()`, the
   transitive-delta branch of `deltaMatrix()` and `withReplaceLoop()`;
   works around the signature problem with `expressionSignature()`.

The change makes each base abstract with protected constructors, moves
per-iteration behavior to protected methods (abstract where a subclass must
supply it) defined by named subclasses, has each subclass's `generate()`
rebuild its own type, and removes `expressionSignature()` once no aggregation
is configured by caller lambdas. `AggregationSignatureTests` is updated to
prove the same non-collision property with two concrete subclasses rather than
two raw lambda-configured aggregations — it is tightened, never weakened.

### Explicitly in scope

- Making the three classes abstract and reworking their self-construction sites.
- The existing named subclasses: `RepeatedProducerComputationAdapter`,
  `ConstantRepeatedDeltaComputation`, `CollectionSumComputation`,
  `CollectionMaxComputation`, `LoopedWeightedSumComputation` (and the already-
  abstract `TraversableRepeatedProducerComputation` /
  `PrefixAccumulationComputation`, which only need to keep compiling and keep
  their signature contribution).
- Updating the one production caller that re-flags an aggregation:
  `engine/audio` `BatchedPatternRenderer.sumNoteAxis` (`withReplaceLoop(false)`).
- Updating `AggregationSignatureTests`, and the class-javadoc examples that
  construct the now-abstract bases directly.
- Updating `base/hardware/docs/INSTRUCTION_CACHING.md`, which is durable
  documentation that describes the current aggregation signature: it states
  that `AggregatedProducerComputation` appends "a structural rendering of its
  initial and combining functions" and names
  `AggregatedProducerComputation.expressionSignature()` as a source of null
  signatures. Removing `expressionSignature()` and the `,functions:` segment
  makes both passages stale, so they must be revised (the class plus its
  `{count,replaceLoop}` state becomes what disambiguates). The implementer
  should also re-check the claim there that the signature "covers the raw
  aggregations its `delta()` constructs," since the delta path no longer builds
  a raw aggregation.

### Explicitly out of scope

- The latent signature gaps in `RepeatedProducerComputationAdapter` (it embeds a
  `TraversableExpression` not captured in its signature) and
  `ConstantRepeatedDeltaComputation` (its `expression` closes over the
  differentiated computation). These are pre-existing, orthogonal to the
  lambda-vs-subclass change, and should be filed as follow-ups rather than
  widening this task.
- Any change to the delta/gather/reduction *numerics* beyond what is needed to
  rebuild the same graph from named classes.
- Creating any new Maven module (not required; all classes stay in
  `compute/algebra`, tests in `engine/utils`).

## Approach

The four computations already converted (`TraversableRepeatedProducerComputation`,
`PrefixAccumulationComputation`, `IndexOfMaxComputation`, and the sum/max/
weighted-sum subclasses) are the template. The work is to apply the same shape
one level up the hierarchy.

### 1. `RepeatedProducerComputation` → abstract

- Make the class `abstract` and its constructors `protected`.
- Keep `initial`/`condition`/`expression` as the mechanism the base `getScope()`
  uses, but they are now supplied only by subclasses through the protected
  constructors (the existing `setInitial`/`setCondition` protected hooks remain
  for subclasses such as `PrefixAccumulationComputation` /
  `RepeatedProducerComputationAdapter` that assign after `super(...)`).
- Make `generate()` `abstract` — every concrete subclass already needs
  its own, and the base can no longer build a raw instance.
- Move the class-identity signature component here: override `signature()` to
  append `{getClass().getName()}` (returning `null` when `super.signature()` is
  `null`), and **remove** the now-duplicate override in
  `TraversableRepeatedProducerComputation` so the class name is appended exactly
  once. `getClass().getName()` is dynamic, so the concrete subclasses still get
  their own names. This is the most general correct home for "the class
  determines the behavior, so the class is part of the signature," and it
  extends the guarantee to `RepeatedProducerComputationAdapter` as well.

**`RepeatedProducerComputationAdapter`** (the one concrete direct subclass,
built by `CollectionProducerComputationAdapter.toRepeated()` and
`CollectionProducerComputationBase.toRepeated()`): it already *is* a named
class, so it stays concrete. It must gain its own `generate()` (it currently
inherits the base one). Its `initial`/`condition`/`expression` are fixed by the
class (init to `0.0`; condition `index < outputLength`; expression
`storedExpression.getValueAt(index)`), so they can stay as the class's own
construction-time assignment — no caller supplies them. Its generate rebuilds a
`RepeatedProducerComputationAdapter` over the same stored expression and the new
children.

### 2. `ConstantRepeatedProducerComputation` → abstract

- Make the class `abstract` and its constructors `protected`.
- The continuation condition (`index < count`) is already fixed by this class,
  not by a caller — it stays as the class's own behavior.
- `initial`/`expression` remain protected constructor parameters, now reachable
  only by subclasses (`ConstantRepeatedDeltaComputation`,
  `TraversableRepeatedProducerComputation` and everything under it).
- Make `generate()` `abstract`; both existing subtrees already override
  it (`ConstantRepeatedDeltaComputation`, and the abstract
  `TraversableRepeatedProducerComputation` already declares it abstract).
- Convert the class-javadoc "indexOfMax"/"accumulate"/"optimizedProcess"
  examples from direct construction to the corresponding named subclasses (or
  remove them), since the class can no longer be instantiated directly.
- `deltaMatrix()` already returns a `ConstantRepeatedDeltaComputation` — no
  change needed there.

### 3. `AggregatedProducerComputation` → abstract (the substantive part)

- Make the class `abstract` with protected constructors. Keep `expression`,
  `initial` and `replaceLoop` as protected state assigned by subclass
  constructors (mirroring how `TraversableRepeatedProducerComputation` keeps
  `initial`).
- Make `generate()` `abstract`; all three concrete subclasses already
  override it.
- Rework the two remaining self-construction sites so they build **named**
  types:
  - **`withReplaceLoop(boolean)`:** make it `abstract` (or a thin
    dispatcher to a protected `recreate(boolean replaceLoop)`), implemented by
    each concrete subclass to rebuild *its own* type with the new flag.
    `LoopedWeightedSumComputation` already overrides it (throws when the flag
    differs). `CollectionMaxComputation` is constructed with `replaceLoop=false`
    and has no reason to toggle, so it can throw if asked to change (matching the
    weighted-sum precedent). `CollectionSumComputation` **must** support
    `withReplaceLoop(false)` because `BatchedPatternRenderer.sumNoteAxis` relies
    on it to disable the always-failing gather probe on batched pattern windows;
    give `CollectionSumComputation` a protected constructor that carries the
    `replaceLoop` flag and an override that rebuilds a `CollectionSumComputation`
    with the requested flag. This also fixes an existing latent bug: today
    `withReplaceLoop(false)` on a `CollectionSumComputation` returns a *raw*
    `AggregatedProducerComputation`, silently dropping the sum's type and its
    `deltaDependent` override.
  - **Transitive-delta branch of `deltaMatrix()`:** this builds an
    aggregation that *sums* the enumerated Jacobian groups. Every subclass that
    reaches this branch is additive — `CollectionSumComputation` and
    `LoopedWeightedSumComputation` both combine with `add`; `CollectionMaxComputation`
    overrides `deltaDependent` and routes through `select()`, so it never reaches
    here. `new CollectionSumComputation(delta)` reproduces the raw aggregation's
    output shape (`shape(delta).replace(shape(1))`) and iteration count (the
    delta's per-group size equals `count`), so the branch can construct a named
    `CollectionSumComputation` directly. This removes the last use of
    `this.expression` for constructing a new aggregation and makes the delta of a
    reduction an explicit, named sum. (If validation shows a subclass needs a
    non-additive delta reduction, fall back to a protected
    `createDeltaAggregation(...)` template the subclass implements — but the
    current subclasses do not need it.)
- **Remove `expressionSignature()`** and drop the `,functions:` segment from
  `signature()`. With no caller-configured aggregations left, the concrete class
  (appended by the base `signature()` from step 1) plus the parameterized state
  each subclass already adds — `{count,replaceLoop}` here,
  `{innerCount,input,weight,indexers}` on `LoopedWeightedSumComputation`,
  `{pad=...}` on `PrefixAccumulationComputation` — fully determines the generated
  kernel. The `init()` refresh calls in `AggregatedProducerComputation` and
  `LoopedWeightedSumComputation` (which re-capture the signature after subclass
  fields are set) remain as-is.

### 4. Tests

- **`AggregationSignatureTests.aggregationFunctionsDisambiguate`** currently
  constructs two raw `AggregatedProducerComputation`s with different lambdas
  (sum vs product) to prove their signatures differ. Rewrite it to compare two
  concrete subclasses whose loop bodies genuinely differ — e.g.
  `CollectionSumComputation` vs `CollectionMaxComputation` over the same data —
  asserting distinct signatures and correct, distinct evaluated results. This
  keeps the property the test protects (structurally different aggregations never
  share a cached kernel) while expressing it through the subclass model. Do not
  reduce dimensions, loosen assertions or delete the result checks.
- The other `AggregationSignatureTests` methods (`aggregationDeltaSignature`,
  `weightedSumSignatures`, `indexOfMaxSignatureIdentifiesClass`,
  `projectionGraphSignatures`) must continue to pass unchanged.

## Success criteria

A reviewer can confirm the task is done when:

1. `RepeatedProducerComputation`, `ConstantRepeatedProducerComputation` and
   `AggregatedProducerComputation` are all `abstract` with protected
   constructors, and there is no `new RepeatedProducerComputation(...)`,
   `new ConstantRepeatedProducerComputation(...)` or
   `new AggregatedProducerComputation(...)` anywhere in production or test code
   (grep clean, except within the abstract classes themselves if a protected
   factory is used — ideally none).
2. Every concrete subclass's `generate()` returns its own type, and
   `withReplaceLoop` returns the concrete type (verified for
   `CollectionSumComputation`).
3. `expressionSignature()` is gone and `AggregationSignatureTests` proves
   non-collision via two concrete subclasses, still asserting correct distinct
   results.
4. `mvn clean install -DskipTests` succeeds, and `ar-build-validator`
   (`checkstyle`, `code_policy`, `test_timeouts`, `duplicate_code`) is clean.
5. The gating tests pass (run in the `engine/utils` module via the MCP test
   runner, in bounded selections of ≤5 classes):
   - `AggregationSignatureTests`
   - `AggregatedComputationTests`
   - `CollectionComputationTests` (covers `indexOfMax`, `sum`, `max`)
   - `RepeatedTraversableComputationTests`
   - `RepeatedDeltaComputationTests` (repeated + delta paths; note
     `convDeltaMedium` is a pre-existing environment-sensitive marginal test —
     see memories on `qa/consolidate-20260919-192550` — and is not a regression
     signal for this change)
   - `MinMaxTests`, `ScalarBankSumTest`
   - `WeightedSumTests`, `WeightedSumLoopTests`, `LoopedWeightedSumCorrectnessTest`
   - `CumulativeProductTests`
6. `BatchedPatternRenderer` still compiles and its `withReplaceLoop(false)` path
   produces a `CollectionSumComputation` reduction (confirmed by the audio
   batched-render tests that exercise `sumNoteAxis`).

## Dependencies and open questions

- **Dependency:** none open. The blocking work
  (`TraversableRepeatedProducerComputation` / `IndexOfMaxComputation`) already
  landed on `feature/delta-consolidation` (PR #667); this builds directly on it.
- **Open question — delta reduction type:** the plan recommends the
  transitive-delta branch build a `CollectionSumComputation`. This is correct for
  every aggregation that currently reaches it, but it is a construction change on
  a gradient path, so `RepeatedDeltaComputationTests` and `ReducedDeltaTest` /
  `ReducedDeltaRulesTest` are the gate. If any delta regresses, fall back to a
  protected `createDeltaAggregation` template method the subclass supplies. The
  approving reviewer should confirm the additive-reduction assumption is
  acceptable.
- **Open question — class-name signature placement:** the plan moves the
  `{getClass().getName()}` signature component from
  `TraversableRepeatedProducerComputation` up to `RepeatedProducerComputation`.
  This is a behavior improvement (adapters gain class identity) but changes the
  signature string of every repeated computation; confirm no cache/signature
  snapshot test pins an exact string (the current tests use `contains`/
  inequality, which are robust). If a double-append is a concern, the
  conservative alternative is to leave the override on
  `TraversableRepeatedProducerComputation` and add a separate one to
  `RepeatedProducerComputationAdapter`.
- **Splitting:** the task is one coherent change but naturally sequences as
  (a) `RepeatedProducerComputation` + `ConstantRepeatedProducerComputation`
  abstract (mechanical, low risk), then (b) `AggregatedProducerComputation`
  abstract + delta/withReplaceLoop rework + `expressionSignature()` removal +
  test rewrite (the substantive part). If review prefers, (a) and (b) can be two
  reviews on this branch; they are not large enough to require separate tasks.

## Machine requirements

None. The classes are in `compute/algebra` and the gating tests run on the
native CPU backend; no Metal/macOS-only capability is needed, so no
`-workstream.yaml` settings file accompanies this plan.
