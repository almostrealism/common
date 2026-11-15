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

package org.almostrealism.algebra;

import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies the guard on the unoptimized evaluation path, which
 * {@link CollectionProducerComputationBase#checkUnoptimizedIsolation()} applies when a
 * computation reaches compilation (through {@code getEvaluable()}) without first being
 * optimized. A reduction longer than
 * {@link org.almostrealism.collect.computations.TraversableRepeatedProducerComputation#isolationCountThreshold}
 * reports itself as an isolation target, so a graph that embeds one and is evaluated
 * without optimization is what the guard is meant to catch.
 */
public class UnoptimizedIsolationGuardTest extends TestSuiteBase {

	/**
	 * A reduction over a group larger than the isolation threshold, consumed by an
	 * element-wise reader so that the reduction is a descendant of the evaluated root
	 * rather than the root itself.
	 */
	private Producer<PackedCollection> reductionConsumedByReader() {
		PackedCollection input = new PackedCollection(shape(64)).randFill();
		return add(max(cp(input)), c(1.0));
	}

	/**
	 * The tree-walk {@link Process#collectUnisolatedIsolationTargets(ProcessContext)} finds
	 * the embedded reduction when the graph has not been optimized.
	 */
	@Test(timeout = 60000)
	public void collectsUnisolatedReduction() {
		Producer<PackedCollection> graph = reductionConsumedByReader();
		Assert.assertTrue(graph instanceof Process);

		Assert.assertFalse(((Process) graph)
				.collectUnisolatedIsolationTargets(ProcessContext.base()).isEmpty());
	}

	/**
	 * Evaluating the graph without optimization trips the guard. The guard is run in its
	 * failing mode here so the detection is observable as a thrown exception rather than a
	 * log line.
	 */
	@Test(timeout = 60000)
	public void tripsOnUnoptimizedReduction() {
		boolean fails = CollectionProducerComputationBase.unoptimizedIsolationGuardFails;

		try {
			CollectionProducerComputationBase.unoptimizedIsolationGuardFails = true;

			Producer<PackedCollection> graph = reductionConsumedByReader();

			try {
				graph.evaluate();
				Assert.fail("Guard did not trip on an unoptimized reduction");
			} catch (IllegalStateException expected) {
				// the guard reports the un-isolated reduction by throwing
			}
		} finally {
			CollectionProducerComputationBase.unoptimizedIsolationGuardFails = fails;
		}
	}

	/**
	 * Evaluating the same graph through {@code evaluateOptimized()} isolates the reduction,
	 * so the guard stays silent even in its failing mode.
	 */
	@Test(timeout = 60000)
	public void silentOnOptimizedReduction() {
		boolean fails = CollectionProducerComputationBase.unoptimizedIsolationGuardFails;

		try {
			CollectionProducerComputationBase.unoptimizedIsolationGuardFails = true;

			Producer<PackedCollection> graph = reductionConsumedByReader();
			PackedCollection result = graph.evaluateOptimized();
			Assert.assertNotNull(result);
		} finally {
			CollectionProducerComputationBase.unoptimizedIsolationGuardFails = fails;
		}
	}

	/**
	 * A purely element-wise graph has no isolation target, so the guard stays silent even
	 * when the graph is evaluated without optimization in the guard's failing mode.
	 */
	@Test(timeout = 60000)
	public void silentWithoutIsolationTarget() {
		boolean fails = CollectionProducerComputationBase.unoptimizedIsolationGuardFails;

		try {
			CollectionProducerComputationBase.unoptimizedIsolationGuardFails = true;

			PackedCollection a = new PackedCollection(shape(8)).randFill();
			PackedCollection b = new PackedCollection(shape(8)).randFill();
			Producer<PackedCollection> graph = add(cp(a), cp(b));

			Assert.assertTrue(((Process) graph)
					.collectUnisolatedIsolationTargets(ProcessContext.base()).isEmpty());

			PackedCollection result = graph.evaluate();
			Assert.assertNotNull(result);
		} finally {
			CollectionProducerComputationBase.unoptimizedIsolationGuardFails = fails;
		}
	}
}
