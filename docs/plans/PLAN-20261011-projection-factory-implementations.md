# Concrete Dense and LoRA Projection Factories

**Tracker task:** `7881b11f-127d-42fb-842f-35afd654c97e`: "ProjectionFactory: static
dense()/lora() create throwaway new LayerFeatures(){} although the interface extends
LayerFeatures"
**Release:** Common 0.76 (project "Common")
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
2. Add a named class `LoRAProjectionFactory` in the same package. It is built from an
   `AdapterConfig` and a `Consumer<LoRALinear>` that receives each adapter it creates.
   For targeted layers it creates a `LoRALinear`. For all other layers it falls back to its
   own inherited `dense(...)`.
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
   - `docs/internals/ml-inference-pipeline.md` mentions `ProjectionFactory` only as a
     parameter of `sequenceAttention` and needs no change.
   <!-- TODO(review): docs/modules/graph.html and docs/modules/ml.html carry the same ProjectionFactory.lora examples as the two READMEs; decide whether they get the same one-sentence mention. -->


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
public class LoRAProjectionFactory extends DenseProjectionFactory {
	private final AdapterConfig config;
	private final Consumer<LoRALinear> registry;

	public LoRAProjectionFactory(AdapterConfig config, Consumer<LoRALinear> registry) { ... }

	@Override
	public CellularLayer create(TraversalPolicy inputShape, PackedCollection weights,
								PackedCollection bias, AdapterConfig.TargetLayer targetLayer) {
		if (config == null || !config.isTargeted(targetLayer)) {
			return super.create(inputShape, weights, bias, targetLayer);
		}

		LoRALinear layer = new LoRALinear(inputShape, weights, bias,
				config.getRank(), config.getAlpha());
		registry.accept(layer);
		return layer;
	}
}
```

- Extending `DenseProjectionFactory` makes the fallback a `super.create(...)` call, so the
  dense path has a single definition. The "is-a" reading is that a LoRA factory is a dense
  factory that adapts the targeted layers.
- The registration hook is a `Consumer<LoRALinear>` and not a `List`. A `List` would fit
  only `ProjectionFactory.lora(...)`, whose callers pass a list. `LowRankAdapterSupport`
  registers through its abstract `addLoraLayer(LoRALinear)`, which a `Consumer` covers
  directly (`this::addLoraLayer`).
- A null `AdapterConfig` means "never adapt". This matches the current `config != null`
  guard in both copies.

### `ProjectionFactory` statics

```java
static ProjectionFactory dense() {
	return new DenseProjectionFactory();
}

static ProjectionFactory lora(AdapterConfig config, List<LoRALinear> loraLayers) {
	return new LoRAProjectionFactory(config, loraLayers == null ? layer -> { } : loraLayers::add);
}
```

The null-list handling keeps the current contract (`if (loraLayers != null)`). Nothing
else in the interface changes. `@FunctionalInterface` stays, so callers can still supply a
lambda.

### `LowRankAdapterSupport`

```java
default ProjectionFactory getProjectionFactory() {
	return new LoRAProjectionFactory(getAdapterConfig(), this::addLoraLayer);
}

