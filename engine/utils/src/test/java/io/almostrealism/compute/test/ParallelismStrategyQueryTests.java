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

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.ParallelismDiversityOptimization;
import io.almostrealism.compute.ParallelismTargetOptimization;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.compute.ProcessOptimizationStrategy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Pins what the parallelism strategies of the default optimization cascade decide, and how
 * much of a process tree they read to decide it, along with the depth query the depth strategy
 * relies on. Every evaluation optimizes its graph, so these strategies run at every node of
 * every graph evaluated; they must not ask a process for its parallelism or output size when
 * the answer cannot change the decision. The processes here are stubs that count those
 * questions, so the tests hold on any backend.
 */
public class ParallelismStrategyQueryTests extends TestSuiteBase {

	/**
	 * A process with a chosen parallelism and output size that records how often each is
	 * read. Isolating it yields a copy marked as isolated.
	 */
	private static final class Stage implements ParallelProcess<Process<?, ?>, Object> {
		/** The label of this stage, used to select stages in a depth query. */
		private final String label;

		/** The parallelism of this stage. */
		private final long parallelism;

		/** The output size of this stage. */
		private final long outputSize;

		/** Whether this stage is the result of isolating another. */
		private final boolean isolated;

		/** The children of this stage. */
		private final List<Process<?, ?>> children;

		/** The number of times {@link #getParallelism()} has been called. */
		private int parallelismQueries;

		/** The number of times {@link #getOutputSize()} has been called. */
		private int outputSizeQueries;

		/**
		 * Creates a stage.
		 *
		 * @param label       the label of the stage
		 * @param parallelism the parallelism of the stage
		 * @param outputSize  the output size of the stage
		 * @param isolated    whether the stage is the result of isolating another
		 * @param children    the children of the stage
		 */
		private Stage(String label, long parallelism, long outputSize, boolean isolated,
					  List<Process<?, ?>> children) {
			this.label = label;
			this.parallelism = parallelism;
			this.outputSize = outputSize;
			this.isolated = isolated;
			this.children = children;
		}

		/**
		 * Returns a stage that is not isolated.
		 *
		 * @param label       the label of the stage
		 * @param parallelism the parallelism of the stage
		 * @param outputSize  the output size of the stage
		 * @param children    the children of the stage
		 * @return the stage
		 */
		private static Stage of(String label, long parallelism, long outputSize, Process<?, ?>... children) {
			return new Stage(label, parallelism, outputSize, false, List.of(children));
		}

		/**
		 * Applies a strategy to this stage and its children, as the optimization of this
		 * stage does once its children have been optimized.
		 *
		 * @param strategy the strategy
		 * @return the result of the strategy, which may be {@code null}
		 */
		private Process<Process<?, ?>, Object> apply(ProcessOptimizationStrategy strategy) {
			return strategy.optimize(ProcessContext.base(), this, getChildren(), Collection::stream);
		}

		/**
		 * Returns whether each child of this stage is an isolated stage.
		 *
		 * @return the isolation of each child, in order
		 */
		private List<Boolean> childIsolation() {
			List<Boolean> result = new ArrayList<>();
			for (Process<?, ?> child : children) {
				result.add(((Stage) child).isolated);
			}

			return result;
		}

		@Override
		public Collection<Process<?, ?>> getChildren() { return children; }

		@Override
		public Object get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getCountLong() { return parallelism; }

		@Override
		public long getParallelism() {
			parallelismQueries++;
			return parallelism;
		}

		@Override
		public long getOutputSize() {
			outputSizeQueries++;
			return outputSize;
		}

		@Override
		public ParallelProcess<Process<?, ?>, Object> generate(List<Process<?, ?>> children) {
			return new Stage(label, parallelism, outputSize, isolated, children);
		}

		@Override
		public Process<Process<?, ?>, Object> isolate() {
			return new Stage(label, parallelism, outputSize, true, children);
		}
	}

	/**
	 * The depth below a process is its own count plus the largest depth among the children
	 * that pass the filter; children that do not pass are not explored.
	 */
	@Test(timeout = 10000)
	public void depthCountsAlongFilteredChildren() {
		Stage leaf = Stage.of("leaf", 1, 1);
		Stage hidden = Stage.of("hidden", 1, 1, Stage.of("deep", 1, 1, Stage.of("deeper", 1, 1)));
		Stage root = Stage.of("root", 1, 1, Stage.of("mid", 1, 1, leaf), hidden);

		Assert.assertEquals(4, root.countDepth(p -> 1, p -> true));
		Assert.assertEquals(3, root.countDepth(p -> 1, p -> !"hidden".equals(((Stage) p).label)));
		Assert.assertEquals(1, root.countDepth(p -> "mid".equals(((Stage) p).label) ? 1 : 0, p -> true));
		Assert.assertEquals(1, leaf.countDepth(p -> 1, p -> true));
	}

