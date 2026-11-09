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

package org.almostrealism.hardware.benchmark;

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * The benchmark population is a function of its seed, its default distribution sits between
 * always repeating and never repeating, and every generated reference is finite. None of this
 * compiles the generated computations, so it is cheap enough for every run.
 */
public class WorkloadGeneratorTest extends TestSuiteBase {

	/** The same seed generates the same populations, and a different seed a different one. */
	@Test(timeout = 60000)
	public void sameSeedSamePopulation() {
		List<BenchmarkScenario> a = new WorkloadGenerator(1729, 1024).scenarios(300);
		List<BenchmarkScenario> b = new WorkloadGenerator(1729, 1024).scenarios(300);
		List<BenchmarkScenario> c = new WorkloadGenerator(1730, 1024).scenarios(300);

		Assert.assertEquals(a.size(), b.size());
		for (int i = 0; i < a.size(); i++) {
			Assert.assertEquals(a.get(i).getName(), a.get(i).fingerprint(), b.get(i).fingerprint());
		}

		Assert.assertNotEquals(a.get(0).fingerprint(), c.get(0).fingerprint());
	}

	/**
	 * The fingerprint distinguishes populations that differ only in an embedded constant, a
	 * scalar input or a data seed, none of which {@link WorkloadInstance#describe()} renders, and
	 * is unchanged when nothing differs.
	 */
	@Test(timeout = 60000)
	public void fingerprintDistinguishesGeneratedValues() {
		ComputationTemplate canonical = ComputationTemplate.canonical();
		TraversalPolicy shape = new TraversalPolicy(4, 16);
		String base = fingerprint(canonical, shape, new double[] { 2.0 }, 7);

		Assert.assertEquals(base, fingerprint(canonical, shape, new double[] { 2.0 }, 7));
		Assert.assertNotEquals("embedded constant", base, fingerprint(canonical, shape, new double[] { 3.0 }, 7));
		Assert.assertNotEquals("data seed", base, fingerprint(canonical, shape, new double[] { 2.0 }, 8));
		Assert.assertNotEquals("shape", base, fingerprint(canonical, new TraversalPolicy(4, 32), new double[] { 2.0 }, 7));

		ComputationTemplate scaled = new ComputationTemplate("scaled", new TemplateNode.Input(0),
				ComputationTemplate.Reduction.NONE, 0, 1, 1, new boolean[] { false });
		Assert.assertNotEquals("scalar input",
				fingerprint(scaled, shape, new double[] { 2.0 }, 7),
				fingerprint(scaled, shape, new double[] { 3.0 }, 7));
	}

	/** Returns the fingerprint of a scenario holding the single described instance. */
	private static String fingerprint(ComputationTemplate template, TraversalPolicy shape,
									  double[] scalars, long dataSeed) {
		WorkloadInstance instance = new WorkloadInstance(template, shape,
				WorkloadInstance.Aliasing.DISTINCT, scalars, dataSeed);
		return new BenchmarkScenario("single", "one instance", List.of(instance)).fingerprint();
	}

	/**
	 * With the default parameters the main population repeats an earlier structure neither
	 * almost always nor almost never.
	 */
	@Test(timeout = 60000)
	public void defaultDistributionIsInTheMiddleRange() {
		double repeat = new WorkloadGenerator(1729, 1024).generated(500).structuralRepeatRate();
		log("structuralRepeatRate=" + repeat);
		Assert.assertTrue("repeat rate " + repeat, repeat > 0.2 && repeat < 0.8);

		double concentrated = new WorkloadGenerator(1729, 2, 4.0, 4.0, 4.0, 0.0, 4, 4, 0.3, 0.5, 1024)
				.generated(500).structuralRepeatRate();
		Assert.assertTrue("concentrated repeat rate " + concentrated, concentrated > repeat);
	}

	/** Every instance of every scenario has a finite reference of the expected length. */
	@Test(timeout = 60000)
	public void referencesAreFinite() {
		for (BenchmarkScenario scenario : new WorkloadGenerator(1729, 1024).scenarios(100)) {
			for (WorkloadInstance instance : scenario.getInstances()) {
				instance.allocate(this);

				try {
					double[] reference = instance.reference();
					Assert.assertTrue(instance.describe(), reference.length > 0);
					for (double value : reference) {
						Assert.assertTrue(instance.describe(), Double.isFinite(value));
					}

					Assert.assertNull(instance.describe(), instance.verify(reference));
				} finally {
					instance.release();
				}
			}
		}
	}
}
