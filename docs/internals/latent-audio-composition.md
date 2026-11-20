# Composing Audio in Latent Space: the AutoEncoder Blend Pipeline

The platform can compose new audio from several existing sources by blending them in the
**latent space** of an audio autoencoder. Each source is an encoded latent; a position vector
decides how much of each source goes into the mix; the blend is a computation graph compiled to
native code; and the composed latent is decoded back to a waveform (or handed to the diffusion
generator as a starting point).

This page walks that pipeline end to end. The API reference lives in the Javadoc of
`AudioComposer` and `ComposableAudioFeatures` and in the "ML Audio Package" section of the
[`studio/compose` README](../../studio/compose/README.md#ml-audio-package). This page shows how
the pieces fit together and which numerical contract each blend mode keeps.

The worked examples are in `LatentAudioCompositionExamplesTest` in `studio/compose`
(`org.almostrealism.studio.ml`). Code blocks on this page are taken from that test unless they
are marked as fragments, and every number the page prints is an assertion in the matching test
method. The examples use a synthetic autoencoder, so they run on any backend with no model
download. The full set of edge cases (random weights, near-origin positions, overrides, shape
validation) is covered by `AudioComposerTest` in the same package.

---

## 1. Where each part lives

| Part | Module | Classes |
|---|---|---|
| The autoencoder contract | `engine/ml` (`org.almostrealism.ml.audio`) | `AutoEncoder` |
| Composition | `studio/compose` (`org.almostrealism.studio.ml`) | `AudioComposer`, `ComposableAudioFeatures` |
| Compiled-model autoencoder | `studio/compose` (`org.almostrealism.studio.ml`) | `CompiledModelAutoEncoder` |
| ONNX autoencoder | `extern/ml-onnx` | `OnnxAutoEncoder` |
| Production callers | `studio/compose` (`org.almostrealism.studio.ml`) | `AudioGenerator`, `LegacyAudioGenerator`, `AudioModulator` |

---

## 2. The `AutoEncoder` abstraction

`org.almostrealism.ml.audio.AutoEncoder` is the seam between raw audio and its latent
representation:

- `encode(Producer)` maps a raw audio producer to a latent producer, and `decode(Producer)`
  maps a latent producer back to audio. Both are `Producer` transforms: they describe work,
  they do not perform it.
- `getSampleRate()` is the audio rate and `getLatentSampleRate()` the latent frame rate. Their
  ratio `getSampleRate() / getLatentSampleRate()` is the time compression of the latent — the
  number of audio samples per latent frame (2048 for a 44.1 kHz model with a 2048-sample hop).
  `getLatentSampleRate()` itself is the frame rate, roughly 21.5 latent frames per second of
  audio for that model.
- `getMaximumDuration()` bounds the length of audio the model accepts.

Composition happens in the latent space rather than on raw samples because the latent is
compact (a `(channels, frames)` tensor, typically `(64, 256)` for the production models) and
because the decoder turns *any* point in that space into plausible audio. Mixing two raw
waveforms is a crossfade; blending two latents and decoding yields a new sound that carries
features of both.

---

## 3. Sources, weight vectors and contributions

An `AudioComposer` is constructed with an autoencoder and the length `dim` of the position
vectors it will be given. Each source it holds is a `ComposableAudioFeatures`: an encoded
latent plus a **merge weight vector** of length `dim`.

```java
// LatentAudioCompositionExamplesTest.twoSourceComposer
AudioComposer composer = new AudioComposer(autoencoder, 2, 1L);
composer.addSource(cp(pack(1, 2, 3, 4).reshape(shape(2, 2))), cp(pack(1.0, 0.0)));
composer.addSource(cp(pack(8, 4, 0, -4).reshape(shape(2, 2))), cp(pack(0.0, 1.0)));
```

Every example below uses this composer: source A `(1, 2, 3, 4)` is selected by the first
component of the position, source B `(8, 4, 0, -4)` by the second. The tiny `(2, 2)` "latents"
stand in for real `(64, 256)` ones.

There are three ways to add a source:

- `addSource(features, weights)` takes an already-encoded latent and an explicit weight vector.
  The vector is used **verbatim** (any shape with `dim` elements is flattened; any other length
  throws `IllegalArgumentException`). `setWeights(index, weights)` replaces it later, with the
  same rules.
- `addSource(features)` takes an already-encoded latent and samples its weight vector from a
  Gaussian (`createWeights`). The sampled vector is L2-normalized only when the **static**
  flag `AudioComposer.normalizeWeights` is set. That flag defaults to `true` and is
  process-wide, not a per-composer setting; it never applies to an explicit vector.
- `addAudio(audio)` is `addSource(autoencoder.encode(audio))`. It works only when the
  encoder's output producer reports its shape, because `ComposableAudioFeatures` reads the
  feature shape from that producer to broadcast the weights. `OnnxAutoEncoder.encode` declares
  its output shape; `CompiledModelAutoEncoder.encode` returns a bare lambda producer with no
  shape, so with that encoder `addAudio` fails. A caller using `CompiledModelAutoEncoder`
  evaluates the encode itself, reshapes the latent to two dimensions, and calls
  `addSource(cp(latent))` — which is the form this page uses throughout.

Each source also has a scalar **contribution**, `ComposableAudioFeatures.setContribution`
(default `1.0`), reached through `composer.getSource(index)`.

### The scale of a source

For a position vector `p`, a source's scale is computed by `ComposableAudioFeatures.getScale`:
the dot product of its weight vector with `p`, clamped to be non-negative, then multiplied by
the contribution. As a graph:

```java
// Fragment: ComposableAudioFeatures.getScale
CollectionProducer scale = c(weights).multiply(tiled).traverse(2).sum();
scale = max(scale.reshape(scale.getShape().trim()), c(0));
return contribution == 1.0 ? scale : scale.multiply(c(contribution));
```

The weights are stored as a `(T, B, L)` tensor, one length-`L` vector per latent cell. A
length-`L` vector is broadcast to every `(T, B)` cell (`broadcastWeights`), so **with vector
weights every cell of a source gets the same scale**: the "per-cell scale" is one number per
source. Per-cell variation requires supplying a full `(T, B, L)` weight tensor.

---

## 4. The blend graph, in both modes

`AudioComposer.getInterpolatedLatent(position)` returns the composed latent as a `Producer`.
There are two blend modes.

### The default: weighted sum

By default (`isNormalizedBlend()` is `false`) the composed latent is the weighted **sum**
`Σ sᵢ·xᵢ` of the sources `xᵢ` and their scales `sᵢ`. This is the mode every production caller
uses today — no production code calls `setNormalizedBlend`.

```java
// LatentAudioCompositionExamplesTest.weightedSum
AudioComposer composer = twoSourceComposer(new GainDecoder());

PackedCollection latent = composer.getInterpolatedLatent(cp(pack(0.5, 0.25)))
        .evaluateOptimized();

// 0.5 * (1, 2, 3, 4) + 0.25 * (8, 4, 0, -4)
Assert.assertFalse(composer.isNormalizedBlend());
Assert.assertArrayEquals(new double[] { 2.5, 2.0, 1.5, 1.0 }, latent.toArray(), TOLERANCE);
```

In a weighted sum the magnitude of the latent follows the magnitude of the position: doubling
the position doubles the latent, so a random position can drive the decoded audio towards
silence or clipping.

### Opt-in: the normalized blend

`setNormalizedBlend(true)` makes the composed latent a weighted **mean**. Each scale is clamped
again with `max(getScale(p), 0)`, the scales are summed into `total`, and the result is
`Σ sᵢ·xᵢ / total`. The position then decides only the *proportions* of the sources, and the
latent stays at the level of the sources it mixes:

```java
// LatentAudioCompositionExamplesTest.normalizedBlend
AudioComposer composer = twoSourceComposer(new GainDecoder());
composer.setNormalizedBlend(true);

double[] near = composer.getInterpolatedLatent(cp(pack(0.5, 0.25))).evaluateOptimized().toArray();
double[] far = composer.getInterpolatedLatent(cp(pack(2.0, 1.0))).evaluateOptimized().toArray();

// (2/3) * (1, 2, 3, 4) + (1/3) * (8, 4, 0, -4)
double[] expected = { 10.0 / 3.0, 8.0 / 3.0, 2.0, 4.0 / 3.0 };
```

Both positions select A and B in the ratio 2 : 1, so both compose
`(3.3333, 2.6667, 2.0, 1.3333)`.

**The unselected guard.** A cell where no source has a positive scale has `total == 0`. Rather
than divide by zero, the graph computes `unselected = greaterThan(total, 0, 0, 1)` — `0` where
`total > 0`, `1` where it is zero — and composes

```
Σ (sᵢ + unselected)·xᵢ / (total + unselected·n)
```

which is exactly the weighted mean where `total > 0` and the plain mean `Σ xᵢ / n` where it is
zero:

```java
// LatentAudioCompositionExamplesTest.unselectedGuardIsPlainMean
double[] latent = composer.getInterpolatedLatent(cp(pack(-1.0, -1.0))).evaluateOptimized().toArray();

// ((1, 2, 3, 4) + (8, 4, 0, -4)) / 2
Assert.assertArrayEquals(new double[] { 4.5, 3.0, 1.5, 0.0 }, latent, TOLERANCE);
```

**Negative contributions are excluded.** A negative contribution drives a source's scale below
zero after the dot-product clamp. A negative value cannot be a proportion of a mean, and could
make `total` zero or negative, so the normalized blend's second clamp removes the source from
the mix instead:

```java
// LatentAudioCompositionExamplesTest.negativeContributionIsExcluded
composer.getSource(1).setContribution(-1.0);

double[] latent = composer.getInterpolatedLatent(cp(pack(0.5, 0.25))).evaluateOptimized().toArray();

Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, latent, TOLERANCE);
```

If every source is excluded this way, `total` is zero and the guard again yields the plain
mean (`AudioComposerTest.normalizedBlendWithAllNegativeContributionsIsMean`).

---

## 5. THE FUNDAMENTAL RULE in the small

Nothing in the blend runs in Java. The dot products, clamps, `total`, the `unselected` guard and
the division are all `CollectionProducer` compositions; `getInterpolatedLatent` returns a graph
and the native compiler turns it into a kernel. The only `evaluate` calls are at the top of the
call stack — the production callers and the tests.

Those boundaries evaluate the latent through `evaluateOptimized()`, not plain `.evaluate()`:

```java
// Fragment: WRONG for a normalized blend — every reader embeds the shared reductions
PackedCollection latent = composer.getInterpolatedLatent(cp(position)).evaluate();

// Fragment: RIGHT — Process.optimize isolates the shared reductions first
PackedCollection latent = composer.getInterpolatedLatent(cp(position)).evaluateOptimized();
```

The reason is the shape of the normalized blend's graph. Each `getScale` reduction is read
twice (by `total` and by its own term), and `total` is read by `unselected`, by every term and
by the divisor. `Producer.evaluate()` does not optimize, so it would inline each reduction into
every one of its readers, and the expression grows with the number of sources. In the default
weighted sum each reduction has a single reader, so plain evaluation would compile the same
kernel — but the production callers use `evaluateOptimized()` regardless, so switching the blend
mode never changes which evaluation path is correct.

For the two-source example the two paths still agree numerically; that is what
`LatentAudioCompositionExamplesTest.optimizedMatchesPlainEvaluation` pins. It is the size of
the compiled expression, not the value, that the optimizer protects.

The production boundaries are `AudioGenerator` and `LegacyAudioGenerator`, which call
`getInterpolatedLatent(cp(position)).evaluateOptimized()` and hand the evaluated latent to the
diffusion generator, and `AudioModulator.project`, which evaluates `getResultant` — see the
limitation in the next section.

---

## 6. The decode boundary

`AudioComposer.getResultant(position)` is `autoencoder.decode(getInterpolatedLatent(position))`.
The decode is **where the graph ends**: neither real decoder is a `CollectionProducer`
composition.

- `CompiledModelAutoEncoder.decode` returns a lambda producer that evaluates the latent producer
  it is given and calls `decoder.forward` on the result.
- `OnnxAutoEncoder.decode` (`extern/ml-onnx`) wraps an ONNX Runtime call in `func(…)`.

So the decoded audio is never compiled into the blend kernel. The pattern to copy is the
two-step one shown by the worked example below: evaluate the composed latent through the
optimizer at the boundary, then decode the evaluated latent.

```java
// LatentAudioCompositionExamplesTest.decodeComposedLatent
GainDecoder autoencoder = new GainDecoder();
AudioComposer composer = twoSourceComposer(autoencoder);

PackedCollection latent = composer.getInterpolatedLatent(cp(pack(0.5, 0.25)))
        .evaluateOptimized();
PackedCollection audio = autoencoder.decode(cp(latent)).evaluate();

// GainDecoder halves the latent (2.5, 2, 1.5, 1)
Assert.assertArrayEquals(new double[] { 1.25, 1.0, 0.75, 0.5 }, audio.toArray(), TOLERANCE);
```

`GainDecoder` is a **test device**: its decode is `c(latent).multiply(0.5)`, a graph transform
chosen so the example runs on any backend. Because it is a graph transform,
`composer.getResultant(...).evaluate()` produces the same audio in the test — but that is a
property of the stub, not of real decoders.

**Current limitation: `AudioModulator.project`.** `AudioModulator.project` calls
`composer.getResultant(cp(position)).evaluateOptimized()`. With `CompiledModelAutoEncoder`, the
producer it optimizes is the decoder's lambda, which is not a `Process`; `Process.optimized`
returns a non-`Process` unchanged, and the lambda then evaluates the latent graph with a plain
`evaluate`. The latent is therefore **not** optimized on that path. This is harmless in the
default weighted sum (single-reader reductions) but would bite a normalized blend. Do not copy
`getResultant(...).evaluateOptimized()` as the way to decode a composition; use the two-step
pattern above.

---

## 7. Scope and known limits

- This page covers the **on-platform latent-composition** slice. The text-conditioned diffusion
  path (`AudioGenerator` → `AudioDiffusionGenerator` → `DiffusionSampler`, with an ONNX
  conditioner and autoencoder) needs a model download and owns its own sampling loop; see
  [Sampling Loop Examples](sampling-loop-examples.md). The composed latent enters that path as
  the starting latent of `generateFrom`.
- `addAudio` requires an encoder whose output producer carries a shape (section 3).
- `AudioComposer.normalizeWeights` is a public static flag shared by every composer in the
  process.
- Vector weights give one scale per source; per-cell blending needs a `(T, B, L)` weight tensor.
- `AudioModulator.project` does not optimize the latent graph through a
  `CompiledModelAutoEncoder` decoder (section 6).

---

## Related files

| File | Contents |
|---|---|
| `engine/ml/src/main/java/org/almostrealism/ml/audio/AutoEncoder.java` | The autoencoder contract |
| `studio/compose/src/main/java/org/almostrealism/studio/ml/AudioComposer.java` | Sources, weight sampling, both blend modes |
| `studio/compose/src/main/java/org/almostrealism/studio/ml/ComposableAudioFeatures.java` | Weight broadcasting, `getScale`, contributions |
| `studio/compose/src/main/java/org/almostrealism/studio/ml/CompiledModelAutoEncoder.java` | Lambda-producer encode and decode around compiled models |
| `studio/compose/src/test/java/org/almostrealism/studio/ml/LatentAudioCompositionExamplesTest.java` | The examples on this page |
| `studio/compose/src/test/java/org/almostrealism/studio/ml/AudioComposerTest.java` | Edge cases of the blend contract |
