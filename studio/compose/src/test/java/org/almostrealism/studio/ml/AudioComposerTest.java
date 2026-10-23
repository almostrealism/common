/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.audio.AutoEncoder;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Tests for the per-source merge controls of {@link AudioComposer}: explicit merge
 * weight vectors, weight overrides, and scalar blend contributions, together with a
 * guard that the default random-weight path is unchanged.
 */
public class AudioComposerTest extends TestSuiteBase {
	/** Dimensionality of the merge weight vectors used by these tests. */
	private static final int DIM = 2;

	/** Number of rows in the test feature tensors. */
	private static final int ROWS = 2;

	/** Number of columns in the test feature tensors. */
	private static final int COLS = 3;

	/** Values of the first test source, used to compute expected results. */
	private static final double[] SOURCE_A = { 1, 2, 3, 4, 5, 6 };

	/** Values of the second test source, used to compute expected results. */
	private static final double[] SOURCE_B = { -2, 0.5, 7, 1, -3, 10 };

	/**
	 * Two sources with orthogonal unit weight vectors are each scaled by the
	 * matching component of the position vector, independent of the seed.
	 */
	@Test(timeout = 60000)
	public void explicitWeightsAreDeterministic() {
		double[] first = composeExplicit(1L);
		double[] second = composeExplicit(987654321L);

		assertValues(expected(2.0, 0.5), first);
		assertValues(first, second);
	}

	/**
	 * A negative dot product between the weight vector and the position vector
	 * removes the source from the composition.
	 */
	@Test(timeout = 60000)
	public void negativeScaleIsClamped() {
		AudioComposer composer = composer(3L);
		composer.addSource(sourceA(), weightVector(-1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));

		assertValues(expected(0.0, 1.5), latent(composer, 2.0, 1.5));
	}

	/**
	 * Per-source contributions weight each source's term in the composed latent.
	 */
	@Test(timeout = 60000)
	public void contributionWeightsSources() {
		AudioComposer composer = composer(5L);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(1.0, 0.0));
		composer.getSource(0).setContribution(0.75);
		composer.getSource(1).setContribution(0.25);

