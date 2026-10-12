# Concrete Dense and LoRA Projection Factories

**Tracker task:** `7881b11f-127d-42fb-842f-35afd654c97e`: "ProjectionFactory: static
dense()/lora() create throwaway new LayerFeatures(){} although the interface extends
LayerFeatures"
**Release:** the release this task is assigned to in tracker project "Common" (see the
tracker task; the version itself is not repeated here because it changes)
**Status:** Proposal, awaiting approval. Nothing below is implemented yet: neither class
exists, and `ProjectionFactory` and `LowRankAdapterSupport` are unchanged.

## Motivation

`domain/graph/src/main/java/org/almostrealism/layers/ProjectionFactory.java` is declared
`public interface ProjectionFactory extends LayerFeatures`, so every implementation already
*is* a `LayerFeatures`. Its two static factories cannot use that: `dense()` and
`lora(AdapterConfig, List<LoRALinear>)` return lambdas defined in a static
context, where there is no `this`. Each lambda therefore creates
`LayerFeatures lf = new LayerFeatures() {};` and calls `lf.dense(...)`. A new anonymous
Features object is built on **every** `create(...)` call just to reach a default method
that the factory itself already inherits.

Because the dense and LoRA strategies have no named type, a reader cannot see that they
are real `ProjectionFactory` implementations, and the LoRA strategy ("targeted → new
`LoRALinear` + register it, otherwise dense") is written out three times: in
`ProjectionFactory.lora(...)`, in the lambda returned by
`LowRankAdapterSupport.getProjectionFactory()`, and in the body of
`LowRankAdapterSupport.loraOrDense(TraversalPolicy, PackedCollection, PackedCollection,
TargetLayer)`. The three copies differ only in how a new adapter is registered (append to a
caller-supplied list, or call `addLoraLayer`).

## Scope

In scope:

1. Add a named class `DenseProjectionFactory implements ProjectionFactory` in
   `org.almostrealism.layers` (module `domain/graph`, artifact `ar-graph`). Its `create`
   calls its own inherited `dense(shape, weights, bias, false)`.
2. Add a named class `LoRAProjectionFactory implements ProjectionFactory` in the same
   package. It is built from an `AdapterConfig`, a `Consumer<LoRALinear>` that receives
   each adapter it creates, and a fallback `ProjectionFactory`. For targeted layers it
   creates a `LoRALinear`. For all other layers it delegates to the fallback, so the caller
   decides what an untargeted projection is (see Open Question 6 for why, and for the
   simpler alternative the approver may pick instead).
3. Rewrite `ProjectionFactory.dense()` and `ProjectionFactory.lora(config, loraLayers)` as
   convenience constructors that return instances of these classes. Remove both
   `new LayerFeatures() {}` expressions.
4. Make `LowRankAdapterSupport.getProjectionFactory()` return a `LoRAProjectionFactory`
   that registers through `this::addLoraLayer`, and make the four-argument
   `loraOrDense(...)` build its own `LoRAProjectionFactory` the same way and call `create`
   on it. The LoRA strategy then exists in exactly one place. This is the same "LoRA
   projection factory" the task names, so it is included rather than left behind as a
   second and third copy (see Open Questions). `loraOrDense` must **not** go through
   `getProjectionFactory()`; see "`LowRankAdapterSupport`" under Approach for why.
5. Update the documentation that shows these factories:
   - The `ProjectionFactory` class javadoc. Its "LoRA" example is a lambda that calls an
     unqualified `dense(...)`, which only compiles inside a `LayerFeatures` implementor;
     replace it with `ProjectionFactory.lora(config, loraLayers)` and name the two classes
     with `@see`.
   - `domain/graph/README.md` ("Using ProjectionFactory") and `engine/ml/README.md`
     ("ProjectionFactory"). Both examples call only `ProjectionFactory.dense()` and
     `ProjectionFactory.lora(config, loraLayers)`, which keep working unchanged, so these
     need at most a sentence naming `DenseProjectionFactory` / `LoRAProjectionFactory`, not
     a rewrite. Add the two classes to the `domain/graph/README.md` LoRA class table
     (which already lists `ProjectionFactory`).
   - `docs/modules/graph.html` and `docs/modules/ml.html`, the HTML module pages linked
     from `docs/index.html`. They are hand-maintained (no generator for `docs/modules/`
     exists under `scripts/`) and mirror the two READMEs: `graph.html` has a
     "Using ProjectionFactory" section with the same `dense()`/`lora(config, loraLayers)`
     example and a class list with a `ProjectionFactory` entry; `ml.html` has a
     "ProjectionFactory" section with the same example and lists
     `LowRankAdapterSupport.getProjectionFactory()`. As with the READMEs, the examples keep
     working; give each page the same one-sentence mention of the two classes, and add
     `DenseProjectionFactory` / `LoRAProjectionFactory` entries next to `ProjectionFactory`
     in the `graph.html` class list, so the pages do not drift from the READMEs.
   - `docs/internals/ml-inference-pipeline.md` mentions `ProjectionFactory` only as a
     parameter of `sequenceAttention` and needs no change.

Out of scope:

- Every `ProjectionFactory` parameter in `engine/ml` (`AttentionFeatures`,
  `SequenceAttentionFeatures`, `TransformerBlockFeatures`, `FeedForwardFeatures`,
  `DifferentialAttentionFeatures`, `T5GemmaEncoder`, `CausalLanguageModel`,
  `DiffusionTransformer`). These callers keep calling `ProjectionFactory.dense()` and
  `lora(...)`, and their signatures do not change.
- `LoRADiffusionTransformer.getProjectionFactory()`. It already delegates to
  `LowRankAdapterSupport.super.getProjectionFactory()` and picks up the new implementation
  automatically.
- Any change to `LoRALinear`, `AdapterConfig` or `LayerFeatures.dense(...)`.

## Approach

### `DenseProjectionFactory`

```java
public class DenseProjectionFactory implements ProjectionFactory {
	@Override
	public CellularLayer create(TraversalPolicy inputShape, PackedCollection weights,
								PackedCollection bias, AdapterConfig.TargetLayer targetLayer) {
		return dense(inputShape, weights, bias, false);
	}
}
```

The class is stateless. `ProjectionFactory.dense()` may return a new instance or a shared
constant (`DenseProjectionFactory.INSTANCE`). Either way satisfies the task. The default is
a new instance, which keeps today's semantics where every call returns a distinct factory.

### `LoRAProjectionFactory`

```java
public class LoRAProjectionFactory implements ProjectionFactory {
	private final AdapterConfig config;
	private final Consumer<LoRALinear> registry;
	private final ProjectionFactory fallback;

	public LoRAProjectionFactory(AdapterConfig config, Consumer<LoRALinear> registry,
								 ProjectionFactory fallback) { ... }

	@Override
	public CellularLayer create(TraversalPolicy inputShape, PackedCollection weights,
								PackedCollection bias, AdapterConfig.TargetLayer targetLayer) {
		if (config == null || !config.isTargeted(targetLayer)) {
			return fallback.create(inputShape, weights, bias, targetLayer);
		}

		LoRALinear layer = new LoRALinear(inputShape, weights, bias,
				config.getRank(), config.getAlpha());
		registry.accept(layer);
		return layer;
	}
}
```

- The fallback is a `ProjectionFactory` the caller supplies (composition), not an
  inherited `dense(...)`. This is what lets `LowRankAdapterSupport` keep routing untargeted
  projections through the implementor's own `dense(...)`, as it does today; see
  "`LowRankAdapterSupport`" below and Open Question 6. The dense path still has a single
  definition: the statics pass a `DenseProjectionFactory`.
- The registration hook is a `Consumer<LoRALinear>` and not a `List`. A `List` would fit
  only `ProjectionFactory.lora(...)`, whose callers pass a list. `LowRankAdapterSupport`
  registers through its abstract `addLoraLayer(LoRALinear)`, which a `Consumer` covers
  directly (`this::addLoraLayer`).
- The `registry` and `fallback` hooks are required: the constructor rejects a null
  `registry` or `fallback` with `Objects.requireNonNull`. `create(...)` calls
  `registry.accept(layer)` for every targeted projection and `fallback.create(...)` for
  every untargeted one, so neither has a sensible default when null. "Skip registration" is
  a caller intent, expressed by passing an explicit no-op (`layer -> { }`), not by passing
  null. The one caller that has a nullable registration target —
  `ProjectionFactory.lora(config, loraLayers)` with a null list — performs that substitution
  itself (see "`ProjectionFactory` statics"), so the null-list contract stays at the
  convenience method that owns it rather than being silently absorbed by the factory. The
  other caller, `loraProjectionFactory()`, always supplies a non-null `this::addLoraLayer`
  hook and a non-null fallback lambda.
- `config`, by contrast, is nullable: a null `AdapterConfig` means "never adapt", and
  `create(...)` handles it with the `config == null` guard shown above, which delegates
  every projection to the fallback. This matches the current `config != null` guard in both
  copies and keeps the `ProjectionFactory.lora(null, loraLayers)` contract (forward the null
  config; adapt nothing) working unchanged.

### `ProjectionFactory` statics

```java
static ProjectionFactory dense() {
	return new DenseProjectionFactory();
}

static ProjectionFactory lora(AdapterConfig config, List<LoRALinear> loraLayers) {
	return new LoRAProjectionFactory(config,
			loraLayers == null ? layer -> { } : loraLayers::add,
			dense());
}
```

The null-list handling keeps the current contract (`if (loraLayers != null)`). Nothing
else in the interface changes. `@FunctionalInterface` stays, so callers can still supply a
lambda.

### `LowRankAdapterSupport`

```java
default ProjectionFactory getProjectionFactory() {
	return loraProjectionFactory();
}

default CellularLayer loraOrDense(TraversalPolicy inputShape, PackedCollection weights,
								  PackedCollection bias, AdapterConfig.TargetLayer targetLayer) {
	return loraProjectionFactory().create(inputShape, weights, bias, targetLayer);
}

private LoRAProjectionFactory loraProjectionFactory() {
	return new LoRAProjectionFactory(getAdapterConfig(), this::addLoraLayer,
			(shape, weights, bias, target) -> dense(shape, weights, bias, false));
}
```

The private interface method holds the one construction both defaults share. What keeps
`loraOrDense` independent of an overridden `getProjectionFactory()` is that it builds its
own factory through this shared helper instead of calling `getProjectionFactory()` (see
below). The helper being private is a separate guarantee: it stops an implementor from
overriding the shared construction itself.

`getAdapterConfig()` is read when the factory is created, which is when it is read today.
`loraOrDense` keeps reading the config per call, because it builds a fresh factory each time.
The three-argument `loraOrDense` overload already delegates to the four-argument one and
does not change.

The fallback lambda keeps today's dispatch. Today the dense fallback in both
`LowRankAdapterSupport` methods calls `dense(...)` on the implementor itself
(`LowRankAdapterSupport extends LayerFeatures`), so an implementor that overrode
`dense(TraversalPolicy, PackedCollection, PackedCollection, boolean, ComputeRequirement...)`
has its override used. A lambda has no `this` of its own, so the unqualified `dense(...)`
in the fallback lambda above still resolves to the implementor, and that stays true. Had
`LoRAProjectionFactory` instead inherited its fallback from `DenseProjectionFactory` (the
earlier draft of this plan), the fallback would call the factory's own inherited
`LayerFeatures.dense(...)` and silently bypass such an override. No implementor in this
repository overrides that method (the only `dense(TraversalPolicy, ...)` declarations are
the two defaults in `LayerFeatures`; the implementors are `LoRADiffusionTransformer` and
the test class `AdapterConfigTests`), but `LowRankAdapterSupport` is a public interface of
a published library and `LayerFeatures.dense(...)` is a public, overridable default, so
downstream code may depend on it. Open Question 6 records the simpler alternative and its
cost.

The fallback lambda is a `ProjectionFactory` lambda, as the body of today's
`getProjectionFactory()` already is. It creates no anonymous Features object, so it does
not reintroduce the problem this plan removes.

`loraOrDense` deliberately does not call `getProjectionFactory()`. That method is
overridable, and in the one production implementor it *is* overridden: `DiffusionTransformer`
declares a concrete `getProjectionFactory()` returning `ProjectionFactory.dense()`, and
`LoRADiffusionTransformer` (which extends it and implements `LowRankAdapterSupport`) has to
override it again with `LowRankAdapterSupport.super.getProjectionFactory()` — its javadoc
explains that a concrete superclass method otherwise wins over the interface default. Any
implementor in that position that omitted the override would see `loraOrDense` silently
return dense layers if it were routed through `getProjectionFactory()`. Today `loraOrDense`
is independent of that method, and the change should keep it so.

### Code-policy fit

These classes only build layers: they describe the graph and do not execute it. Nothing in
them calls `evaluate()`, `toDouble()` or similar. The class names end in `Factory`, and each
class *is* the `ProjectionFactory` it is named after, which satisfies the "a class IS what
its name says" rule.

## Success Criteria

- `ProjectionFactory.java` contains no `new LayerFeatures() {}`. A repository-wide
  `grep -rn "new LayerFeatures() {}" --include='*.java' .` (quote the glob; zsh rejects it
  unquoted) returns nothing outside `target/`. Today its only two hits are the two
  statics in `ProjectionFactory`.
- `ProjectionFactory.dense()` returns a `DenseProjectionFactory`;
  `ProjectionFactory.lora(...)` and the `LowRankAdapterSupport.getProjectionFactory()`
  default return a `LoRAProjectionFactory`. Neither class creates anonymous Features
  instances.
- The LoRA "targeted → `LoRALinear` + register, else fallback" logic has exactly one
  definition (`LoRAProjectionFactory.create`).
- Untargeted projections from a `LowRankAdapterSupport` implementor still go through the
  implementor's own `dense(...)`, proven by the override-dispatch test below. (If the
  approver picks option (a) of Open Question 6 instead, this criterion is replaced by a
  javadoc sentence on both defaults stating the change, and that test is dropped.)
