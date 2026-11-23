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

import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collection;
import java.util.Collections;

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
 * would be caught here. {@link #evaluateRoutesThroughOptimizationWhileExplicitDoesNot()}
 * proves the routing directly rather than through numerical results (optimization is
 * semantics-preserving, so a value comparison alone cannot distinguish the paths);
 * the value tests additionally confirm the real framework produces correct results
 * through each entry point.</p>
 */
public class ProducerEvaluationContractTest extends TestSuiteBase {

	/**
	 * Each entry point computes the correct element-wise result on the real framework.
	 * A fresh producer is built per path so that the optimized computation cached on one
	 * producer does not make another producer's {@code evaluateExplicit()} silently run
	 * the optimized graph (see {@link Producer#evaluateExplicit(Object...)}).
	 */
	@Test(timeout = 60000)
	public void entryPointsAgreeForElementWiseGraph() {
		PackedCollection a = pack(1.0, 2.0, 3.0, 4.0);
		PackedCollection b = pack(5.0, 6.0, 7.0, 8.0);

		CollectionProducer optimizedProduct = cp(a).multiply(cp(b));
		CollectionProducer explicitProduct = cp(a).multiply(cp(b));
		CollectionProducer deprecatedProduct = cp(a).multiply(cp(b));

		PackedCollection optimized = optimizedProduct.evaluate();
		PackedCollection explicit = explicitProduct.evaluateExplicit();
		PackedCollection deprecated = deprecatedProduct.evaluateOptimized();

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

	/**
	 * Observes the routing of the three entry points directly, independent of the
	 * computed value. {@link RoutingProducer#get()} yields {@code "unoptimized"} and
	 * {@link RoutingProducer#optimize(ProcessContext)} yields a producer whose
	 * {@code get()} yields {@code "optimized"}, so the entry points are distinguishable:
	 * {@link Producer#evaluate(Object...)} and {@link Producer#evaluateOptimized(Object...)}
	 * must route through optimization, while {@link Producer#evaluateExplicit(Object...)}
	 * must not. A regression that made {@code evaluate()} call {@code get().evaluate()}
	 * again, or made {@code evaluateExplicit()} optimize, or broke the deprecated alias,
	 * fails here where the value tests above could not detect it.
	 */
	@Test(timeout = 60000)
	public void evaluateRoutesThroughOptimizationWhileExplicitDoesNot() {
		RoutingProducer producer = new RoutingProducer("unoptimized");

		Assert.assertEquals("optimized", producer.evaluate());
		Assert.assertEquals("unoptimized", producer.evaluateExplicit());
		Assert.assertEquals("optimized", producer.evaluateOptimized());
	}

	/**
	 * A minimal {@link Producer} that is also a {@link Process}, so
	 * {@link Process#optimized(java.util.function.Supplier)} invokes its
	 * {@link #optimize(ProcessContext)} method. Its unoptimized {@link #get()} and its
	 * optimized form return different values, which makes the entry-point routing
	 * observable. It carries no computation graph of its own.
	 */
	private static final class RoutingProducer
			implements Producer<String>, Process<Process<?, ?>, Evaluable<? extends String>> {
		/** The value this producer's {@link #get()} evaluates to. */
		private final String tag;

		/**
		 * Creates a producer whose unoptimized {@link #get()} evaluates to the given tag.
		 *
		 * @param tag the value returned on the unoptimized path
		 */
		private RoutingProducer(String tag) {
			this.tag = tag;
		}

		@Override
		public Evaluable<String> get() {
			String value = tag;
			return args -> value;
		}

		@Override
		public Collection<Process<?, ?>> getChildren() {
			return Collections.emptyList();
		}

		@Override
		public Process<Process<?, ?>, Evaluable<? extends String>> optimize(ProcessContext context) {
			return new RoutingProducer("optimized");
		}
	}
}
