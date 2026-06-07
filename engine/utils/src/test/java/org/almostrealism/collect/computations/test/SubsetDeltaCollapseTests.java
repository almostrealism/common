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

package org.almostrealism.collect.computations.test;

import io.almostrealism.compute.Process;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.AggregatedProducerComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests for the gather-collapse of a contracted subset-selection gradient.
 *
 * <p>The contracted path {@code subset(x).sum().delta(x)} builds an
 * {@link AggregatedProducerComputation} whose reduction summand is a single-index mask
 * {@code Mask(col == source(row), value)}. Summing over the masked column reduces to a
 * gather {@code value[source(row)]}; this is the same path that {@code convDelta} exercises
 * at a larger scale, here reproduced cheaply with a closed-form gradient oracle.</p>
 *
 * @author Michael Murray
 */
public class SubsetDeltaCollapseTests extends TestSuiteBase {

	/**
	 * Closed-form oracle: {@code d(sum(subset(x, [k] at off)))/dx[j] = 1} when
	 * {@code off <= j < off + k}, else {@code 0}.
	 */
	@Test(timeout = 60000)
	public void subsetSumDeltaCorrectness() {
		int n = 8;
		int k = 4;
		int off = 2;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		CollectionProducer grad = cp(in).subset(shape(k), off).sum().delta(cp(in));

		PackedCollection out = grad.evaluate();
		out.traverse().print();

		for (int j = 0; j < n; j++) {
			assertEquals((j >= off && j < off + k) ? 1.0 : 0.0, out.toDouble(j));
		}
	}

	/**
	 * Differential test: the optimized graph (which may collapse the reduction loop into a
	 * gather) must produce identical output to the un-optimized graph (which evaluates the
	 * dense reduction directly). A wrong collapse yields silently incorrect gradients, so
	 * this guards correctness independent of the oracle above.
	 */
	@Test(timeout = 60000)
	public void subsetSumDeltaCollapseMatchesDense() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		PackedCollection dense = cp(in).subset(shape(k), off).sum().delta(cp(in)).evaluate();
		PackedCollection optimized =
				(PackedCollection) Process.optimized(cp(in).subset(shape(k), off).sum().delta(cp(in)))
						.get().evaluate();

		dense.traverse().print();
		optimized.traverse().print();

		for (int j = 0; j < n; j++) {
			assertEquals(dense.toDouble(j), optimized.toDouble(j));
		}
	}

	/**
	 * Diagnostic reproduction with aggregation logging enabled, used to confirm whether the
	 * reduction loop collapses (no "Unable to determine unique offset" log) or falls back to
	 * the dense loop.
	 */
	@Test(timeout = 120000)
	public void subsetSumDeltaDiagnostic() {
		boolean prev = AggregatedProducerComputation.enableLogging;
		boolean prevWarn = ScopeSettings.enableExpressionWarnings;
		AggregatedProducerComputation.enableLogging = true;
		ScopeSettings.enableExpressionWarnings = true;

		try {
			int n = 8;
			int k = 4;
			int off = 0;

			PackedCollection in = new PackedCollection(shape(n)).randFill();
			PackedCollection out =
					(PackedCollection) Process.optimized(cp(in).subset(shape(k), off).sum().delta(cp(in)))
							.get().evaluate();
			out.traverse().print();

			for (int j = 0; j < n; j++) {
				assertEquals(j < k ? 1.0 : 0.0, out.toDouble(j));
			}
		} finally {
			AggregatedProducerComputation.enableLogging = prev;
			ScopeSettings.enableExpressionWarnings = prevWarn;
		}
	}
}
