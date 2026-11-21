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

	/** Dimensionality of the merge weight vectors of the indicator-source tests. */
	private static final int INDICATOR_DIM = 4;

	/** Number of rows in the test feature tensors. */
	private static final int ROWS = 2;

	/** Number of columns in the test feature tensors. */
	private static final int COLS = 3;

	/** Values of the first test source, used to compute expected results. */
	private static final double[] SOURCE_A = { 1, 2, 3, 4, 5, 6 };

	/** Values of the second test source, used to compute expected results. */
	private static final double[] SOURCE_B = { -2, 0.5, 7, 1, -3, 10 };

	/** Values of the third test source, used to compute expected results. */
	private static final double[] SOURCE_C = { 0.5, -4, 2, 8, 1, -6 };

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
	 * A normalized blend gives each source the softmax share of its score, so explicit
	 * weights compose deterministic proportions in which the higher-scoring source has the
	 * larger share and the shares sum to one; positions that differ only in length scale
	 * the scores and so sharpen or flatten the same ordering.
	 */
	@Test(timeout = 60000)
	public void normalizedBlendIsWeightedMean() {
		AudioComposer composer = composer(17L);
		composer.setNormalizedBlend(true);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));

		assertValues(expected(softmax(2.0, 0.5)), latent(composer, 2.0, 0.5));
		assertValues(expected(softmax(20.0, 5.0)), latent(composer, 20.0, 5.0));
		assertValues(expected(softmax(0.2, 0.05)), latent(composer, 0.2, 0.05));
	}

	/**
	 * In a normalized blend, a position at which no source has a positive score still mixes
	 * both sources in the proportions of their scores, rather than falling back to a fixed
	 * mean; only equal scores give the even mix.
	 */
	@Test(timeout = 60000)
	public void normalizedBlendWithNoSelectedSourceIsMean() {
		AudioComposer composer = composer(19L);
		composer.setNormalizedBlend(true);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));

		assertValues(expected(softmax(-1.0, -3.0)), latent(composer, -1.0, -3.0));
		assertValues(expected(0.5, 0.5), latent(composer, -2.0, -2.0));
	}

	/**
	 * In a normalized blend, a position very close to the origin composes nearly the even
	 * mix, approached continuously, and a negative score reduces a source's share without
	 * removing it from the blend.
	 */
	@Test(timeout = 60000)
	public void normalizedBlendNearOriginKeepsProportions() {
		AudioComposer composer = composer(21L);
		composer.setNormalizedBlend(true);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));

		assertValues(expected(0.5, 0.5), latent(composer, 2e-12, 5e-13));
		assertValues(expected(softmax(1e-12, -1.0)), latent(composer, 1e-12, -1.0));
		assertValues(expected(softmax(-1.0, 1e-12)), latent(composer, -1.0, 1e-12));
	}

	/**
	 * In a normalized blend, random weights keep the composed latent within the range of
	 * the sources at every position, however far the position is from the origin.
	 */
	@Test(timeout = 60000)
	public void normalizedBlendStaysWithinSources() {
		Random positions = new Random(23L);

		for (int i = 0; i < 8; i++) {
			AudioComposer composer = composer(100L + i);
			composer.setNormalizedBlend(true);
			composer.addSource(sourceA());
			composer.addSource(sourceB());

			double scale = Math.pow(10, positions.nextInt(5) - 2);
			double[] actual = latent(composer,
					scale * positions.nextGaussian(), scale * positions.nextGaussian());

			for (int j = 0; j < actual.length; j++) {
				double low = Math.min(SOURCE_A[j], SOURCE_B[j]);
				double high = Math.max(SOURCE_A[j], SOURCE_B[j]);
				Assert.assertTrue("Cell " + j + " = " + actual[j] + " is outside [" + low + ", " + high + "]",
						actual[j] >= low - 1e-4 && actual[j] <= high + 1e-4);
			}
		}
	}

	/**
	 * In a normalized blend, a source whose scale is driven negative by its blend
	 * contribution is excluded from the proportions: the composed latent is the remaining
	 * selected source alone, not a mix pulled towards the negative source, and there is no
	 * division by a negative or zero total.
	 */
	@Test(timeout = 60000)
	public void normalizedBlendExcludesNegativeContribution() {
		AudioComposer composer = composer(29L);
		composer.setNormalizedBlend(true);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));
		composer.getSource(1).setContribution(-1.0);

		assertValues(expected(1.0, 0.0), latent(composer, 2.0, 0.5));
	}

	/**
	 * In a normalized blend, when every source's scale is driven non-positive by a
	 * negative contribution the total is zero and the composer falls back to the plain
	 * mean rather than dividing by a zero or negative total and producing NaN.
	 */
	@Test(timeout = 60000)
	public void normalizedBlendWithAllNegativeContributionsIsMean() {
		AudioComposer composer = composer(31L);
		composer.setNormalizedBlend(true);
		composer.addSource(sourceA(), weightVector(1.0, 0.0));
		composer.addSource(sourceB(), weightVector(0.0, 1.0));
		composer.getSource(0).setContribution(-1.0);
		composer.getSource(1).setContribution(-1.0);

		assertValues(expected(0.5, 0.5), latent(composer, 2.0, 0.5));
	}

	/**
	 * In a normalized blend with the default random weights, every random position mixes
	 * every source: each share is strictly positive, the shares sum to one, and no two
	 * positions compose the same latent. Checked for two and three sources, whose shares
	 * are read directly off {@link #indicatorSource(int) indicator sources}.
	 */
	@Test(timeout = 120000)
	public void randomPositionsMixEverySource() {
		for (int count = 2; count <= 3; count++) {
			AudioComposer composer = new AudioComposer(new StubAutoEncoder(), INDICATOR_DIM, 37L + count);
			composer.setNormalizedBlend(true);
			for (int i = 0; i < count; i++) {
				composer.addSource(indicatorSource(i));
			}

			Random positions = new Random(41L + count);
			List<double[]> mixes = new ArrayList<>();
			for (int p = 0; p < 12; p++) {
				double[] latent = latent(composer, gaussian(positions));

				Assert.assertEquals("Shares of " + Arrays.toString(latent) + " do not sum to one",
						1.0, latent[0], 1e-5);
				double total = 0;
				for (int i = 0; i < count; i++) {
					double share = latent[1 + i];
					Assert.assertTrue("Source " + i + " of " + count + " has share " + share,
							share > 1e-6);
					total += share;
				}
				Assert.assertEquals(1.0, total, 1e-5);

				for (double[] previous : mixes) {
					Assert.assertTrue("Positions composed the same mix " + Arrays.toString(latent),
							maxDifference(previous, latent) > 1e-4);
				}
				mixes.add(latent);
			}
		}
	}

	/**
	 * In a normalized blend of three sources with random weights, every cell of the composed
	 * latent stays within the range of the sources at that cell, so its magnitude never
	 * exceeds the largest source magnitude, at positions of very different lengths.
	 */
	@Test(timeout = 120000)
	public void threeSourceBlendStaysWithinSources() {
		double[][] sources = { SOURCE_A, SOURCE_B, SOURCE_C };
		double largest = Arrays.stream(sources).mapToDouble(AudioComposerTest::norm).max().orElse(0.0);
		Random positions = new Random(43L);

		for (int i = 0; i < 8; i++) {
			AudioComposer composer = composer(200L + i);
			composer.setNormalizedBlend(true);
			composer.addSource(sourceA());
			composer.addSource(sourceB());
			composer.addSource(sourceC());

			double scale = Math.pow(10, positions.nextInt(5) - 2);
			double[] actual = latent(composer,
					scale * positions.nextGaussian(), scale * positions.nextGaussian());

			for (int j = 0; j < actual.length; j++) {
				double low = Math.min(SOURCE_A[j], Math.min(SOURCE_B[j], SOURCE_C[j]));
				double high = Math.max(SOURCE_A[j], Math.max(SOURCE_B[j], SOURCE_C[j]));
				Assert.assertTrue("Cell " + j + " = " + actual[j] + " is outside [" + low + ", " + high + "]",
						actual[j] >= low - 1e-4 && actual[j] <= high + 1e-4);
			}

			Assert.assertTrue(norm(actual) <= largest + 1e-4);
		}
	}

	/**
	 * In a normalized blend, a contribution of zero excludes a source at every position,
	 * while the remaining sources keep positive shares that sum to one.
	 */
	@Test(timeout = 60000)
	public void zeroContributionExcludesSource() {
		AudioComposer composer = new AudioComposer(new StubAutoEncoder(), INDICATOR_DIM, 47L);
		composer.setNormalizedBlend(true);
		for (int i = 0; i < 3; i++) {
			composer.addSource(indicatorSource(i));
		}
		composer.getSource(1).setContribution(0.0);

		Random positions = new Random(53L);
		for (int p = 0; p < 4; p++) {
			double[] latent = latent(composer, gaussian(positions));

			Assert.assertEquals(1.0, latent[0], 1e-5);
			Assert.assertEquals(0.0, latent[2], 1e-9);
			Assert.assertTrue(latent[1] > 1e-6);
			Assert.assertTrue(latent[3] > 1e-6);
			Assert.assertEquals(1.0, latent[1] + latent[3], 1e-5);
		}
	}

	/**
	 * In a normalized blend, a canonical merge position, built from explicit orthogonal
	 * weight vectors and a position whose components are the per-source contributions,
	 * gives deterministic shares in the order of those contributions; setting the same
	 * contributions on the sources and evaluating at the origin gives shares exactly in
	 * their ratio.
	 */
	@Test(timeout = 60000)
	public void mergePositionFollowsContributions() {
		double[] contributions = { 0.3, 0.6, 0.1 };
		List<Producer<PackedCollection>> axes = List.of(
				cp(pack(1.0, 0.0, 0.0, 0.0)), cp(pack(0.0, 1.0, 0.0, 0.0)), cp(pack(0.0, 0.0, 1.0, 0.0)));

		AudioComposer composer = new AudioComposer(new StubAutoEncoder(), INDICATOR_DIM, 59L);
		composer.setNormalizedBlend(true);
		for (int i = 0; i < 3; i++) {
			composer.addSource(indicatorSource(i), axes.get(i));
		}

		Producer<PackedCollection> mergePosition = cp(pack(0.3, 0.6, 0.1, 0.0));
		double[] merged = latent(composer, mergePosition);
		Assert.assertTrue(merged[2] > merged[1] && merged[1] > merged[3] && merged[3] > 0);
		Assert.assertEquals(1.0, merged[1] + merged[2] + merged[3], 1e-5);
		assertValues(merged, latent(composer, mergePosition));

		for (int i = 0; i < 3; i++) {
			composer.getSource(i).setContribution(contributions[i]);
		}

		double[] weighted = latent(composer, cp(pack(0.0, 0.0, 0.0, 0.0)));
		for (int i = 0; i < 3; i++) {
			Assert.assertEquals(contributions[i], weighted[1 + i], 1e-5);
		}
	}

	/** A non-positive blend temperature is rejected. */
	@Test(timeout = 60000)
	public void nonPositiveTemperatureIsRejected() {
		AudioComposer composer = composer(61L);
		Assert.assertEquals(1.0, composer.getBlendTemperature(), 0.0);

		try {
			composer.setBlendTemperature(0.0);
			Assert.fail("Expected a zero blend temperature to be rejected");
		} catch (IllegalArgumentException e) {
			Assert.assertEquals(1.0, composer.getBlendTemperature(), 0.0);
		}
	}

	/**
	 * Returns a source whose first cell is {@code 1}, whose cell {@code 1 + index} is
	 * {@code 1}, and whose other cells are {@code 0}. In a normalized blend of such sources
	 * the composed latent's first cell is the total of the shares and its cell
	 * {@code 1 + index} is the share of source {@code index}.
	 */
	private Producer<PackedCollection> indicatorSource(int index) {
		PackedCollection values = switch (index) {
			case 0 -> pack(1, 1, 0, 0, 0, 0);
			case 1 -> pack(1, 0, 1, 0, 0, 0);
			case 2 -> pack(1, 0, 0, 1, 0, 0);
			default -> throw new IllegalArgumentException("No indicator source " + index);
		};

		return cp(values.reshape(shape(ROWS, COLS)));
	}

	/**
	 * Returns a length-{@link #INDICATOR_DIM} position with independent unit Gaussian
	 * components, sampled from the given random source.
	 */
	private Producer<PackedCollection> gaussian(Random random) {
		return cp(randn(shape(INDICATOR_DIM), 0.0, 1.0, random).evaluate());
	}

	/** Returns the largest absolute element-wise difference of two arrays. */
	private static double maxDifference(double[] a, double[] b) {
		double difference = 0;
		for (int i = 0; i < a.length; i++) {
			difference = Math.max(difference, Math.abs(a[i] - b[i]));
		}

		return difference;
	}

	/** Returns the Euclidean norm of the given values. */
	private static double norm(double[] values) {
		return Math.sqrt(Arrays.stream(values).map(v -> v * v).sum());
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

	/**
	 * Evaluates the interpolated latent of the composer at the position {@code (x, y)}
	 * through the optimizer, as the production callers do, so a normalized blend's shared
	 * reductions are isolated rather than embedded once per consumer.
	 */
	private double[] latent(AudioComposer composer, double x, double y) {
		return latent(composer, cp(pack(x, y)));
	}

	/** Evaluates the interpolated latent of the composer at the given position. */
	private double[] latent(AudioComposer composer, Producer<PackedCollection> position) {
		return composer.getInterpolatedLatent(position).evaluateOptimized().toArray();
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

	/** Returns the third test source, holding {@link #SOURCE_C} in shape {@code (ROWS, COLS)}. */
	private Producer<PackedCollection> sourceC() {
		return cp(pack(0.5, -4, 2, 8, 1, -6).reshape(shape(ROWS, COLS)));
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

	/** Computes {@code shares[0] * SOURCE_A + shares[1] * SOURCE_B} element-wise. */
	private static double[] expected(double[] shares) {
		return expected(shares[0], shares[1]);
	}

	/**
	 * Computes the softmax of the given scores at unit temperature, the shares a normalized
	 * blend gives sources with unit contributions.
	 */
	private static double[] softmax(double... scores) {
		double reference = Arrays.stream(scores).max().orElse(0.0);
		double[] shares = Arrays.stream(scores).map(s -> Math.exp(s - reference)).toArray();
		double total = Arrays.stream(shares).sum();
		return Arrays.stream(shares).map(s -> s / total).toArray();
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
