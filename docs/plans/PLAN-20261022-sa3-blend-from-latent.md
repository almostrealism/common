# Blend from an adjusted latent with native Stable Audio 3

- **Tracker task:** `e72668a2-3645-4da6-b4e4-397424b44e39` — "Blend from an adjusted latent with native Stable Audio 3"
- **Release:** Common 0.76
- **Estimated complexity:** Medium

## Motivation

`StableAudio3` (`engine/ml/src/main/java/org/almostrealism/ml/audio/StableAudio3.java`)
today does only text-to-audio: `generate(...)` samples a latent from pure noise with
`DiffusionSampler.sample(...)` and decodes it, and the model compiles only the
`SAMEAutoEncoder` **decoder**. The framework's blending flow — encode audio into the
latent space, adjust that latent (for example compose several sources), then run diffusion
*from that latent* at a chosen strength rather than from noise — exists only on the older
pipeline (`AudioGenerator` composing with `AudioComposer`, `AudioDiffusionGenerator`
calling `DiffusionSampler.sampleFrom(...)`), which is built on the older autoencoder and
transformer. This task makes that same flow work with native SA3, so a caller can encode a
clip with SAME-S, adjust/compose the latent, and generate audio from it with the released
SA3 components.

## Scope

Concretely, within the boundaries the task sets:

1. **engine/ml — a blend entry point on `StableAudio3`.** Add a `generateFrom(...)` method
   (and a text-prompt convenience overload) that runs SA3 diffusion from a supplied start
   latent and strength, with prompt conditioning, by delegating to the existing
   `DiffusionSampler.sampleFrom(...)` and reusing the ping-pong schedule, conditioner,
   valid-frame/padding-mask setup and decoder that `generate(...)` already uses. Refactor
   the shared body of `generate(...)` so the noise path and the blend path do not duplicate
   the conditioning, guidance, sampling and decode logic.

2. **engine/ml — a fixture-based end-to-end test.** Encode a synthetic clip with the small
   `SAMEAutoEncoder` (`SAMEAutoEncoder.encoder(...)`), adjust or compose the latent, and
   generate audio from it with the synthetic `StableAudio3` built by the existing fixtures,
   asserting the two invariants the task names: strength 0 returns the decoded input latent;
   strength 1 matches plain text-to-audio from the same seed.

3. **studio/compose — `AudioComposer` over a SAME-S autoencoder.** Confirm (and, if a shape
   assumption blocks it, minimally generalize) that `AudioComposer` composes a SAME-S latent
   whose shape is 256 channels at SA3's frame rate, using the existing
   `CompiledModelAutoEncoder.of(SAMEAutoEncoder, ...)` adapter (which already compiles both
   encoder and decoder). The composed latent is the input a caller hands to
   `StableAudio3.generateFrom(...)`.

**Explicitly out of scope** (per the task): inpainting, `local_add_cond`, multi-segment
editing and the Medium variant. Also out of scope: routing native SA3 through
`AudioGenerator` itself (see Open Questions — it is the older pipeline's orchestrator and is
hardwired to the older model and latent shape; the native blend belongs on `StableAudio3`).

## Approach

### What already exists (no new code needed)

- **`DiffusionSampler.sampleFrom(startLatent, strength, seed, crossAttnCond, globalCond)`**
  is already implemented. It rejects a start latent whose shape does not match the sampler's
  `latentShape`, returns the start latent directly when `strength == 0.0`, and otherwise adds
  noise at the schedule step selected by the strength and runs the normal loop. The blend
  entry point is missing on `StableAudio3`, not on the sampler.
- **`CompiledModelAutoEncoder.of(SAMEAutoEncoder, batchSize, samples, sampleRate, maxDuration)`**
  (studio/compose) already compiles a SAME-S **encoder and decoder** into an `AutoEncoder`.
  This is the "AutoEncoder for SAME-S that encodes as well as decodes" the task asks for —
  reused rather than written anew, exactly as the task directs.
- **`AudioComposer`** is already generic over the `AutoEncoder` interface and composes
  encoded sources in latent space via `getInterpolatedLatent(position)`.

### 1. `StableAudio3.generateFrom(...)` (engine/ml)

`generate(long seed, long[] prompt, double seconds)` currently: validates the duration, sets
`transformer.setValidLength(validFrames(seconds))`, builds the `DiffusionSampler` with the
ping-pong `LogSNRShift.fixed(LOG_SNR_START, LOG_SNR_END)` schedule, runs the conditioner
(and, when guidance is on, the negative conditioner), calls `sampler.sample(seed, context,
global)`, decodes with `decoder.forward(latent)`, and returns the clamped, truncated audio.

