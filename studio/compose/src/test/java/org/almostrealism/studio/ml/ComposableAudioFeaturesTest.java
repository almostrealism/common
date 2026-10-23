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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;

/**
 * Tests that {@link ComposableAudioFeatures} scales each feature cell by the clamped
 * dot product of that cell's own weight vector with the value vector, and by the
 * source's blend contribution.
 */
public class ComposableAudioFeaturesTest extends TestSuiteBase {
	/**
	 * Expected resultant of {@link #featureData()} with {@link #weights()} for
	 * {@link #value()}: the per-cell dot products are {@code 3, 2, 5, 6, -2, 1}, the
	 * negative one is clamped to zero, and each scales the matching feature value.
	 */
	private static final double[] EXPECTED = { 3, 4, 15, 24, 0, 6 };

	/**
	 * Each cell is scaled by the dot product of its own weight vector with the value.
	 */
	@Test(timeout = 60000)
	public void perCellDotProduct() {
		assertValues(1.0, EXPECTED, resultant(features()));
	}

	/**
	 * The contribution multiplies the resultant and survives replacing the weights.
	 */
	@Test(timeout = 60000)
	public void contributionScalesResultant() {
		ComposableAudioFeatures features = new ComposableAudioFeatures(
				featureData(), cp(pack(1, 0)).repeat(2).repeat(3));
		features.setContribution(0.5);
		features.setWeights(weights());

		Assert.assertEquals(0.5, features.getContribution(), 0.0);
		assertValues(0.5, EXPECTED, resultant(features));
	}

	/**
	 * A single weight vector is broadcast to every feature cell, both when supplied to
	 * the constructor and when it replaces the weights.
	 */
	@Test(timeout = 60000)
	public void weightVectorIsBroadcast() {
		ComposableAudioFeatures features = new ComposableAudioFeatures(featureData(), cp(pack(1, 1)));
		assertValues(5.0, new double[] { 1, 2, 3, 4, 5, 6 }, resultant(features));

		features.setWeights(cp(pack(0, 1)));
		assertValues(2.0, new double[] { 1, 2, 3, 4, 5, 6 }, resultant(features));
	}

	/** Creates features with the per-cell {@link #weights()}. */
	private ComposableAudioFeatures features() {
		return new ComposableAudioFeatures(featureData(), weights());
	}

	/** Returns the feature values, shaped {@code (2, 3)}. */
	private CollectionProducer featureData() {
		return cp(pack(1, 2, 3, 4, 5, 6).reshape(shape(2, 3)));
	}

	/** Returns a different weight vector for every feature cell, shaped {@code (2, 3, 2)}. */
	private CollectionProducer weights() {
		return cp(pack(1, 0, 0, 1, 1, 1, 2, 0, 0, -1, 1, -1).reshape(shape(2, 3, 2)));
	}

	/** Returns the value vector. */
	private CollectionProducer value() {
		return cp(pack(3, 2));
	}

	/** Evaluates the resultant of the features for {@link #value()}. */
	private double[] resultant(ComposableAudioFeatures features) {
		return features.getResultant(value()).get().evaluate().toArray();
	}

	/** Asserts that the actual values equal {@code factor} times the expected values. */
	private void assertValues(double factor, double[] expected, double[] actual) {
		Assert.assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			Assert.assertEquals("index " + i + " of " + Arrays.toString(actual),
					factor * expected[i], actual[i], 1e-5);
		}
	}
}