	/**
	 * A process cannot have more distinct child parallelisms than children, so a process with
	 * no more children than the diversity threshold is left to the rest of the cascade without
	 * any child being asked for its parallelism.
	 */
	@Test(timeout = 10000)
	public void diversityReadsNoParallelismForFewChildren() {
		Stage a = Stage.of("a", 1000, 1);
		Stage b = Stage.of("b", 2000, 1);
		Stage c = Stage.of("c", 3000, 1);
		Stage parent = Stage.of("parent", 1, 1, a, b, c);

		Assert.assertNull(parent.apply(new ParallelismDiversityOptimization()));
		Assert.assertEquals(0, a.parallelismQueries);
		Assert.assertEquals(0, b.parallelismQueries);
		Assert.assertEquals(0, c.parallelismQueries);
	}

	/**
	 * A process with more distinct child parallelisms than the diversity threshold, whose
	 * children average far more parallelism than it has, still has its children isolated.
	 */
	@Test(timeout = 10000)
	public void diversityIsolatesManyDiverseChildren() {
		Process<?, ?>[] children = new Process<?, ?>[9];
		for (int i = 0; i < children.length; i++) {
			children[i] = Stage.of("child" + i, 1000L * (i + 1), 1);
		}

		Process<Process<?, ?>, Object> result =
				Stage.of("parent", 1, 1, children).apply(new ParallelismDiversityOptimization());

		Assert.assertNotNull(result);
		List<Boolean> isolation = ((Stage) result).childIsolation();
		Assert.assertEquals(9, isolation.size());
		assertTrue(isolation.stream().allMatch(Boolean::booleanValue));
	}

	/**
	 * The diversity strategy processes the children for analysis exactly once, whether it stops
	 * at the threshold or goes on to read their parallelism, and reads each child's parallelism
	 * exactly once when it does.
	 */
	@Test(timeout = 10000)
	public void diversityProcessesChildrenOnce() {
		Stage[] children = new Stage[9];
		for (int i = 0; i < children.length; i++) {
			children[i] = Stage.of("child" + i, 1000L * (i + 1), 1);
		}

		Stage wide = Stage.of("wide", 1, 1, children);
		Stage narrow = Stage.of("narrow", 1, 1, Stage.of("a", 10, 1), Stage.of("b", 20, 1));

		int[] applications = new int[1];
		Function<Collection<Process<?, ?>>, Stream<Process<?, ?>>> processor = c -> {
			applications[0]++;
			return c.stream();
		};

		ParallelismDiversityOptimization strategy = new ParallelismDiversityOptimization();
		Assert.assertNotNull(strategy.optimize(ProcessContext.base(), wide, wide.getChildren(), processor));
		Assert.assertEquals(1, applications[0]);
		for (Stage child : children) {
			Assert.assertEquals(1, child.parallelismQueries);
		}

		applications[0] = 0;
		Assert.assertNull(strategy.optimize(ProcessContext.base(), narrow, narrow.getChildren(), processor));
		Assert.assertEquals(1, applications[0]);
	}

	/**
	 * When the parallelism of a process covers that of every child, its children stay inline,
	 * and neither it nor any child is asked for its output size, which only the scores read.
	 */
	@Test(timeout = 10000)
	public void targetReadsNoOutputSizeWhenParentCoversChildren() {
		Stage a = Stage.of("a", 100, 10);
		Stage b = Stage.of("b", 50, 10);
		Stage parent = Stage.of("parent", 100, 100, a, b);

		Stage result = (Stage) parent.apply(new ParallelismTargetOptimization());

		Assert.assertEquals(List.of(false, false), result.childIsolation());
		Assert.assertEquals(0, parent.outputSizeQueries);
		Assert.assertEquals(0, a.outputSizeQueries);
		Assert.assertEquals(0, b.outputSizeQueries);
	}

	/**
	 * When the counts leave the decision open and a child scores better than the process
	 * itself (far more parallelism for a small output), the children are isolated.
	 */
	@Test(timeout = 10000)
	public void targetIsolatesWhenAChildScoresBetter() {
		Stage parent = Stage.of("parent", 10, 10, Stage.of("a", 1000, 1000), Stage.of("b", 10, 10));

		Stage result = (Stage) parent.apply(new ParallelismTargetOptimization());

		Assert.assertEquals(List.of(true, true), result.childIsolation());
		assertTrue(parent.outputSizeQueries > 0);
	}

	/**
	 * When the counts leave the decision open but the process scores better than every child
	 * (the child with more parallelism has an output so large that its memory cost dominates),
	 * the children stay inline.
	 */
	@Test(timeout = 10000)
	public void targetKeepsChildrenWhenTheParentScoresBetter() {
		Stage parent = Stage.of("parent", 10, 10, Stage.of("a", 1000, 10_000_000), Stage.of("b", 5, 5));

		Stage result = (Stage) parent.apply(new ParallelismTargetOptimization());

		Assert.assertEquals(List.of(false, false), result.childIsolation());
		assertTrue(parent.outputSizeQueries > 0);
	}
}