The blend path differs in exactly one place: the initial latent comes from
`sampler.sampleFrom(startLatent, strength, seed, context, global)` instead of
`sampler.sample(seed, context, global)`. Everything else — duration validation, valid-frame
and padding-mask setup, conditioning, guidance, decode, clamp and truncation — is identical.

Plan: extract the common body into a private helper, and have both the public noise entry
point and the new blend entry point call it, differing only in how the latent is produced.
A sketch of the resulting surface (final names/signatures to be settled in review):

```java
// existing public API, unchanged for callers
public CollectionProducer generate(long seed, long[] prompt, double seconds);

// new blend entry points
public CollectionProducer generateFrom(long seed, long[] prompt, double strength,
                                       double seconds, PackedCollection startLatent);
public CollectionProducer generateFromText(long seed, String prompt, double strength,
                                           double seconds, PackedCollection startLatent);
```

`startLatent` is a materialized `PackedCollection` in the model's latent shape
`[BATCH, latentDim, latentLen]` (`equalsIgnoreAxis`, as `sampleFrom` requires). This mirrors
the older pipeline, where `AudioGenerator` evaluates the composed latent before calling
`generateFrom`; encoding/composition stay as Producers up to that pipeline boundary.

Two implementation details to get right (both called out so the implementer does not
rediscover them the hard way):

- **Strength-0 lifecycle.** `generate(...)`'s `finally` block destroys the sampler's latent.
  When `strength == 0.0`, `sampleFrom` returns *the caller's* `startLatent` object; the
  blend path must not destroy a latent it was handed. The shared helper must only release
  latents the sampler allocated, never the supplied `startLatent`.
- **Shape contract.** The `startLatent` must have been encoded for the same aligned audio
  length the model was compiled for, so that its `latentLen` equals
  `StableAudio3.getLatentLength()`. The constraint already lives in `sampleFrom`'s shape
  check; the method javadoc should state it so callers compile their encoder for the
  matching length.

### 2. Why the strength invariants hold (reuse, not new mechanism)

- **strength 0 → decoded input latent.** `sampleFrom` returns `startLatent` unchanged, and
  `generateFrom` decodes it with the SAME-S decoder. With the encoder and the model's
  decoder built from the same weights, this is the autoencoder round-trip of the input.
- **strength 1 → matches plain text-to-audio from the same seed.** This is guaranteed by
  the existing machinery, not by anything new: `DistributionShift.schedule` pins
  `schedule[0] = sigmaMax = 1.0`, so `sampleFrom(strength=1)` starts at step 0 with
  `addNoise(startLatent, 1.0, noise) = (1-1)*startLatent + 1*noise =` pure noise;
  `DiffusionSampler.sampleNoise` and `SamplingStrategy.sampleInitialNoise` are the same
  `new PackedCollection(shape).randnFill(random)` draw, and both paths then run the identical
  loop with the same conditioning. The test pins this equality so a future change to the
  schedule or noise draw cannot silently break it.

### 3. `AudioComposer` over a SAME-S autoencoder (studio/compose)

`AudioComposer` encodes each source through its `AutoEncoder` and blends in latent space.
`CompiledModelAutoEncoder.of(SAMEAutoEncoder.small(weights), 1, samples, SAMPLE_RATE,
maxDuration)` provides the SAME-S autoencoder. One shape detail to reconcile: the composer's
`createWeights(...)` reads the feature shape as an unbatched `[bins, time]`
(`shape.length(0)`/`length(1)`), whereas `CompiledModelAutoEncoder.encode` yields a batched
`[1, 256, latentLen]`. The older pipeline already handles the equivalent gap by dropping and
re-adding the batch axis (`AudioGenerator` reshapes to/from `DIT_X_SHAPE`). The SA3
integration does the same: compose on the unbatched `[256, latentLen]` latent, then present
`[1, 256, latentLen]` to `StableAudio3.generateFrom`. The implementation should prefer having
the caller (or a thin SA3-aware seam) do the batch-axis handling over widening
`AudioComposer`'s contract, unless a clean generalization of `AudioComposer` to a leading
batch axis proves simpler — a point to settle in review.

### Files involved

- `engine/ml/src/main/java/org/almostrealism/ml/audio/StableAudio3.java` — add
  `generateFrom`/`generateFromText` blend entry points; refactor the shared body of
  `generate`.
- `engine/ml/src/test/java/org/almostrealism/ml/audio/StableAudio3Test.java` (or a sibling
  test class) — the fixture-based blend test; a small test helper so the standalone SAME-S
  encoder/decoder and the `StableAudio3` instance share one set of autoencoder weights.
