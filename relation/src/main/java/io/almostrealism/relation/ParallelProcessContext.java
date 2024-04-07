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

package io.almostrealism.relation;

public class ParallelProcessContext implements ProcessContext, Countable {
	private long count;
	private boolean fixed;

	protected ParallelProcessContext(long count, boolean fixed) {
		this.count = count;
		this.fixed = fixed;
	}

	@Override
	public long getCountLong() { return count; }

	@Override
	public boolean isFixedCount() { return fixed; }

	public static ParallelProcessContext of(Countable c) {
		return new ParallelProcessContext(c.getCountLong(), c.isFixedCount());
	}

	public static ParallelProcessContext of(ProcessContext ctx, Countable c) {
		if (ctx instanceof ParallelProcessContext) {
			ParallelProcessContext pctx = (ParallelProcessContext) ctx;

			boolean parent = c instanceof Parent && ((Parent) c).getChildren().size() > 1;
			if (!parent) return pctx;

			if (pctx.getCountLong() > c.getCountLong()) {
				if (pctx.isFixedCount() || !c.isFixedCount()) return pctx;
			}
		}

		return ParallelProcessContext.of(c);
	}
}
