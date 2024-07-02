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

package org.almostrealism.time.computations.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class TemporalFeaturesTest implements TestFeatures {
	protected double[] lowPassCoefficients(double cutoff, int sampleRate, int filterOrder) {
		double[] coefficients = new double[filterOrder + 1];
		double normalizedCutoff = 2 * cutoff / sampleRate;

		for (int i = 0; i <= filterOrder; i++) {
			if (i == filterOrder / 2) {
				coefficients[i] = normalizedCutoff;
			} else {
				int k = i - filterOrder / 2;
				coefficients[i] = Math.sin(Math.PI * k * normalizedCutoff) / (Math.PI * k);
			}

			// Hamming window
			coefficients[i] *= 0.54 - 0.46 * Math.cos(2 * Math.PI * i / filterOrder);
		}

		return coefficients;
	}

	protected double[] highPassCoefficients(double cutoff, int sampleRate, int filterOrder) {
		double lowPassCoefficients[] = lowPassCoefficients(cutoff, sampleRate, filterOrder);

		double[] highPassCoefficients = new double[filterOrder + 1];
		for (int i = 0; i <= filterOrder; i++) {
			highPassCoefficients[i] = ((i == filterOrder / 2) ? 1.0 : 0.0) - lowPassCoefficients[i];
		}

		return highPassCoefficients;
	}

	@Test
	public void lowPassCoefficients() {
		int filterOrder = 30;
		int sampleRate = 44100;
		double cutoff = 3000;

		double[] coefficients = lowPassCoefficients(cutoff, sampleRate, filterOrder);
		double result[] = lowPassCoefficients(c(cutoff), sampleRate, filterOrder).get().evaluate().toArray();

		for (int i = 0; i < filterOrder + 1; i++) {
			assertEquals(coefficients[i], result[i]);
		}
	}

	@Test
	public void lowPassCoefficientsMultiple() {
		int filterOrder = 30;
		int sampleRate = 44100;
		PackedCollection<?> cutoffs = pack(1000, 2000, 3000);

		PackedCollection<?> result = lowPassCoefficients(cp(cutoffs), sampleRate, filterOrder).get().evaluate();

		int len = filterOrder + 1;

		for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {
			double[] coefficients = lowPassCoefficients(cutoffs.toDouble(c), sampleRate, filterOrder);
			double[] resultCoefficients = result.range(shape(len), c * len).toArray();

			for (int i = 0; i < filterOrder + 1; i++) {
				assertEquals(coefficients[i], resultCoefficients[i]);
			}
		}
	}

	@Test
	public void highPassCoefficientsMultiple() {
		int filterOrder = 30;
		int sampleRate = 44100;
		PackedCollection<?> cutoffs = pack(1000, 2000, 3000);

		PackedCollection<?> result = highPassCoefficients(cp(cutoffs), sampleRate, filterOrder).get().evaluate();

		int len = filterOrder + 1;

		for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {
			double[] coefficients = highPassCoefficients(cutoffs.toDouble(c), sampleRate, filterOrder);
			double[] resultCoefficients = result.range(shape(len), c * len).toArray();

			for (int i = 0; i < filterOrder + 1; i++) {
				assertEquals(coefficients[i], resultCoefficients[i]);
			}
		}
	}

	@Test
	public void lowPassCoefficientsArguments() {
		int filterOrder = 30;
		int sampleRate = 44100;
		PackedCollection<?> cutoffs = pack(1000, 2000, 3000);

		PackedCollection<?> result = new PackedCollection<>(shape(cutoffs.getShape().getTotalSize(), (filterOrder + 1)));
		lowPassCoefficients(
				v(shape(1), 0), sampleRate, filterOrder)
				.get().into(result.traverse(1)).evaluate(cutoffs);

//		PackedCollection<?> result =
//				lowPassCoefficients(
//					v(shape(1), 0), sampleRate, filterOrder)
//				.get().evaluate(cutoffs);

		int len = filterOrder + 1;

		for (int c = 0; c < cutoffs.getShape().getTotalSize(); c++) {
			double[] coefficients = lowPassCoefficients(cutoffs.toDouble(c), sampleRate, filterOrder);
			double[] resultCoefficients = result.range(shape(len), c * len).toArray();

			for (int i = 0; i < filterOrder + 1; i++) {
				log(coefficients[i] + " vs " + resultCoefficients[i]);
				assertEquals(coefficients[i], resultCoefficients[i]);
			}
		}
	}
}