- `mvn clean install -DskipTests` succeeds, and the build validator (checkstyle,
  code_policy, test_timeouts, duplicate_code) reports no violations.
- A new test class, `engine/utils/src/test/java/org/almostrealism/layers/test/ProjectionFactoryTests.java`
  (extends `TestSuiteBase`, every `@Test` has a timeout), covers:
  - the concrete types: `ProjectionFactory.dense()` is a `DenseProjectionFactory`, and
    `ProjectionFactory.lora(config, list)` and a `LowRankAdapterSupport` implementor's
    `getProjectionFactory()` are each a `LoRAProjectionFactory`. Named types are the point
    of the task, and the behaviour checks below would still pass if the statics kept
    returning lambdas;
  - a `dense()` layer gives the same output as `LayerFeatures.dense(...)` for the same
    weights and bias, with and without bias;
  - `lora(config, list)` returns a `LoRALinear` for a targeted layer and adds it to the
    list, and returns a non-LoRA layer for an untargeted one without touching the list;
  - `lora(null, list)` and `lora(config, null)` follow the current null contracts;
  - the `LoRAProjectionFactory` constructor contract: direct construction with a null
    `registry` or null `fallback` is rejected (`NullPointerException` from
    `Objects.requireNonNull`), while a null `config` is accepted and makes every projection
    untargeted (delegated to the fallback). This records the decision that "skip
    registration" is expressed by an explicit no-op hook, not by a null;
  - `LowRankAdapterSupport.getProjectionFactory()` registers adapters via `addLoraLayer`;
  - `loraOrDense(...)` still creates and registers a `LoRALinear` for a targeted layer when
    the implementor overrides `getProjectionFactory()` to return `ProjectionFactory.dense()`
    (the `DiffusionTransformer` situation described under Approach);
  - override dispatch: a `LowRankAdapterSupport` implementor whose `dense(...)` override
    is observable (it counts calls, or returns a recognisable layer) has that override
    used for an untargeted projection via both `getProjectionFactory().create(...)` and
    `loraOrDense(...)`. Dropped only if the approver picks option (a) of Open Question 6.
