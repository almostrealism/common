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

import io.almostrealism.profile.OperationInfo;

import java.util.Collections;
import java.util.List;

/**
 * A specialized {@link ParallelProcessContext} that includes compute requirements
 * for hardware-accelerated operations.
 *
 * <p>This context extends {@link ParallelProcessContext} to add support for
 * {@link ComputeRequirement}s, which specify hardware capabilities or constraints
 * needed for executing the computation (e.g., GPU requirements, memory constraints,
 * specific hardware backends).</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Create from a parallel process
 * ComputableProcessContext ctx = ComputableProcessContext.of(depth, parallelProcess);
 *
 * // Create from an existing context with requirements
 * ComputableProcessContext ctx = ComputableProcessContext.of(
 *     parallelContext,
 *     Arrays.asList(ComputeRequirement.GPU)
 * );
 * }</pre>
 *
 * @see ParallelProcessContext
 * @see ComputeRequirement
 * @see ParallelProcess
 *
 * @author Michael Murray
 */
public class ComputableProcessContext extends ParallelProcessContext {
	private List<ComputeRequirement> requirements;

	/**
	 * Constructs a new {@code ComputableProcessContext} with the specified parameters.
	 *
	 * @param depth            the nesting depth of this context in the process hierarchy
	 * @param parallelism      the degree of parallelism (number of parallel execution units)
	 * @param aggregationCount the number of elements to aggregate in batch operations
	 * @param fixed            whether the parallelism count is fixed or may vary
	 * @param requirements     the list of compute requirements for hardware acceleration,
	 *                         or {@code null} if no specific requirements are needed
	 */
	public ComputableProcessContext(int depth, long parallelism, long aggregationCount,
									boolean fixed, List<ComputeRequirement> requirements) {
		super(depth, parallelism, aggregationCount, fixed);
		this.requirements = requirements;
	}

	/**
	 * Returns an unmodifiable view of the compute requirements for this context.
	 *
	 * @return an unmodifiable list of {@link ComputeRequirement}s, or {@code null}
	 *         if no requirements have been specified
	 */
	public List<ComputeRequirement> getRequirements() {
		return requirements == null ? null : Collections.unmodifiableList(requirements);
	}

	/**
	 * Creates a new {@code ComputableProcessContext} from an existing
	 * {@link ParallelProcessContext} with the specified compute requirements.
	 *
	 * @param c            the source parallel process context to copy settings from
	 * @param requirements the compute requirements to associate with the new context
	 * @return a new {@code ComputableProcessContext} with the combined settings
	 */
	public static ComputableProcessContext of(ParallelProcessContext c, List<ComputeRequirement> requirements) {
		return new ComputableProcessContext(c.getDepth(), c.getCountLong(),
					c.getAggregationCount(), c.isFixedCount(), requirements);
	}

	/**
	 * Creates a new {@code ComputableProcessContext} from a {@link ParallelProcess}
	 * at the specified depth.
	 *
	 * <p>The compute requirements are automatically extracted from the process
	 * if it implements {@link OperationInfo}.</p>
	 *
	 * @param depth the nesting depth for the new context
	 * @param c     the parallel process to extract settings and requirements from
	 * @return a new {@code ComputableProcessContext} initialized from the process
	 */
	public static ComputableProcessContext of(int depth, ParallelProcess c) {
		return new ComputableProcessContext(depth, c.getParallelism(), 1, c.isFixedCount(), extractRequirements(c));
	}

	/**
	 * Creates a new {@code ComputableProcessContext} by combining an existing
	 * {@link ProcessContext} with a {@link ParallelProcess}.
	 *
	 * <p>This method first creates a {@link ParallelProcessContext} from the
	 * given context and process, then adds compute requirements extracted
	 * from the process.</p>
	 *
	 * @param ctx the existing process context
	 * @param c   the parallel process to merge with the context
	 * @return a new {@code ComputableProcessContext} with combined settings
	 */
	public static ComputableProcessContext of(ProcessContext ctx, ParallelProcess c) {
		return ComputableProcessContext.of(ParallelProcessContext.of(ctx, c), extractRequirements(c));
	}

	/**
	 * Extracts compute requirements from a {@link ParallelProcess} if it
	 * implements {@link OperationInfo}.
	 *
	 * @param process the parallel process to extract requirements from
	 * @return the list of compute requirements if the process is an
	 *         {@link OperationInfo}, otherwise {@code null}
	 */
	public static List<ComputeRequirement> extractRequirements(ParallelProcess process) {
		if (process instanceof OperationInfo) {
			return ((OperationInfo) process).getComputeRequirements();
		} else {
			return null;
		}
	}
}
