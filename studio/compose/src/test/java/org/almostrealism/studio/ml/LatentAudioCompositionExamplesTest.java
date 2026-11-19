/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.studio.ml;

import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.AutoEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Worked examples for the latent audio composition internals page
 * ({@code docs/internals/latent-audio-composition.md}). Every code snippet on that page is
 * taken from a method of this class, and every number the page prints is an assertion here,
 * so the page cannot drift from the behaviour of {@link AudioComposer} and
 * {@link ComposableAudioFeatures} without this test failing.
 *
 * <p>The examples use {@link GainDecoder}, a synthetic {@link AutoEncoder} whose decode is a
 * small {@code CollectionProducer} transform, so they run on any backend with no model
 * download. Real decoders are host-side model calls rather than graph transforms.</p>
 */
public class LatentAudioCompositionExamplesTest extends TestSuiteBase {
	/** Tolerance for comparing composed latents with the values printed on the page. */
	private static final double TOLERANCE = 1e-5;

	/**
	 * The default mode is the weighted sum: each source is scaled by the dot product of its
	 * weight vector with the position, and the scaled sources are added.
	 */
	@Test(timeout = 60000)
	public void weightedSum() {
		AudioComposer composer = twoSourceComposer(new GainDecoder());

		PackedCollection latent = composer.getInterpolatedLatent(cp(pack(0.5, 0.25)))
				.evaluateOptimized();

		// 0.5 * (1, 2, 3, 4) + 0.25 * (8, 4, 0, -4)
		Assert.assertFalse(composer.isNormalizedBlend());
		Assert.assertArrayEquals(new double[] { 2.5, 2.0, 1.5, 1.0 }, latent.toArray(), TOLERANCE);
	}

	/**
	 * The normalized blend divides the scales by their total, so the position decides only
	 * the proportions of the sources: (0.5, 0.25) and (2, 1) compose the same latent.
	 */
	@Test(timeout = 60000)
	public void normalizedBlend() {
		AudioComposer composer = twoSourceComposer(new GainDecoder());
		composer.setNormalizedBlend(true);

		double[] near = composer.getInterpolatedLatent(cp(pack(0.5, 0.25))).evaluateOptimized().toArray();
		double[] far = composer.getInterpolatedLatent(cp(pack(2.0, 1.0))).evaluateOptimized().toArray();

		// (2/3) * (1, 2, 3, 4) + (1/3) * (8, 4, 0, -4)
		double[] expected = { 10.0 / 3.0, 8.0 / 3.0, 2.0, 4.0 / 3.0 };
		Assert.assertArrayEquals(expected, near, TOLERANCE);
		Assert.assertArrayEquals(expected, far, TOLERANCE);
	}

	/**
	 * When no source has a positive scale the unselected guard makes the normalized blend
	 * the plain mean of the sources rather than a division by zero.
	 */
	@Test(timeout = 60000)
	public void unselectedGuardIsPlainMean() {
		AudioComposer composer = twoSourceComposer(new GainDecoder());
		composer.setNormalizedBlend(true);

		double[] latent = composer.getInterpolatedLatent(cp(pack(-1.0, -1.0))).evaluateOptimized().toArray();

		// ((1, 2, 3, 4) + (8, 4, 0, -4)) / 2
		Assert.assertArrayEquals(new double[] { 4.5, 3.0, 1.5, 0.0 }, latent, TOLERANCE);
	}

	/**
	 * A source whose contribution is negative is excluded from the normalized blend, so the
	 * composed latent is the remaining source alone.
	 */
	@Test(timeout = 60000)
	public void negativeContributionIsExcluded() {
		AudioComposer composer = twoSourceComposer(new GainDecoder());
		composer.setNormalizedBlend(true);
		composer.getSource(1).setContribution(-1.0);

		double[] latent = composer.getInterpolatedLatent(cp(pack(0.5, 0.25))).evaluateOptimized().toArray();

		Assert.assertArrayEquals(new double[] { 1.0, 2.0, 3.0, 4.0 }, latent, TOLERANCE);
	}

	/**
	 * Decoding ends the graph. The composed latent is evaluated through the optimizer at the
	 * boundary and the evaluated latent is then decoded; with a decoder that is itself a
	 * graph transform, {@link AudioComposer#getResultant(Producer)} gives the same audio.
	 */
	@Test(timeout = 60000)
	public void decodeComposedLatent() {
		GainDecoder autoencoder = new GainDecoder();
		AudioComposer composer = twoSourceComposer(autoencoder);

		PackedCollection latent = composer.getInterpolatedLatent(cp(pack(0.5, 0.25)))
				.evaluateOptimized();
		PackedCollection audio = autoencoder.decode(cp(latent)).evaluate();

		// GainDecoder halves the latent (2.5, 2, 1.5, 1)
		Assert.assertArrayEquals(new double[] { 1.25, 1.0, 0.75, 0.5 }, audio.toArray(), TOLERANCE);
		Assert.assertArrayEquals(audio.toArray(),
				composer.getResultant(cp(pack(0.5, 0.25))).evaluate().toArray(), TOLERANCE);
	}

	/**
	 * For a graph this small, plain evaluation and optimized evaluation of the normalized
	 * blend agree; the optimizer is required because the blend's shared reductions would
	 * otherwise be embedded in each of their readers, which grows with the source count.
	 */
	@Test(timeout = 60000)
	public void optimizedMatchesPlainEvaluation() {
		AudioComposer composer = twoSourceComposer(new GainDecoder());
		composer.setNormalizedBlend(true);
		Producer<PackedCollection> position = cp(pack(0.5, 0.25));

		double[] plain = composer.getInterpolatedLatent(position).evaluate().toArray();
		double[] optimized = composer.getInterpolatedLatent(position).evaluateOptimized().toArray();

		Assert.assertArrayEquals(plain, optimized, TOLERANCE);
	}

	/**
	 * Creates the page's two-source composer: source A {@code (1, 2, 3, 4)} selected by the
	 * first position component, source B {@code (8, 4, 0, -4)} by the second, both shaped
	 * {@code (2, 2)}.
	 *
	 * @param autoencoder the autoencoder the composer decodes with
	 * @return a composer holding the two sources with explicit orthogonal weights
	 */
	private AudioComposer twoSourceComposer(AutoEncoder autoencoder) {
		AudioComposer composer = new AudioComposer(autoencoder, 2, 1L);
		composer.addSource(cp(pack(1, 2, 3, 4).reshape(shape(2, 2))), cp(pack(1.0, 0.0)));
		composer.addSource(cp(pack(8, 4, 0, -4).reshape(shape(2, 2))), cp(pack(0.0, 1.0)));
		return composer;
	}

	/**
	 * A synthetic {@link AutoEncoder} for the examples. Its decode multiplies the latent by
	 * one half, a {@code CollectionProducer} transform that runs on any backend. This is a
	 * test device: real decoders evaluate the latent and call a model on the host. The
	 * examples add already-encoded latents, so encoding is not supported.
	 */
	private static class GainDecoder implements AutoEncoder, CodeFeatures {
		@Override
		public double getLatentSampleRate() { return 44100.0 / 2048.0; }

		@Override
		public double getSampleRate() { return 44100.0; }

		@Override
		public double getMaximumDuration() { return 2.0; }

		@Override
		public Producer<PackedCollection> decode(Producer<PackedCollection> latent) {
			return c(latent).multiply(0.5);
		}

		@Override
		public Producer<PackedCollection> encode(Producer<PackedCollection> input) {
			throw new UnsupportedOperationException("The examples add encoded latents directly");
		}

		@Override
		public void destroy() { }
	}
}
