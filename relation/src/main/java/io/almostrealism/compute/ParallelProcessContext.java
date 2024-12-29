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
	private boolean fixed;

	protected ParallelProcessContext(int depth, long parallelism, boolean fixed) {
		super(depth);
		this.parallelism = parallelism;
		this.fixed = fixed;
	}

	@Override
	public long getCountLong() { return parallelism; }

	@Override
	public boolean isFixedCount() { return fixed; }

	public static ParallelProcessContext of(int depth, ParallelProcess c) {
		return new ParallelProcessContext(depth, c.getParallelism(), c.isFixedCount());
	}

	public static ParallelProcessContext of(ProcessContext ctx, ParallelProcess c) {
		if (ctx instanceof ParallelProcessContext) {
			ParallelProcessContext pctx = (ParallelProcessContext) ctx;

			boolean parent = c != null && c.getChildren().size() > 1;
			if (!parent) return pctx;

			if (pctx.getCountLong() > c.getCountLong()) {
				if (pctx.isFixedCount() || !c.isFixedCount()) return pctx;
			}
		}

		return ParallelProcessContext.of(
				Optional.ofNullable(ctx)
						.map(ProcessContext::getDepth).orElse(0) + 1, c);
	}
}