- Existing tests pass unchanged, run as bounded selections:
  - `engine/utils`: `LoRALinearTests`, `AdapterConfigTests` (implements
    `LowRankAdapterSupport` and exercises `loraOrDense`).
  - `engine/ml`: `SequenceAttentionAssetTest` (includes
    `adaptedProjectionsComeFromTheProjectionFactory`, the one test calling
    `ProjectionFactory.lora`), `CausalSequenceAttentionTest`,
    `TransformerBlockFeaturesTest`, `DifferentialAttentionTest`.
  - `AttentionGradientScalingTest#testLoRADiffusionTransformerGradient` (engine/ml,
    `@TestDepth(2)`, 600 s timeout) is the one test in that class that reaches the
    factory: it builds a `LoRADiffusionTransformer`, whose model construction in
    `DiffusionTransformer` passes `getProjectionFactory()` to the attention builders, and
    `LoRADiffusionTransformer.getProjectionFactory()` resolves to
    `LowRankAdapterSupport.super.getProjectionFactory()`. Run it as a named method if time
    allows, otherwise leave it to CI. Do **not** count
    `testLoRATransformerBlockGradient` (`@TestDepth(1)`) as coverage: its
    `buildLoRATransformerBlock` helper constructs `LoRALinear` directly and never touches a
    `ProjectionFactory`. `EndToEndFineTuningTest` (engine/ml) and
    `AggressiveFineTuningTest` (studio/compose) also build a `LoRADiffusionTransformer`
    and are likewise CI's to cover. The cheap, direct check of the
    `LowRankAdapterSupport.getProjectionFactory()` default is the `ProjectionFactoryTests`
    case above; the `LoRADiffusionTransformer` override is unchanged by this work and only
    delegates to that default.

