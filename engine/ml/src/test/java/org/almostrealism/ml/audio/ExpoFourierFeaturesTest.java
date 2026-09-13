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

package org.almostrealism.ml.audio;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies the deterministic timestep features of
 * {@link DiffusionTransformerFeatures#expoFourierFeatures}: a geometric frequency ladder from the
 * minimum to the maximum frequency and the {@code [cos, sin]} embedding of {@code 2 pi t f}.
 */
public class ExpoFourierFeaturesTest extends TestSuiteBase implements DiffusionTransformerFeatures {

	/**
	 * The frequency ladder runs from {@code minFreq} to {@code maxFreq} with uniform log spacing.
	 * The ladder is stored in single precision, so each rung is checked to a relative error.
	 */
	@Test(timeout = 60000)
	public void frequenciesAreGeometric() {
		int half = 4;
		double minFreq = 0.5;
		double maxFreq = 10000.0;
		PackedCollection freqs = evaluate(expoFourierFrequencies(2 * half, minFreq, maxFreq));
		assertEquals(half, freqs.getShape().getTotalSize());

		for (int i = 0; i < half; i++) {
			double expected = Math.exp(Math.log(minFreq) + (double) i / (half - 1) * (Math.log(maxFreq) - Math.log(minFreq)));
			double relativeError = Math.abs(freqs.toDouble(i) - expected) / expected;
			assertTrue("rung " + i + " relative error " + relativeError, relativeError < 1e-5);
		}
	}

	/**
	 * The feature block maps each timestep to {@code [cos(2 pi t f_k), sin(2 pi t f_k)]} for the ladder
	 * frequencies, matching a host-side reference. The kernel evaluates the phase in single precision,
	 * so the permitted error grows with the phase: a phase of several hundred radians carries a
	 * rounding error of a few times {@code ulp(1) * phase}, which the tolerance allows for.
	 */
	@Test(timeout = 120000)
	public void featuresMatchReference() {
		int batch = 3;
		int dim = 16;
		double minFreq = 0.5;
		double maxFreq = 100.0;

		PackedCollection t = PackedCollection.of(0.0, 0.37, 0.9).reshape(shape(batch, 1));

		Block block = expoFourierFeatures(batch, dim, minFreq, maxFreq);
		Model model = new Model(shape(batch, 1));
		model.sequential().add(block);
		CompiledModel compiled = model.compile(false);
		PackedCollection out = compiled.forward(t).reshape(shape(batch, dim));

		int half = dim / 2;
		double logMin = Math.log(minFreq);
		double logMax = Math.log(maxFreq);

		for (int b = 0; b < batch; b++) {
			for (int k = 0; k < half; k++) {
				double f = Math.exp(logMin + (double) k / (half - 1) * (logMax - logMin));
				double arg = 2.0 * Math.PI * t.valueAt(b, 0) * f;
				double phaseTolerance = 1e-4 + 8.0 * Math.ulp(1.0f) * Math.abs(arg);
				assertEquals(Math.cos(arg), out.valueAt(b, k), phaseTolerance);
				assertEquals(Math.sin(arg), out.valueAt(b, half + k), phaseTolerance);
			}
		}
	}

	/**
	 * Evaluates a producer at the test boundary.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	private PackedCollection evaluate(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}
}
