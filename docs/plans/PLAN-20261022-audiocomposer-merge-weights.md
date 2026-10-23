# Controllable per-source merge weights for AudioComposer latent blending

**Tracker task:** `7dca3a77-2708-4905-bb2b-397b7f4d1d87` — *Controllable per-source merge weights for AudioComposer latent blending*
**Release:** Common 0.76
**Estimated complexity:** Small

## Motivation

`AudioComposer` (`studio/compose/src/main/java/org/almostrealism/studio/ml/AudioComposer.java`)
blends several encoded audio sources in latent space. Today every source added
to the composer is assigned a **random Gaussian weight vector** (`createWeights`,
standard deviation `getDeviation()`, optionally L2-normalized). At composition
time each source's contribution is the dot product of its weight vector with a
caller-supplied position vector, clamped to be non-negative, used to scale that
source's latent features; the composer sums the scaled sources. A caller can
steer the blend only indirectly, by moving the position vector through a latent
field of weights it did not choose and cannot inspect.

<!-- TODO(review): remove the private-product reference below; public repo docs must describe motivation in platform terms -->
This is the framework capability behind the Rings 0.41 *"Audio sculpting becomes
the real workflow"* goal — specifically *"media brushes … with fine-grained
control over how the different sources are merged during diffusion."* Merging
multiple media sources in latent space is a general generation capability that
belongs in the framework; any application performing multi-source latent
composition needs to be able to say, deterministically, how much each source
contributes. This task adds that control while leaving the existing random-weight
behaviour as the untouched default.

## Background: how blending works today

- `AudioComposer` keeps `List<ComposableAudioFeatures>`, one entry per added
  source.
- `addSource(Producer<PackedCollection> features)` wraps the encoded features in
  a `ComposableAudioFeatures` whose weight tensor comes from `createWeights`:
  `randn(shape(dim), …, random)`, optionally `normalize`d, then
  `.repeat(bins).repeat(time)` so the single length-`dim` vector is broadcast
  across the feature's two leading dimensions.
- `ComposableAudioFeatures` is a `Factor<PackedCollection>`. Its
  `getResultant(value)` computes, per feature cell, the dot product of the
  length-`dim` weight vector with `value`, clamps it with `max(·, 0)`, and uses
  it to scale the features.
- `AudioComposer.getInterpolatedLatent(value)` sums the per-source resultants.
  `getResultant(value)` then decodes that sum with the autoencoder.
  `getInterpolatedLatent` itself does **not** touch the autoencoder — it is pure
  latent-space `CollectionProducer` arithmetic.

There are currently no tests for `AudioComposer` or `ComposableAudioFeatures`.
Consumers of the composer are `AudioModulator`, `AudioGenerator`, and
`LegacyAudioGenerator`, all in the same `studio.ml` package; each adds sources
and calls `getResultant`/`getInterpolatedLatent` with a position vector.

## Scope

Two additions, both additive and defaulting to today's behaviour:

1. **Explicit per-source merge weight vectors on `AudioComposer`.** Let a caller
   supply the length-`dim` weight vector for a source instead of having it
   sampled from the Gaussian — both when adding a source and as an override of
   an already-added source. This is the literal "set/override the per-source
   merge weights" requirement: the caller chooses the vectors that, together with
   the position vector, determine each source's contribution.

2. **Per-source scalar blend contribution on `ComposableAudioFeatures`.** A single
   scalar multiplier per source (default `1.0`) applied to that source's
   resultant, so the composed latent is `sum_i(contribution_i · resultant_i)`.
   This is the "where meaningful, a per-source blend contribution" clause and the
   simplest deterministic knob for "how much each source contributes" — it does
   not depend on the position vector at all.

**Out of scope** (name as follow-ups, do not widen this task):

- Changing how `AudioModulator` / `AudioGenerator` / `LegacyAudioGenerator`
  expose blending to their own callers. They keep compiling and behaving as they
  do; surfacing the new control through them is separate work.
- Any change to the autoencoder, diffusion sampler, or decode path.
- Persisting or serializing weights/contributions.