		Assert.assertEquals(2, composer.getSourceCount());
		Assert.assertEquals(0.75, composer.getSource(0).getContribution(), 0.0);
		assertValues(expected(0.75, 0.25), latent(composer, 1.0, 0.0));
	}

	/**
	 * Overriding the weights of an already-added source changes its scale and
	 * preserves its contribution.
	 */
	@Test(timeout = 60000)
	public void setWeightsOverridesSource() {
		AudioComposer composer = composer(7L);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(1.0, 0.0));
		composer.getSource(0).setContribution(0.5);

		composer.setWeights(0, weightVector(0.0, 1.0));

		Assert.assertEquals(0.5, composer.getSource(0).getContribution(), 0.0);
		assertValues(expected(1.5, 2.0), latent(composer, 2.0, 3.0));
	}

	/**
	 * A weight vector shaped with one more axis than the features, but holding only
	 * {@code dim} elements, is still broadcast to every cell rather than being taken as
	 * a per-cell weight tensor, both when added and when overridden.
	 */
	@Test(timeout = 60000)
	public void multiAxisWeightVectorIsBroadcast() {
		AudioComposer composer = composer(15L);
		composer.addSource(sourceA(), cp(pack(1.0, 0.0).reshape(shape(1, 1, DIM))));
		composer.addSource(sourceB(), weightVector(1.0, 0.0));
		composer.setWeights(1, cp(pack(0.0, 1.0).reshape(shape(1, 1, DIM))));

		assertValues(expected(2.0, 0.5), latent(composer, 2.0, 0.5));
	}

	/**
	 * Explicit weights that do not match the embedding dimension are rejected.
	 */
	@Test(timeout = 60000)
	public void wrongWeightLengthIsRejected() {
		AudioComposer composer = composer(9L);

		try {
			composer.addSource(sourceA(), cp(pack(1.0, 0.0, 0.0)));
			Assert.fail("Expected a weight vector of the wrong length to be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(0, composer.getSourceCount());
		}
	}

	/**
	 * Overriding a source's weights with a vector of the wrong length is rejected and
	 * leaves the source's previous weights in effect.
	 */
	@Test(timeout = 60000)
	public void wrongOverrideLengthIsRejected() {
		AudioComposer composer = composer(11L);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));

		try {
			composer.setWeights(1, cp(pack(1.0)));
			Assert.fail("Expected a weight override of the wrong length to be rejected");
		} catch (IllegalArgumentException e) {
			assertValues(expected(2.0, 0.5), latent(composer, 2.0, 0.5));
		}
	}

	/**
	 * Overriding the weights of a source that does not exist is rejected.
	 */
	@Test(timeout = 60000)
	public void overrideOfMissingSourceIsRejected() {
		AudioComposer composer = composer(13L);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));

		try {
			composer.setWeights(1, weightVector(0.0, 1.0));
			Assert.fail("Expected an override of a missing source to be rejected");
		} catch (IndexOutOfBoundsException e) {
			Assert.assertEquals(1, composer.getSourceCount());
			assertValues(expected(2.0, 0.0), latent(composer, 2.0, 0.5));
		}
	}

	/**
	 * With no explicit weights or contributions, the composer reproduces the
	 * random-weight composition for a given seed: Gaussian vectors drawn from a
	 * {@link Random} with that seed, normalized, and broadcast over the features.
	 */
	@Test(timeout = 60000)
	public void defaultPathMatchesRandomWeights() {
		long seed = 42L;
		double x = 0.3;
		double y = 0.9;
		double[] actual = composeRandom(seed, x, y);

		Random random = new Random(seed);
		List<Producer<?>> terms = new ArrayList<>();
		for (Producer<PackedCollection> source : List.of(sourceA(), sourceB())) {
			CollectionProducer rand = randn(shape(DIM), 1.0, 1.0, random);
			if (AudioComposer.normalizeWeights)
				rand = normalize(rand);
			terms.add(new ComposableAudioFeatures(source,
					rand.repeat(ROWS).repeat(COLS)).getResultant(cp(pack(x, y))));
		}

		double[] reference = add(terms).get().evaluate().toArray();
		assertValues(reference, actual);
		assertValues(actual, composeRandom(seed, x, y));
	}

	/**
	 * Composes the two test sources with orthogonal unit weight vectors using the
	 * given seed.
	 */
	private double[] composeExplicit(long seed) {
		AudioComposer composer = composer(seed);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));
		return latent(composer, 2.0, 0.5);
	}

	/** Creates a composer with a stub autoencoder that is never decoded. */
	private AudioComposer composer(long seed) {
		return new AudioComposer(new StubAutoEncoder(), DIM, seed);
	}

	/** Evaluates the interpolated latent of the composer at the position {@code (x, y)}. */
	private double[] latent(AudioComposer composer, double x, double y) {
		return composer.getInterpolatedLatent(cp(pack(x, y))).get().evaluate().toArray();
	}

	/**
	 * Composes the two test sources with randomly sampled weights using the given
	 * seed, evaluated at the position {@code (x, y)}.
	 */
	private double[] composeRandom(long seed, double x, double y) {
		AudioComposer composer = composer(seed);
		composer.addSource(sourceA());
		composer.addSource(sourceB());
		return latent(composer, x, y);
	}

	/** Returns the first test source, holding {@link #SOURCE_A} in shape {@code (ROWS, COLS)}. */
	private Producer<PackedCollection> sourceA() {
		return cp(pack(1, 2, 3, 4, 5, 6).reshape(shape(ROWS, COLS)));
	}

	/** Returns the second test source, holding {@link #SOURCE_B} in shape {@code (ROWS, COLS)}. */
	private Producer<PackedCollection> sourceB() {
		return cp(pack(-2, 0.5, 7, 1, -3, 10).reshape(shape(ROWS, COLS)));
	}

	/** Returns the merge weight vector {@code (x, y)}. */
	private Producer<PackedCollection> weightVector(double x, double y) {
		return cp(pack(x, y));
	}

	/** Computes {@code a * SOURCE_A + b * SOURCE_B} element-wise. */
	private static double[] expected(double a, double b) {
		double[] result = new double[SOURCE_A.length];
		for (int i = 0; i < result.length; i++) {
			result[i] = a * SOURCE_A[i] + b * SOURCE_B[i];
		}

		return result;
	}

	/** Asserts that the actual values equal the expected values. */
	private static void assertValues(double[] expected, double[] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("index " + i + " of " + Arrays.toString(actual),
					expected[i], actual[i], 1e-5);
		}
	}

	/**
	 * An {@link AutoEncoder} that is never used to encode or decode, since these
	 * tests only evaluate the interpolated latent.
	 */
	private static class StubAutoEncoder implements AutoEncoder {
		@Override
		public double getSampleRate() { return 44100; }

		@Override
		public double getLatentSampleRate() { return 21.5; }

		@Override
		public double getMaximumDuration() { return 1.0; }

		@Override
		public Producer<PackedCollection> encode(Producer<PackedCollection> input) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Producer<PackedCollection> decode(Producer<PackedCollection> latent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void destroy() { }
	}
}