## Dependencies

None. The task has no blockers. The full set of files the work touches:

- Production code, all in `ar-graph` (`domain/graph`, package `org.almostrealism.layers`):
  new `DenseProjectionFactory.java` and `LoRAProjectionFactory.java`; edits to
  `ProjectionFactory.java` and `LowRankAdapterSupport.java`. No production code in any
  other module changes.
- Tests, in `ar-utils` (`engine/utils`, package `org.almostrealism.layers.test`, where
  `LoRALinearTests` and `AdapterConfigTests` already live): new `ProjectionFactoryTests.java`.
- Documentation: the `ProjectionFactory` javadoc (in the production file above),
  `domain/graph/README.md`, `engine/ml/README.md`, `docs/modules/graph.html` and
  `docs/modules/ml.html` (Scope item 5).

No `pom.xml` changes are needed: `engine/utils` tests already compile against
`ar-graph`. The work runs on any backend, so it has no platform requirement.

## Open Questions

1. **Folding in `LowRankAdapterSupport`.** The task names only the static `dense()` and
   `lora()` factories. The plan also points `LowRankAdapterSupport.getProjectionFactory()`
   and `loraOrDense()` at `LoRAProjectionFactory`. Otherwise the change leaves a second and
   third copy of the logic it just named. If the approver wants the change limited to
   `ProjectionFactory.java`, drop Scope item 4. The rest of the plan does not depend on it.
