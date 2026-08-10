/*
 * Copyright 2024 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.time.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.FirFilterTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for temporal features including filter coefficients.
 */
public class TemporalFeaturesTest extends TestSuiteBase implements FirFilterTestFeatures {

	/**
	 * Computes high-pass filter coefficients from low-pass coefficients by spectral
	 * inversion: the unit impulse at the centre tap, less the low-pass response.
	 *
	 * @param cutoff      the cutoff frequency in Hz
	 * @param sampleRate  the sample rate in Hz
	 * @param filterOrder the filter order
	 * @return a producer for the high-pass coefficients
	 */
	protected CollectionProducer highPassCoefficients(double cutoff, int sampleRate, int filterOrder) {
		return subtract(oneHot(filterOrder + 1, filterOrder / 2),
				cp(referenceLowPassCoefficients(cutoff, sampleRate, filterOrder)));
	}

	/**
	 * Tests low-pass filter coefficient computation.
	 */
	@Test(timeout = 10000)
	public void lowPassCoefficients() {
		int filterOrder = 30;
		int sampleRate = 44100;
		double cutoff = 3000;

		PackedCollection coefficients = referenceLowPassCoefficients(cutoff, sampleRate, filterOrder);
		PackedCollection result = lowPassCoefficients(c(cutoff), sampleRate, filterOrder).get().evaluate();

		assertEquals(0.0, largestDeviation(coefficients, result));
	}

	/**
	 * Tests low-pass filter coefficients for multiple cutoffs.
	 */
	@Test(timeout = 10000)
	public void lowPassCoefficientsMultiple() {
		int filterOrder = 30;
		int sampleRate = 44100;
		PackedCollection cutoffs = pack(1000, 2000, 3000);

		int count = cutoffs.getShape().getTotalSize();
		int len = filterOrder + 1;

		PackedCollection result = lowPassCoefficients(cp(cutoffs), sampleRate, filterOrder)
				.get().evaluate().reshape(shape(count, len)).traverse(1);

		for (int c = 0; c < count; c++) {
			PackedCollection coefficients = referenceLowPassCoefficients(cutoffs.toDouble(c), sampleRate, filterOrder);

			assertEquals(0.0, largestDeviation(coefficients, result.get(c)));
		}
	}

	/**
	 * Tests high-pass filter coefficients for multiple cutoffs.
	 */
	@Test(timeout = 10000)
	public void highPassCoefficientsMultiple() {
		int filterOrder = 30;
		int sampleRate = 44100;
		PackedCollection cutoffs = pack(1000, 2000, 3000);

		int count = cutoffs.getShape().getTotalSize();
		int len = filterOrder + 1;

		PackedCollection result = highPassCoefficients(cp(cutoffs), sampleRate, filterOrder)
				.get().evaluate().reshape(shape(count, len)).traverse(1);

		for (int c = 0; c < count; c++) {
			PackedCollection coefficients =
					highPassCoefficients(cutoffs.toDouble(c), sampleRate, filterOrder).evaluate();

			assertEquals(0.0, largestDeviation(coefficients, result.get(c)));
		}
	}

	/**
	 * Tests low-pass coefficients with producer arguments.
	 */
	@Test(timeout = 10000)
	public void lowPassCoefficientsArguments() {
		int filterOrder = 30;
		int sampleRate = 44100;
		PackedCollection cutoffs = pack(1000, 2000, 3000);

		PackedCollection result = new PackedCollection(shape(cutoffs.getShape().getTotalSize(), (filterOrder + 1)));
		lowPassCoefficients(
				v(shape(-1), 0), sampleRate, filterOrder)
				.get().into(result.traverse(1)).evaluate(cutoffs);

//		PackedCollection result =
//				lowPassCoefficients(
//					v(shape(1), 0), sampleRate, filterOrder)
//				.get().evaluate(cutoffs);

		PackedCollection rows = result.traverse(1);

		for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {
			PackedCollection coefficients = referenceLowPassCoefficients(cutoffs.toDouble(c), sampleRate, filterOrder);
			PackedCollection resultCoefficients = rows.get(c);

			log(coefficients.toArrayString() + " vs " + resultCoefficients.toArrayString());
			assertEquals(0.0, largestDeviation(coefficients, resultCoefficients));
		}
	}

	/**
	 * Tests coefficient selection based on decision value.
	 */
	@Test(timeout = 25000)
	public void chooseCoefficients() {
		chooseCoefficients(0.1);
		chooseCoefficients(0.9);
	}

	/**
	 * Helper for coefficient selection testing.
	 */
	public void chooseCoefficients(double c) {
		int sampleRate = 44100;
		int filterOrder = 20;

		Producer<PackedCollection> decision = cp(pack(c));
		Producer<PackedCollection> cutoff = c(8000);

		CollectionProducer hpCoefficients =
				highPassCoefficients(cutoff, sampleRate, filterOrder)
						.reshape(1, filterOrder + 1);
		CollectionProducer lpCoefficients =
				lowPassCoefficients(cutoff, sampleRate, filterOrder)
						.reshape(1, filterOrder + 1);

		Producer<PackedCollection> coefficients = choice(2,
				shape(filterOrder + 1),
				decision,
				concat(shape(2, filterOrder + 1), hpCoefficients, lpCoefficients).traverse(1));

		PackedCollection result = coefficients.evaluate();

		PackedCollection expected;

		if (c < 0.5) {
			expected = highPassCoefficients(cutoff, sampleRate, filterOrder).evaluate();
		} else {
			expected = lowPassCoefficients(cutoff, sampleRate, filterOrder).evaluate();
		}

		for (int i = 0; i < filterOrder + 1; i++) {
			assertEquals(expected.valueAt(i), result.valueAt(i));
		}
	}
}