- `studio/compose/src/main/java/org/almostrealism/studio/ml/AudioComposer.java` — only if a
  shape assumption must be generalized; otherwise unchanged.
- `studio/compose/.../CompiledModelAutoEncoder.java` — reused unchanged (`of(...)` already
  exists).

## Success criteria

A reviewer can confirm the task is done when:

1. `StableAudio3` exposes a blend entry point that runs SA3 diffusion from a supplied start
   latent and strength with prompt conditioning, reusing `DiffusionSampler.sampleFrom` and
   the existing ping-pong schedule — with no second diffusion loop written anywhere.
2. A new engine/ml test, running on the synthetic fixtures (no released weights, so it runs
   in CI on the default backend), encodes a clip with the small `SAMEAutoEncoder`,
   adjusts/composes the latent, and asserts:
   - **strength 0**: the generated audio equals the decoded input latent (autoencoder
     round-trip), within tolerance;
   - **strength 1**: the generated audio equals `generate(seed, prompt, seconds)` for the
     same seed and prompt, within tolerance.
3. `AudioComposer` is shown to compose a SAME-S latent (via
   `CompiledModelAutoEncoder.of(SAMEAutoEncoder, ...)`) at SA3's 256-channel shape, with the
   composed latent in the shape `StableAudio3.generateFrom` accepts.
4. `mvn clean install -DskipTests` succeeds; the build validator (checkstyle, code_policy,
   test_timeouts, duplicate_code) is clean; the targeted tests above pass.

## Dependencies

- **Builds on the SA3 text-to-audio work already merged** (commit `842760034`, "Add the
  T5Gemma prompt encoder, classifier-free guidance, attention masks and RMS block
  normalization for Stable Audio 3"): `StableAudio3`, `SAMEAutoEncoder`,
  `DiffusionSampler.sampleFrom`, `CompiledModelAutoEncoder.of` and the engine/ml fixtures all
  come from that line of work. No open blockers are recorded on the tracker task.
- No pom/dependency changes are required. The blend method lives in engine/ml and takes a
  `PackedCollection`, so it does not reach into studio/compose; the composition side already
  depends on ar-ml.

## Open questions

1. **Where the full `AudioComposer` + native-SA3 end-to-end test lives.** engine/ml cannot
   see studio's `AudioComposer`/`CompiledModelAutoEncoder` (studio depends on ar-ml, not the
   reverse), and the SA3 synthetic fixtures (`SAMEAutoEncoderFixture`, `T5GemmaWeightFixture`,
   `DiffusionTransformerWeightFixture`) live in engine/ml **test** sources and are not
   published as a test-jar to studio/compose. So the fixture-based "strength 0 / strength 1"
   test (criterion 2) runs in engine/ml and encodes via `SAMEAutoEncoder.encoder(...)`
   directly, treating "adjust/compose the latent" as a latent-space adjustment it can do
   without the studio composer. Criterion 3 (AudioComposer + SAME-S) is a separate
   studio/compose test. Verifying *both AudioComposer and native SA3 generation together* in
   one test would require either released SA3 weights (CI-guarded/skipped, never gated into a
   silent skip by the implementer) or publishing the engine/ml test fixtures as a test-jar —
   a `pom.xml` change an agent may not make. **Decision needed:** accept the split
   verification (recommended), or ask the project owner to expose engine/ml test fixtures to
   studio/compose.
2. **Does `AudioGenerator` itself need to drive native SA3?** As written it is the older
   pipeline's orchestrator: `ConditionalAudioSystem` constructs its own
   `DiffusionTransformer(64, 1024, 16, ...)` and hardwires `DIT_X_SHAPE = [1, 64, 256]`, so it
   is not a wrapper one can hand a `StableAudio3`. This plan puts the native blend on
   `StableAudio3` and leaves `AudioGenerator` on the older model. If a unified high-level
   generator that can host either model is wanted, that is a larger refactor — filed as a
   follow-up task, `aff07cc2-49fb-46f8-a4b2-3a144038c2fc` ("Let the high-level AudioGenerator
   drive native Stable Audio 3") — rather than widening this one.
3. **Final method naming/overloading.** `generateFromText(long, String, double)` already
   exists for text-to-audio; the blend overload `generateFromText(long, String, double,
   double, PackedCollection)` distinguishes by arity. Confirm in review that this is clear
   enough, or choose a distinct name (e.g. `blendFromText`).

## Machine requirements

None. The synthetic-fixture tests run on the default (CPU) backend in CI, as the existing
`StableAudio3Test` does. No Metal/macOS-only step is introduced, so no
`requiredLabels` settings file accompanies this plan. (The known SAME resampling NaN on
Metal only affects released-weight runs, which are out of scope here.)
