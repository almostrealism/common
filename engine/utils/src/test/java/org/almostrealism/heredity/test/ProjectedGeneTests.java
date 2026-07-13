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

package org.almostrealism.heredity.test;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.ProjectedGene;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests that {@link ProjectedGene}'s device-computed projection matches a host-computed
 * reference (dot product, triangular wave, and range mapping), that weight initialization
 * normalizes each row, and that {@link ScaleFactor} scales through its producer.
 */
public class ProjectedGeneTests extends TestSuiteBase {

	/**
	 * Tests that refreshValues produces, for every factor position, the same value as the
	 * reference formula: the source-weights dot product, wrapped by a positive mod into
	 * [0, 2), mapped through a triangular wave, and scaled into the configured range.
	 */
	@Test(timeout = 120000)
	public void refreshValuesReference() {
		int factors = 4;
		int sourceLength = 12;

		double[] min = { 0.0, -1.0, 3.0, 5.0 };
		double[] max = { 1.0, 1.0, 7.0, 2.0 };

		PackedCollection source = new PackedCollection(shape(sourceLength));
		source.fill(pos -> 2.0 * Math.random() - 1.0);

		PackedCollection weights = new PackedCollection(shape(factors, sourceLength)).traverse(1);
		weights.fill(pos -> 2.0 * Math.random() - 1.0);

		ProjectedGene gene = new ProjectedGene(source, weights);
		for (int pos = 0; pos < factors; pos++) {
			gene.setRange(pos, min[pos], max[pos]);
		}

		gene.refreshValues();

		for (int pos = 0; pos < factors; pos++) {
			double dot = 0.0;
			for (int i = 0; i < sourceLength; i++) {
				dot += source.toDouble(i) * weights.valueAt(pos, i);
			}

			double value = ((dot % 2.0) + 2.0) % 2.0;
			double phase = value / 2.0;
			value = phase < 0.5 ? 2 * phase : 2 * (1 - phase);

			double expected = min[pos] + value * (max[pos] - min[pos]);
			double actual = gene.valueAt(pos).getResultant(null).get().evaluate().toDouble(0);
			assertEquals(expected, actual);
		}
	}

	/**
	 * Tests that initWeights leaves every weight row with unit L2 norm.
	 */
	@Test(timeout = 120000)
	public void initWeightsNormalization() {
		int factors = 3;
		int sourceLength = 10;

		PackedCollection source = new PackedCollection(shape(sourceLength));
		PackedCollection weights = new PackedCollection(shape(factors, sourceLength)).traverse(1);

		ProjectedGene gene = new ProjectedGene(source, weights);
		gene.initWeights(42L);

		for (int pos = 0; pos < factors; pos++) {
			double sumSquares = 0.0;
			for (int i = 0; i < sourceLength; i++) {
				double w = weights.valueAt(pos, i);
				sumSquares += w * w;
			}

			assertEquals(1.0, Math.sqrt(sumSquares));
		}
	}

	/**
	 * Tests that ScaleFactor multiplies through its producer and that the scale value
	 * survives a set/get round trip after replacement.
	 */
	@Test(timeout = 120000)
	public void scaleFactor() {
		ScaleFactor factor = new ScaleFactor(0.5);
		assertEquals(0.5, factor.getScaleValue());
		assertEquals(1.5, factor.getResultant(c(3.0)).get().evaluate().toDouble(0));

		factor.setScaleValue(0.25);
		assertEquals(0.25, factor.getScaleValue());
		assertEquals(2.0, factor.getResultant(c(8.0)).get().evaluate().toDouble(0));
	}
}