2. **Inheritance vs. composition for the LoRA fallback (settled by Open Question 6).** An
   earlier draft had `LoRAProjectionFactory extends DenseProjectionFactory`, with the
   fallback as `super.create(...)`. The plan now uses composition: the fallback is a
   `ProjectionFactory` constructor argument. This is required to keep the dispatch
   described in Open Question 6. It also avoids every LoRA factory being
   `instanceof DenseProjectionFactory`, which would stop anyone using that type to mean
   "plain dense projections". Inheritance comes back only together with option (a) of
   Open Question 6.
3. **Shared vs. fresh dense instance.** `dense()` could return a singleton because the
   class is stateless. The plan keeps fresh instances unless the approver prefers the
   constant.
4. **`loraOrDense` independence from `getProjectionFactory()`.** The original draft of this
   plan had `loraOrDense` call `getProjectionFactory().create(...)`. That would change
   behaviour for any implementor whose `getProjectionFactory()` resolves to something
   other than the interface default (see Approach), so the plan now has `loraOrDense`
   build its own `LoRAProjectionFactory` through the shared `loraProjectionFactory()`
   helper instead of calling `getProjectionFactory()` — that is what keeps it independent.
   The helper being private is a separate guarantee that stops an implementor overriding
   the shared construction itself. If the approver would rather `loraOrDense`
   follow an overridden `getProjectionFactory()` on purpose, that is a behaviour change
   and should be stated as one.