## Approach

All changes land in the two existing classes in
`studio/compose/src/main/java/org/almostrealism/studio/ml/`. No new classes, no
new modules, no `pom.xml` changes. The guiding rule is that
`ComposableAudioFeatures` remains a `Factor<PackedCollection>` and every new
member stays correct for any producer passed in.

### 1. `AudioComposer` — explicit weight vectors

- Split the current `createWeights(Producer)` so the random **sampling** and the
  **broadcast** (`.repeat(bins).repeat(time)`) are separable. Introduce a
  protected method that broadcasts a given length-`dim` weight-vector producer to
  the feature's `(bins, time, dim)` shape; `createWeights` becomes "sample a
  Gaussian vector, then broadcast", so the default path is byte-for-byte the
  behaviour it is today.
- Add `addSource(Producer<PackedCollection> features, Producer<PackedCollection>
  weights)`: wraps the features in a `ComposableAudioFeatures` whose weight tensor
  is the caller's vector broadcast through the same step — no `random` draw. The
  existing shape-consistency check in `addSource(ComposableAudioFeatures)` is
  reused.
- Add `setWeights(int index, Producer<PackedCollection> weights)` to override the
  weight vector of an already-added source. Because the weight tensor is private
  state of `ComposableAudioFeatures`, the override is applied by asking that
  object to adopt the new (broadcast) weights — the composer does not reach into
  its fields. (Implementation detail for the approver to confirm: expose a
  package-private/public `setWeights` on `ComposableAudioFeatures` that
  `AudioComposer` calls, rather than replacing the list entry, so any contribution
  already set on that source is preserved.)
- Decide (open question below) whether caller-supplied vectors are passed through
  verbatim or subjected to the same optional `normalizeWeights` treatment as the
  sampled ones. Recommended default: respect the caller's vector verbatim and
  document it, since the whole point is explicit control; `normalizeWeights`
  continues to govern only the sampled default path.

### 2. `ComposableAudioFeatures` — scalar blend contribution

- Add a `double contribution` field defaulting to `1.0`, with getter/setter, and
  multiply the resultant by it:
  `c(features).multiply(max(scale, c(0))).multiply(c(contribution))` (constant
  folded; `contribution == 1.0` leaves the graph identical to today). The
  multiplier is expressed as a `Producer` constant so the computation stays in the
  graph — no Java-side arithmetic on `PackedCollection`.
- Keep `getResultant(value)` honouring the `Factor` contract: when the
  contribution is the default and no explicit weights were supplied, the resultant
  is exactly what it is today.
