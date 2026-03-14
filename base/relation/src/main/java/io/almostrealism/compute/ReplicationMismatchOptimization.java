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

package io.almostrealism.compute;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Optimization strategy that detects children with parallelism significantly lower
 * than the parent and selectively isolates them to prevent expression replication.
 *
 * <p>When a computation with parallelism N has an input with parallelism M where
 * M &lt;&lt; N, the input's expression tree is replicated N times in the generated
 * kernel. This strategy detects such mismatches and isolates the low-parallelism
 * children into separate kernels, converting the replication into a buffer read.</p>
 *
 * <h2>Example: MultiOrderFilter Coefficients</h2>
 * <p>A convolution kernel with parallelism 4096 (one work item per output sample)
 * may contain coefficient expressions with parallelism 41 (one per filter tap).
 * Without isolation, each of the 41 sin/cos coefficient computations is replicated
 * 4096 times in the generated code. With this strategy, the coefficients are isolated
 * into a separate 41-work-item kernel, and the convolution reads coefficients from
 * a pre-computed buffer.</p>
 *
 * <h2>Replication Ratio</h2>
 * <p>The replication ratio is {@code parentParallelism / childParallelism}. When this
 * ratio exceeds {@link #replicationThreshold}, the child is isolated. The threshold
 * is conservative by default (8x) to avoid unnecessary isolation overhead for small
 * mismatches.</p>
 *
 * <h2>Cascading Behavior</h2>
 * <p>This strategy returns {@code null} when no replication mismatches are detected,
 * allowing it to compose with other strategies via {@link CascadingOptimizationStrategy}.
 * It is designed to run before {@link ParallelismTargetOptimization} in the cascade
 * chain, handling per-child selective isolation while the fallback handles aggregate
 * isolation decisions.</p>
 *
 * <h2>Safety</h2>
 * <p>Isolation of individual children delegates to {@link Process#isolate(Process)},
 * which respects {@link Process#isolationPermitted(java.util.function.Supplier)} and
 * implementation-specific memory guards. Children that cannot be safely isolated are
 * left inline.</p>
 *
 * @see CascadingOptimizationStrategy
 * @see ParallelismTargetOptimization
 * @see ProcessOptimizationStrategy
 *
 * @author Michael Murray
 */
public class ReplicationMismatchOptimization implements ProcessOptimizationStrategy {

	/**
	 * Minimum replication ratio to trigger isolation.
	 *
	 * <p>A child with parallelism M inside a parent with parallelism N is isolated
	 * when {@code N / M >= replicationThreshold}. The default value of 8 means the
	 * child's expression tree must be replicated at least 8 times before isolation
	 * is considered worthwhile.</p>
	 */
	public static int replicationThreshold = 8;

	/**
	 * Optimizes a process by selectively isolating children whose parallelism is
	 * significantly lower than the parent's.
	 *
	 * <p>For each child, the method computes the replication ratio
	 * ({@code parentParallelism / childParallelism}). Children with parallelism
	 * greater than 1 whose ratio exceeds {@link #replicationThreshold} are isolated
	 * via {@link Process#isolate(Process)}; all other children are left inline.
	 * Children with parallelism 1 are skipped because they are typically buffer
	 * references or scalar values with no expression tree to replicate.</p>
	 *
	 * @param <P>            the type of child processes
	 * @param <T>            the result type of the process
	 * @param ctx            the process context
	 * @param parent         the parent process being optimized
	 * @param children       the collection of child processes
	 * @param childProcessor a function to process children for analysis
	 * @return the optimized process with selectively isolated children,
	 *         or {@code null} if no replication mismatches were detected
	 */
	@Override
	public <P extends Process<?, ?>, T> Process<P, T> optimize(
			ProcessContext ctx,
			Process<P, T> parent,
			Collection<P> children,
			Function<Collection<P>, Stream<P>> childProcessor) {
		listeners.forEach(l -> l.accept(parent));

		long parentParallelism = ParallelProcess.parallelism(parent);
		if (parentParallelism <= 0) return null;

		boolean anyMismatch = false;
		List<P> result = new ArrayList<>(children.size());

		for (P child : children) {
			long childParallelism = ParallelProcess.parallelism(child);

			if (childParallelism > 1 && childParallelism < parentParallelism
					&& parentParallelism / childParallelism >= replicationThreshold) {
				result.add((P) parent.isolate((Process) child));
				anyMismatch = true;
			} else {
				result.add(child);
			}
		}

		if (!anyMismatch) {
			return null;
		}

		return parent.generate(result);
	}
}
