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
import io.almostrealism.compute.ProcessContextBase;
import io.almostrealism.compute.ProcessOptimizationStrategy;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Minimal tests demonstrating the bug in
 * {@link org.almostrealism.collect.computations.ReshapeProducer#optimize(ProcessContext)}
 * where the strategy cascade was never invoked on the {@code ReshapeProducer} node itself.
 *
 * <p>This prevented {@link io.almostrealism.compute.ExpansionWidthTargetOptimization} from
 * ever seeing producers below a reshape, including the {@code GreaterThanCollection} nodes
 * that cause the 44 MB kernel expression blow-up described in
 * {@code docs/plans/EXPANSION_WIDTH_OPTIMIZATION.md}.</p>
 *
 * <p>The two tests progress from baseline to regression guard:</p>
 * <ol>
 *   <li>{@link #strategyInvokedOnGreaterThanDirectly} — baseline, always passes.</li>
 *   <li>{@link #strategyReachesReshapeProducerAfterFix} — regression guard: asserts the
 *       desired post-fix behaviour where the strategy cascade is invoked on the
 *       {@code ReshapeProducer} node itself.</li>
 * </ol>
 *
 * @see org.almostrealism.collect.computations.ReshapeProducer
 * @see io.almostrealism.compute.ExpansionWidthTargetOptimization
 */
public class ReshapeProducerStrategyTests extends TestSuiteBase {

	/**
	 * Baseline: the strategy cascade <em>is</em> invoked on a {@code GreaterThanCollection}
	 * when that producer is the direct cascade entry point — no {@code ReshapeProducer} in
	 * the path. This should pass both before and after the fix.
	 */
	@Test(timeout = 15000)
	public void strategyInvokedOnGreaterThanDirectly() {
		PackedCollection a = new PackedCollection(shape(8)).randFill();
		PackedCollection b = new PackedCollection(shape(8)).randFill();
		PackedCollection t = new PackedCollection(shape(8)).randFill();
		PackedCollection f = new PackedCollection(shape(8)).randFill();

		List<String> visited = new ArrayList<>();
		ProcessOptimizationStrategy priorStrategy = ProcessContext.base().getOptimizationStrategy();
		ProcessContextBase.setDefaultOptimizationStrategy(
				new ProcessOptimizationStrategy() {
					@Override
					public <P extends Process<?, ?>, T> Process<P, T> optimize(
							ProcessContext ctx, Process<P, T> parent, Collection<P> children,
							Function<Collection<P>, Stream<P>> childProcessor) {
						visited.add(parent.getClass().getSimpleName());
						return parent;
					}
				});

		try {
			Producer<PackedCollection> gt = greaterThan(cp(a), cp(b), cp(t), cp(f));
			((Process<?, ?>) gt).optimize(ProcessContext.base());

			assertTrue("strategy must be invoked on GreaterThanCollection when it is the entry point",
					visited.stream().anyMatch(name -> name.contains("GreaterThan")));
		} finally {
			ProcessContextBase.setDefaultOptimizationStrategy(priorStrategy);
		}
	}

	/**
	 * Post-fix regression guard: after the fix to {@code ReshapeProducer.optimize},
	 * the strategy cascade <em>is</em> invoked on the {@code ReshapeProducer} node when
	 * a {@code GreaterThanCollection} is nested inside it. This allows
	 * {@link io.almostrealism.compute.ExpansionWidthTargetOptimization} to see the reshape
	 * wrapper and decide whether to isolate the high-expansion-width child.
	 *
	 * <p>This test <strong>fails before the fix</strong> and passes after.</p>
	 */
	@Test(timeout = 15000)
	public void strategyReachesReshapeProducerAfterFix() {
		PackedCollection a = new PackedCollection(shape(8)).randFill();
		PackedCollection b = new PackedCollection(shape(8)).randFill();
		PackedCollection t = new PackedCollection(shape(8)).randFill();
		PackedCollection f = new PackedCollection(shape(8)).randFill();

		List<String> visited = new ArrayList<>();
		ProcessOptimizationStrategy priorStrategy = ProcessContext.base().getOptimizationStrategy();
		ProcessContextBase.setDefaultOptimizationStrategy(
				new ProcessOptimizationStrategy() {
					@Override
					public <P extends Process<?, ?>, T> Process<P, T> optimize(
							ProcessContext ctx, Process<P, T> parent, Collection<P> children,
							Function<Collection<P>, Stream<P>> childProcessor) {
						visited.add(parent.getClass().getSimpleName());
						return parent;
					}
				});

		try {
			Producer<PackedCollection> gt = greaterThan(cp(a), cp(b), cp(t), cp(f));
			CollectionProducer reshaped = traverse(1, gt);
			((Process<?, ?>) reshaped).optimize(ProcessContext.base());

			assertTrue("after fix: strategy cascade must be invoked on the ReshapeProducer node",
					visited.stream().anyMatch(name -> name.contains("Reshape")));
		} finally {
			ProcessContextBase.setDefaultOptimizationStrategy(priorStrategy);
		}
	}
}
