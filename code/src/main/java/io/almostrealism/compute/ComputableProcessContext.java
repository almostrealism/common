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

import io.almostrealism.code.OperationInfo;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.ParallelProcessContext;
import io.almostrealism.relation.ProcessContext;

import java.util.Collections;
import java.util.List;

public class ComputableProcessContext extends ParallelProcessContext {
	private List<ComputeRequirement> requirements;

	public ComputableProcessContext(int depth, long parallelism, boolean fixed,
									List<ComputeRequirement> requirements) {
		super(depth, parallelism, fixed);
		this.requirements = requirements;
	}

	public List<ComputeRequirement> getRequirements() {
		return requirements == null ? null : Collections.unmodifiableList(requirements);
	}

	public static ComputableProcessContext of(ParallelProcessContext c, List<ComputeRequirement> requirements) {
		return new ComputableProcessContext(c.getDepth(), c.getCountLong(), c.isFixedCount(), requirements);
	}

	public static ComputableProcessContext of(int depth, ParallelProcess c) {
		return new ComputableProcessContext(depth, c.getParallelism(), c.isFixedCount(), extractRequirements(c));
	}

	public static ComputableProcessContext of(ProcessContext ctx, ParallelProcess c) {
		return ComputableProcessContext.of(ParallelProcessContext.of(ctx, c), extractRequirements(c));
	}

	public static List<ComputeRequirement> extractRequirements(ParallelProcess process) {
		if (process instanceof OperationInfo) {
			return ((OperationInfo) process).getComputeRequirements();
		} else {
			return null;
		}
	}
}
