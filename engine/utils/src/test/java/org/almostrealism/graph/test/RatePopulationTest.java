/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.graph.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.RatePopulation;
import org.almostrealism.time.SimulationRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.Random;

/**
 * Checks the dynamics of {@link RatePopulation} on synthetic coupling matrices, stepped by
 * {@link SimulationRunner}: the discrete linear solution, the bound on the rates, the row scale
 * and the orientation of the matrix.
 */
public class RatePopulationTest extends TestSuiteBase {
	/** The time step of every case. */
	private static final double DT = 1e-3;

	/** The rate time constant of every case. */
	private static final double TAU = 0.02;

	/**
	 * A population whose activation is the identity, so that one step is a linear map with a
	 * closed form. Its rates are not bounded; the maximum rate it passes is never used.
	 */
	private static class LinearRatePopulation extends RatePopulation {
		/**
		 * @param weights the coupling matrix
		 * @param gain    the global gain
		 */
		LinearRatePopulation(PackedCollection weights, double gain) {
			super(weights, null, gain, DT, TAU, 1.0);
		}

		/** Returns the total input unchanged. */
		@Override
		protected CollectionProducer activate(CollectionProducer x) { return x; }
	}

	/**
	 * With the identity activation and a constant input {@code b}, one step is the linear map
	 * {@code r' = A r + c} with {@code A = decay I + (1 - decay) g W} and
	 * {@code c = (1 - decay) b}. The same map iterated on the host must equal the state after
	 * 5,000 steps of the population. A population that dropped the decay, the gain, the input or
	 * the coupling, or that used the transpose of a non-symmetric {@code W}, would disagree.
	 */
	@Test(timeout = 300000)
	public void closedForm() {
		int n = 4;
		int steps = 5000;
		double gain = 0.5;
		PackedCollection weights = new PackedCollection(shape(n, n)).fill(
				0.2, -0.4, 0.1, 0.0,
				0.3, 0.1, -0.2, 0.5,
				-0.1, 0.0, 0.3, -0.3,
				0.4, 0.2, 0.0, -0.2);
		RatePopulation population = new LinearRatePopulation(weights, gain);
		population.getRates().fill(0.1, 0.7, -0.3, 0.0);
		population.getInput().fill(1.0, -0.5, 0.25, 2.0);
		double[] w = weights.toArray();
		double[] b = population.getInput().toArray();
		double[] r0 = population.getRates().toArray();

		SimulationRunner runner = new SimulationRunner(population, DT);
		runner.run(steps);

		double decay = population.getDecay();
		double[] expected = r0.clone();
		for (int k = 0; k < steps; k++) {
			double[] next = new double[n];
			for (int i = 0; i < n; i++) {
				double coupled = 0.0;
				for (int j = 0; j < n; j++) coupled += w[i * n + j] * expected[j];
				next[i] = decay * expected[i] + (1.0 - decay) * (gain * coupled + b[i]);
			}
			expected = next;
		}

		double[] actual = population.getRates().toArray();
		for (int i = 0; i < n; i++) {
			assertEquals(expected[i], actual[i], 1e-4);
		}

		runner.destroy();
		population.destroy();
		weights.destroy();
	}

	/**
	 * A random 512 x 512 matrix with a large gain, from random initial rates in
	 * {@code [0, rMax]}: every rate recorded over 20,000 steps stays finite and in
	 * {@code [0, rMax]}, because the exponential-Euler update is a convex combination of the
	 * previous rate and a value of the bounded activation.
	 */
	@Test(timeout = 900000)
	public void bounds() {
		int n = 512;
		int steps = 20000;
		int interval = 100;
		double rMax = 100.0;
		Random random = new Random(512);

		PackedCollection weights = rand(shape(n, n), random).multiply(2.0).subtract(1.0).evaluate();
		RatePopulation population = new RatePopulation(weights, null, 50.0, DT, TAU, rMax);
		a(cp(population.getRates()), rand(shape(n), random).multiply(rMax)).get().run();
		a(cp(population.getInput()), rand(shape(n), random).multiply(10.0).subtract(5.0)).get().run();

		SimulationRunner runner = new SimulationRunner(population, DT);
		SimulationRunner.Recording recording = runner.record(cp(population.getRates()), interval, steps / interval);
		runner.run(steps);

		double[] samples = recording.getBuffer().toArray();
		double min = Double.MAX_VALUE;
		double max = -Double.MAX_VALUE;
		for (double v : samples) {
			assertTrue("rate is not finite", Double.isFinite(v));
			min = Math.min(min, v);
			max = Math.max(max, v);
		}

		log("min=" + min + " max=" + max);
		assertTrue("rate below zero: " + min, min >= 0.0);
		assertTrue("rate above rMax: " + max, max <= rMax * (1.0 + 1e-6));
		assertTrue("rates collapsed to zero", max > 0.0);

		runner.destroy();
		population.destroy();
		weights.destroy();
	}

	/**
	 * The coupling input with a row scale, {@code s (.) (W r)}, equals {@code diag(s) W r} built
	 * on the host.
	 */
	@Test(timeout = 120000)
	public void rowScale() {
		int n = 6;
		Random random = new Random(6);
		PackedCollection weights = rand(shape(n, n), random).subtract(0.5).evaluate();
		PackedCollection scale = c(1.0).divide(integers(1, n + 1)).evaluate();
		RatePopulation population = new RatePopulation(weights, cp(scale), 1.0, DT, TAU, 10.0);
		a(cp(population.getRates()), rand(shape(n), random)).get().run();

		double[] w = weights.toArray();
		double[] s = scale.toArray();
		double[] r = population.getRates().toArray();

		double[] actual = population.couplingInput().evaluate().toArray();
		for (int i = 0; i < n; i++) {
			double expected = 0.0;
			for (int j = 0; j < n; j++) expected += s[i] * w[i * n + j] * r[j];
			assertEquals(expected, actual[i], 1e-6);
		}

		population.destroy();
		scale.destroy();
		weights.destroy();
	}

	/**
	 * Rows of the matrix are targets and columns are sources: driving unit 3 raises exactly the
	 * units with a nonzero entry in column 3 (units 1 and 4). Unit 3 also has a nonzero entry in
	 * its own row (from unit 2), so a population that used the transpose would raise unit 2
	 * instead.
	 */
	@Test(timeout = 120000)
	public void orientation() {
		int n = 6;
		int driven = 3;
		PackedCollection weights = new PackedCollection(shape(n, n));
		weights.clear();
		weights.setMem(n + driven, 1.0);
		weights.setMem(4 * n + driven, 2.0);
		weights.setMem(driven * n + 2, 1.0);

		RatePopulation population = new RatePopulation(weights, null, 1.0, DT, TAU, 10.0);
		population.getInput().setMem(driven, 5.0);

		SimulationRunner runner = new SimulationRunner(population, DT);
		runner.run(10);

		double[] rates = population.getRates().toArray();
		assertTrue(rates[driven] > 0.0);
		assertTrue(rates[1] > 0.0);
		assertTrue(rates[4] > rates[1]);
		assertEquals(0.0, rates[0], 0.0);
		assertEquals(0.0, rates[2], 0.0);
		assertEquals(0.0, rates[5], 0.0);

		runner.destroy();
		population.destroy();
		weights.destroy();
	}
}
