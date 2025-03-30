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

public class ParallelProcessContext extends ProcessContextBase implements Countable {
	private long parallelism;
	private long aggregationCount;
	private boolean fixed;

	protected ParallelProcessContext(int depth, long parallelism, long aggregationCount, boolean fixed) {
		super(depth);
		this.parallelism = parallelism;
		this.aggregationCount = aggregationCount;
		this.fixed = fixed;

		if (parallelism > Integer.MAX_VALUE) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public long getCountLong() { return parallelism; }

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
	 */
	public long getAggregationCount() { return aggregationCount; }

	public static ParallelProcessContext of(ProcessContext ctx) {
		return ctx instanceof ParallelProcessContext ? (ParallelProcessContext) ctx :
				new ParallelProcessContext(ctx.getDepth(), 1, 1, true);
	}

	public static ParallelProcessContext of(int depth, ParallelProcess c) {
		return of(depth, c, 1);
	}

	public static ParallelProcessContext of(int depth, ParallelProcess c, long aggregationCount) {
		return new ParallelProcessContext(depth, c.getParallelism(), aggregationCount, c.isFixedCount());
	}

	public static ParallelProcessContext of(ProcessContext ctx, ParallelProcess c) {
		return of(ctx, c, 1);
	}

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
