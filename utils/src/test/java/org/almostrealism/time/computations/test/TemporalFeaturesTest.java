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

import org.almostrealism.util.TestFeatures;
import org.junit.Test;

public class TemporalFeaturesTest implements TestFeatures {
	@Test
	public void lowPassCoefficients() {
		int filterOrder = 30;
		int sampleRate = 44100;
		double cutoff = 3000;

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

		double result[] = lowPassCoefficients(c(cutoff), sampleRate, filterOrder).get().evaluate().toArray();

		for (int i = 0; i < filterOrder + 1; i++) {
			assertEquals(coefficients[i], result[i]);
		}
	}
}
