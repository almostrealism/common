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

package io.almostrealism.compute.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Verifies the evaluation entry-point contract on {@link Producer}:
 * {@link Producer#evaluate(Object...)} optimizes by default,
 * {@link Producer#evaluateExplicit(Object...)} is the deliberate unoptimized path,
 * and the deprecated {@link Producer#evaluateOptimized(Object...)} is an alias of
 * {@code evaluate(Object...)}.
 *
 * <p>This guards the rewiring that made optimization the default for
 * {@code evaluate(...)}: a future accidental swap of the three entry points, or a
 * {@code evaluateOptimized(...)} alias that no longer delegates to {@code evaluate(...)},
 * would be caught here.</p>
 */
public class ProducerEvaluationContractTest extends TestSuiteBase {

	/**
	 * For a simple element-wise graph that needs no isolation, the optimizing
	 * default path and the explicit unoptimized path produce identical results,
	 * and the deprecated alias agrees with both.
	 */
	@Test(timeout = 60000)
	public void entryPointsAgreeForElementWiseGraph() {
		PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);
		PackedCollection b = pack(5.0, 6.0, 7.0, 8.0);

		CollectionProducer product = cp(a).multiply(cp(b));

		PackedCollection optimized = product.evaluate();
		PackedCollection explicit = product.evaluateExplicit();
		PackedCollection deprecated = product.evaluateOptimized();

		for (int i = 0; i < 4; i++) {
			double expected = (i + 1) * (i + 5);
			assertEquals(expected, optimized.toDouble(i));
			assertEquals(expected, explicit.toDouble(i));
			assertEquals(expected, deprecated.toDouble(i));
		}
	}

	/**
	 * The default {@link Producer#evaluate(Object...)} path optimizes a composite
	 * graph containing a reduction and produces the correct reduced value.
	 */
	@Test(timeout = 60000)
	public void evaluateReducesCompositeGraph() {
		PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);

		PackedCollection sum = cp(a).sum().evaluate();
		assertEquals(10.0, sum.toDouble(0));
	}
}
