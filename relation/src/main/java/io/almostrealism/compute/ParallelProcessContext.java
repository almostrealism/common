/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.relation.Countable;

import java.util.Optional;

/**
 * An extended {@link ProcessContext} that captures parallelism information during optimization.
 *
 * <p>{@code ParallelProcessContext} extends {@link ProcessContextBase} to track additional
 * state relevant to parallel process optimization, including the current parallelism count,
 * aggregation count, and whether the count is fixed or variable.</p>
 *
 * <h2>Context Properties</h2>
 * <ul>
 *   <li><b>Parallelism</b> - The number of parallel work items at this level of the tree.
 *       This helps optimization strategies understand the current execution scale.</li>
 *   <li><b>Aggregation Count</b> - The number of input positions needed to compute a single
 *       output. For element-wise operations this is 1; for reductions it can be much larger.</li>
 *   <li><b>Fixed Count</b> - Whether the parallelism is determined at compile time or depends
 *       on runtime input sizes.</li>
 * </ul>
 *
 * <h2>Context Propagation</h2>
 * <p>When optimizing nested processes, context is propagated and potentially updated based
 * on the parent process characteristics. The static {@code of()} factory methods handle
 * this propagation logic, preserving the most constraining context values.</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Create context for a parallel process
 * ParallelProcess<?, ?> process = ...;
 * ParallelProcessContext ctx = ParallelProcessContext.of(0, process);
 *
 * // Access context properties
 * long parallelism = ctx.getCountLong();
 * long aggregation = ctx.getAggregationCount();
 * boolean fixed = ctx.isFixedCount();
 *
 * // Wrap an existing context
 * ProcessContext baseCtx = ProcessContext.base();
 * ParallelProcessContext pctx = ParallelProcessContext.of(baseCtx);
 * }</pre>
 *
 * @see ProcessContext
 * @see ProcessContextBase
 * @see ParallelProcess
 * @see Countable
 *
 * @author Michael Murray
 */
public class ParallelProcessContext extends ProcessContextBase implements Countable {
	private long parallelism;
	private long aggregationCount;
	private boolean fixed;

	/**
	 * Constructs a parallel process context with the specified parameters.
	 *
	 * @param depth            the depth in the process tree hierarchy
	 * @param parallelism      the parallelism count at this level
	 * @param aggregationCount the number of inputs aggregated per output
	 * @param fixed            whether the parallelism is fixed at compile time
	 * @throws IllegalArgumentException if parallelism exceeds {@code Integer.MAX_VALUE}
	 */
	protected ParallelProcessContext(int depth, long parallelism, long aggregationCount, boolean fixed) {
		super(depth);
		this.parallelism = parallelism;
		this.aggregationCount = aggregationCount;
		this.fixed = fixed;

		if (parallelism > Integer.MAX_VALUE) {
			throw new IllegalArgumentException();
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the parallelism count at this level of the process tree
	 */
	@Override
	public long getCountLong() { return parallelism; }

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code true} if parallelism is determined at compile time
	 */
	@Override
	public boolean isFixedCount() { return fixed; }

	/**
	 * The largest number of inputs that may be referenced by the process when
	 * computing a single output. For many operations this will be 1, as the
	 * value for each position in a parallel output is computed from a single
	 * position in each of its inputs. However, for operations that perform
	 * comparisons of different members of the input, add many elements
	 * together, or otherwise require examination of multiple positions in
	 * the input to compute a single output - this number can be much larger.
	 *
	 * @return the aggregation count for this context
	 */
	public long getAggregationCount() { return aggregationCount; }

	/**
	 * Converts a {@link ProcessContext} to a {@link ParallelProcessContext}.
	 *
	 * <p>If the context is already a {@code ParallelProcessContext}, it is returned directly.
	 * Otherwise, a new context is created with default values (parallelism 1, aggregation 1,
	 * fixed count).</p>
	 *
	 * @param ctx the process context to convert
	 * @return the context as a {@code ParallelProcessContext}
	 */
	public static ParallelProcessContext of(ProcessContext ctx) {
		return ctx instanceof ParallelProcessContext ? (ParallelProcessContext) ctx :
				new ParallelProcessContext(ctx.getDepth(), 1, 1, true);
	}

	/**
	 * Creates a new context from a parallel process at the specified depth.
	 *
	 * @param depth the depth in the process tree
	 * @param c     the parallel process to derive context from
	 * @return a new context with the process's parallelism characteristics
	 */
	public static ParallelProcessContext of(int depth, ParallelProcess c) {
		return of(depth, c, 1);
	}

	/**
	 * Creates a new context from a parallel process with a specified aggregation count.
	 *
	 * @param depth            the depth in the process tree
	 * @param c                the parallel process to derive context from
	 * @param aggregationCount the aggregation count for this context
	 * @return a new context with the specified characteristics
	 */
	public static ParallelProcessContext of(int depth, ParallelProcess c, long aggregationCount) {
		return new ParallelProcessContext(depth, c.getParallelism(), aggregationCount, c.isFixedCount());
	}

	/**
	 * Creates a new context for a parallel process, incorporating parent context information.
	 *
	 * @param ctx the parent process context
	 * @param c   the parallel process to derive context from
	 * @return a new context combining parent and process characteristics
	 */
	public static ParallelProcessContext of(ProcessContext ctx, ParallelProcess c) {
		return of(ctx, c, 1);
	}

	/**
	 * Creates a new context for a parallel process with specified aggregation count.
	 *
	 * <p>This factory method implements context propagation logic:</p>
	 * <ul>
	 *   <li>If the parent has higher parallelism and aggregation, it may be preserved</li>
	 *   <li>Single-child processes inherit the parent context unchanged</li>
	 *   <li>The maximum aggregation count between parent and child is used</li>
	 *   <li>Depth is incremented from the parent context</li>
	 * </ul>
	 *
	 * @param ctx              the parent process context (may be {@code null})
	 * @param c                the parallel process to derive context from (may be {@code null})
	 * @param aggregationCount the base aggregation count for this context
	 * @return a new context with appropriate propagation of parent values
	 */
	public static ParallelProcessContext of(ProcessContext ctx, ParallelProcess c, long aggregationCount) {
		if (ctx instanceof ParallelProcessContext) {
			ParallelProcessContext pctx = (ParallelProcessContext) ctx;

			boolean parent = c != null && c.getChildren().size() > 1;
			if (!parent) return pctx;

			if (pctx.getCountLong() > c.getCountLong() && pctx.getAggregationCount() >= aggregationCount) {
				if (pctx.isFixedCount() || !c.isFixedCount()) return pctx;
			}

			if (pctx.getAggregationCount() > aggregationCount) {
				aggregationCount = pctx.getAggregationCount();
			}
		}

		return ParallelProcessContext.of(
				Optional.ofNullable(ctx)
						.map(ProcessContext::getDepth).orElse(0) + 1, c, aggregationCount);
	}
}