5. **Is `loraOrDense` still needed?** Its only caller is `AdapterConfigTests`; no
   production code calls it. The plan keeps it (removing public API is outside the task),
   but the approver may prefer to deprecate it in favour of `getProjectionFactory()`.
6. **Where the dense fallback dispatches (default chosen; approver may override).** Today
   a `LowRankAdapterSupport` implementor that overrides `LayerFeatures.dense(...)` has
   that override used for untargeted projections. Nothing in this repository overrides
   it, but downstream code may, so changing it is a public-API compatibility break, not a
   no-op. There are two options:
   - **(b) Preserve the hook — what the plan now proposes.** `LoRAProjectionFactory`
     takes its fallback as a `ProjectionFactory`: the statics pass
     `ProjectionFactory.dense()`, and `LowRankAdapterSupport` passes
     `(shape, weights, bias, target) -> dense(shape, weights, bias, false)`, which runs on
     the implementor and so keeps today's dispatch. Cost: one constructor parameter,
     composition in place of inheritance (Open Question 2), and the override-dispatch
     test under Success Criteria.
   - **(a) Accept the break.** `LoRAProjectionFactory extends DenseProjectionFactory` and
     falls back to `super.create(...)`, so untargeted projections use
     `LayerFeatures.dense(...)` as inherited by the factory, not the implementor's
     override. Add a sentence saying so to the `getProjectionFactory()` and
     `loraOrDense(...)` javadoc and to the commit message, and drop the override-dispatch
     test. Saves one parameter and one test.

   History: the first drafts of this plan sketched (a), on the grounds that no
   in-repository implementor needs the hook, and left the choice to the approver while
   the Approach sketch and Success Criteria described different things. A PR review
   asked that the plan pick one and align the sketch and tests with it. The plan now
   picks (b), because it is the option that changes no observable behaviour, and a
   refactor whose stated goal is naming existing strategies should not also change
   public behaviour as a side effect. If the approver prefers (a), the edits are the ones
   listed under (a) above.

## Estimated Complexity

Small. Two new classes of a few dozen lines each, two static methods and two default
methods rewritten plus one private interface method added to `LowRankAdapterSupport`, the
`ProjectionFactory` javadoc example replaced, a short mention added
to two READMEs and the two matching HTML module pages, and one new test class.