default CellularLayer loraOrDense(TraversalPolicy inputShape, PackedCollection weights,
								  PackedCollection bias, AdapterConfig.TargetLayer targetLayer) {
	return new LoRAProjectionFactory(getAdapterConfig(), this::addLoraLayer)
			.create(inputShape, weights, bias, targetLayer);
}
```

`getAdapterConfig()` is read when the factory is created, which is when it is read today.
`loraOrDense` keeps reading the config per call, because it builds a fresh factory each time.
The three-argument `loraOrDense` overload already delegates to the four-argument one and
does not change.

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
- `ProjectionFactory.dense()` returns a `DenseProjectionFactory`, and
  `ProjectionFactory.lora(...)` returns a `LoRAProjectionFactory`. Neither class creates
  anonymous Features instances.
- The LoRA "targeted → `LoRALinear` + register, else dense" logic has exactly one
  definition (`LoRAProjectionFactory.create`).
- `mvn clean install -DskipTests` succeeds, and the build validator (checkstyle,
  code_policy, test_timeouts, duplicate_code) reports no violations.
- A new test class, `engine/utils/src/test/java/org/almostrealism/layers/test/ProjectionFactoryTests.java`
  (extends `TestSuiteBase`, every `@Test` has a timeout), covers:
  - a `dense()` layer gives the same output as `LayerFeatures.dense(...)` for the same
    weights and bias, with and without bias;
  - `lora(config, list)` returns a `LoRALinear` for a targeted layer and adds it to the
    list, and returns a non-LoRA layer for an untargeted one without touching the list;
  - `lora(null, list)` and `lora(config, null)` follow the current null contracts;
  - `LowRankAdapterSupport.getProjectionFactory()` registers adapters via `addLoraLayer`;
  - `loraOrDense(...)` still creates and registers a `LoRALinear` for a targeted layer when
    the implementor overrides `getProjectionFactory()` to return `ProjectionFactory.dense()`
    (the `DiffusionTransformer` situation described under Approach).
- Existing tests pass unchanged, run as bounded selections:
  - `engine/utils`: `LoRALinearTests`, `AdapterConfigTests` (implements
    `LowRankAdapterSupport` and exercises `loraOrDense`).
  - `engine/ml`: `SequenceAttentionAssetTest` (includes
    `adaptedProjectionsComeFromTheProjectionFactory`, the one test calling
    `ProjectionFactory.lora`), `CausalSequenceAttentionTest`,
    `TransformerBlockFeaturesTest`, `DifferentialAttentionTest`.
  - `AttentionGradientScalingTest#testLoRATransformerBlockGradient` (engine/ml,
    `@TestDepth(1)`, 600 s timeout) reaches `LowRankAdapterSupport.getProjectionFactory()`
    indirectly, through `LoRADiffusionTransformer.getProjectionFactory()`; the test does
    not call the factory itself. Run it as a named method if time allows, otherwise leave
    it to CI. `EndToEndFineTuningTest` (engine/ml) and `AggressiveFineTuningTest`
    (studio/compose) also build a `LoRADiffusionTransformer` and are likewise CI's to
    cover.

## Dependencies

None. The task has no blockers. The full set of files the work touches:

- Production code, all in `ar-graph` (`domain/graph`, package `org.almostrealism.layers`):
  new `DenseProjectionFactory.java` and `LoRAProjectionFactory.java`; edits to
  `ProjectionFactory.java` and `LowRankAdapterSupport.java`. No production code in any
  other module changes.
- Tests, in `ar-utils` (`engine/utils`, package `org.almostrealism.layers.test`, where
  `LoRALinearTests` and `AdapterConfigTests` already live): new `ProjectionFactoryTests.java`.
- Documentation: `domain/graph/README.md` and `engine/ml/README.md` (Scope item 5).

No `pom.xml` changes are needed: `engine/utils` tests already compile against
`ar-graph`. The work runs on any backend, so it has no platform requirement.

## Open Questions

1. **Folding in `LowRankAdapterSupport`.** The task names only the static `dense()` and
   `lora()` factories. The plan also points `LowRankAdapterSupport.getProjectionFactory()`
   and `loraOrDense()` at `LoRAProjectionFactory`. Otherwise the change leaves a second and
   third copy of the logic it just named. If the approver wants the change limited to
   `ProjectionFactory.java`, drop Scope item 4. The rest of the plan does not depend on it.
2. **Inheritance vs. composition for the LoRA fallback.** The plan has
   `LoRAProjectionFactory extends DenseProjectionFactory`. The alternative is two sibling
   implementations of `ProjectionFactory`, each calling its own inherited `dense(...)`.
   That duplicates a single line but avoids the subclass relationship. A side effect of
   inheritance to weigh: every LoRA factory is then `instanceof DenseProjectionFactory`,
   so no code could use that type to mean "plain dense projections". Nothing does today.
3. **Shared vs. fresh dense instance.** `dense()` could return a singleton because the
   class is stateless. The plan keeps fresh instances unless the approver prefers the
   constant.
4. **`loraOrDense` independence from `getProjectionFactory()`.** The original draft of this
   plan had `loraOrDense` call `getProjectionFactory().create(...)`. That would change
   behaviour for any implementor whose `getProjectionFactory()` resolves to something
   other than the interface default (see Approach), so the plan now has `loraOrDense`
   construct its own `LoRAProjectionFactory`. If the approver would rather `loraOrDense`
   follow an overridden `getProjectionFactory()` on purpose, that is a behaviour change
   and should be stated as one.
5. **Is `loraOrDense` still needed?** Its only caller is `AdapterConfigTests`; no
   production code calls it. The plan keeps it (removing public API is outside the task),
   but the approver may prefer to deprecate it in favour of `getProjectionFactory()`.

## Estimated Complexity

Small. Two new classes of a few dozen lines each, two static methods and two default
methods rewritten, the `ProjectionFactory` javadoc example replaced, a short mention added
to two READMEs, and one new test class.