- This is the per-source state the task anticipated ("… and
  `ComposableAudioFeatures` if per-source state is needed").

### Determinism

With explicit weight vectors (or a chosen, fixed position vector) and/or explicit
contributions, `getInterpolatedLatent(value)` is a fixed function of the source
features — no `Random` is consulted. That is what makes the composed latent
deterministic and reproducible.

## Success criteria

A reviewer can confirm the task is done when:

1. A caller can add several sources, set explicit merge weights (and/or a
   per-source contribution), and obtain a composed latent that is a deterministic
   function of those inputs — no dependence on the random seed.
2. Omitting explicit weights/contributions reproduces the current random-weight
   behaviour exactly (same code path, same `createWeights` result for a given
   seed).
3. The build validator passes (`checkstyle`, `code_policy`, `test_timeouts`,
   `duplicate_code`) — in particular no Producer-pattern violation: all new math
   is `CollectionProducer` composition, no `.evaluate()`/`.toDouble()` inside the
   classes.
4. `mvn clean install -DskipTests` succeeds and the three existing consumers
   (`AudioModulator`, `AudioGenerator`, `LegacyAudioGenerator`) still compile
   unchanged.

### Tests to add

A new `AudioComposerTest extends TestSuiteBase` in
`studio/compose/src/test/java/org/almostrealism/studio/ml/`, using a trivial
in-test `AutoEncoder` stub (the interface lives in
`engine/ml/src/main/java/org/almostrealism/ml/audio/AutoEncoder.java`; the stub is
never exercised because `getInterpolatedLatent` does not decode). Each `@Test`
carries an explicit `timeout`. Cases:

- **Explicit weights → deterministic latent.** Add two sources with chosen,
  orthogonal unit weight vectors and feature tensors of known value; call
  `getInterpolatedLatent` with a position vector that selects known per-source
  scalars; assert the composed latent equals the hand-computed weighted sum
  (small tolerance). Running twice (or with different seeds) yields identical
  results.
- **Scalar contribution.** Two sources, explicit contributions (e.g. `0.75` and
  `0.25`); assert the composed latent is the correspondingly weighted sum.
- **Default path unchanged.** With no explicit weights/contribution and a fixed
  seed, assert the composed latent matches the value produced by the current
  random path for that seed (guards against the refactor of `createWeights`
  changing behaviour).

These run on the CPU/native backend — the computation is pure latent-space
arithmetic, so no GPU/Metal is required.

## Dependencies and open questions

- **No blocking dependencies.** The task's `blocked_by` list is empty and the two
  target classes already exist.
- **Override semantics for `setWeights`:** confirm the preferred API shape — a
  mutator on `ComposableAudioFeatures` that `AudioComposer.setWeights(index, …)`
  delegates to (preserves any set contribution; honours encapsulation), versus
  replacing the list entry. The plan recommends the mutator.
- **Normalization of explicit weights:** confirm that caller-supplied vectors are
  used verbatim (recommended) rather than being forced through `normalizeWeights`.
- **Weight-vector shape contract:** the recommended API takes a length-`dim`
  vector and broadcasts it exactly as the sampled path does. Confirm whether
  callers should also be allowed to pass a pre-broadcast `(bins, time, dim)`
  tensor; the plan keeps the single-vector contract to match `createWeights` and
  avoid a second, redundant shape path.
- **Surfacing through the generators:** exposing the new control through
  `AudioModulator`/`AudioGenerator`/`LegacyAudioGenerator` is deliberately out of
  scope; if the approver wants it, it should be filed as a follow-up task.

## Implementation notes

- Open questions were resolved as recommended: `AudioComposer.setWeights(index, …)`
  delegates to a `ComposableAudioFeatures.setWeights` mutator (contribution is
  preserved), explicit vectors are used verbatim (no `normalizeWeights`), and only a
  length-`dim` vector is accepted (`AudioComposer.requireWeightVector` rejects any
  other length). Broadcasting a weight vector across the feature cells is done by
  `ComposableAudioFeatures` itself (its constructor and `setWeights` accept either a
  `(T, B, L)` tensor or a length-`L` vector), since only it knows its feature shape;
  the composer passes vectors and never reads a source's features.
  The per-source contribution is set on the source itself via
  `AudioComposer.getSource(index).setContribution(…)`.
- **Pre-existing defect fixed.** `ComposableAudioFeatures.getResultant` computed
  `c(weights).traverse(2).multiply(c(value)).sum()`. Evaluation showed the
  length-`L` value vector was broadcast along the weight tensor's *leading* axis, so
  each cell's scale was a single component of `value` selected by row — the weights
  played no part and no dot product was taken. Explicit merge weights could not
  have had any effect on top of that, so the value is now tiled explicitly to the
  weight shape before the element-wise product. This intentionally changes the
  numbers the default random-weight path produces; success criterion 2 is therefore
  guarded as "the default path builds the same graph as sampling a Gaussian vector
  with the seed and broadcasting it" (`AudioComposerTest.defaultPathMatchesRandomWeights`)
  rather than as bit-identity with the defective output.
- Tests: `AudioComposerTest` (explicit weights, clamping, contributions, override,
  length validation, default path) and `ComposableAudioFeaturesTest` (per-cell dot
  product with distinct weight vectors per cell, contribution).

## No special machine required

The implementation and its tests are pure latent-space `CollectionProducer`
arithmetic that runs on the CPU/native backend. No Metal/macOS node is needed, so
no `…-workstream.yaml` settings file accompanies this plan.
