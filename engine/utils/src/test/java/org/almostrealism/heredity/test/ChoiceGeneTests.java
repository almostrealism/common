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
import org.almostrealism.heredity.ChoiceGene;
import org.almostrealism.heredity.HeredityFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests the continuous-to-discrete mapping of {@link ChoiceGene}.
 *
 * <p>{@link ChoiceGene} maps a continuous value in the inclusive range {@code [0, 1]}
 * onto one of {@code N} discrete choices, as its own class documentation states
 * ("Get the continuous value from the underlying gene (typically in [0, 1])") and its
 * example demonstrates ({@code g(0.0, 0.5, 1.0)} selecting from three choices, with the
 * {@code 1.0} factor expected to select the last choice). The index is computed as
 * {@code value * N}, so the maximum value {@code 1.0} yields {@code N}, one past the last
 * valid choice index {@code N - 1}; a correct mapping must land on the last choice.</p>
 */
public class ChoiceGeneTests extends TestSuiteBase implements HeredityFeatures {

	/**
	 * A gene value of {@code 1.0} — the inclusive upper bound of the documented value
	 * range, and the literal value used in the {@link ChoiceGene} javadoc example — must
	 * select the last choice.
	 *
	 * <p>The three choices {@code [10, 20, 30]} are held as the first three elements of a
	 * four-element buffer whose fourth element is a sentinel ({@code 999}). This makes the
	 * failure deterministic: the defect computes the choice index as {@code 1.0 * 3 = 3},
	 * one past the last valid index. The gather then reads element {@code 3}, which is out
	 * of bounds for the three-element choice collection — surfacing either as a bounds
	 * error or, on a backend that reads adjacent memory, as the sentinel {@code 999} —
	 * rather than the last choice {@code 30}.</p>
	 */
	@Test(timeout = 120000)
	public void maxValueSelectsLastChoice() {
		PackedCollection buffer = PackedCollection.of(10.0, 20.0, 30.0, 999.0);
		PackedCollection choices = buffer.range(shape(3));

		ChoiceGene gene = new ChoiceGene(g(1.0), choices);

		double selected = gene.valueAt(0).getResultant(c(1.0)).get().evaluate().toDouble(0);

		assertEquals(30.0, selected);
	}
}
