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

package io.almostrealism.code;

import io.almostrealism.compute.ComputableProcessContext;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.compute.ParallelProcessContext;
import io.almostrealism.compute.Process;
import io.almostrealism.compute.ProcessContext;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link ComputableParallelProcess} extends {@link ParallelProcess} with compute-specific
 * functionality for managing parallel computations in the Almost Realism framework.
 * This interface provides enhanced support for:
 * <ul>
 *   <li>Compute requirements handling for hardware-specific execution</li>
 *   <li>Process isolation based on compute requirements</li>
 *   <li>Optimization with operation metadata preservation</li>
 *   <li>Optional debug logging during optimization</li>
 * </ul>
 *
 * <p>This interface is typically implemented by {@link ComputableBase} and its subclasses,
 * providing the foundation for computations that can be parallelized and optimized.
 *
 * <h2>Compute Requirements</h2>
 * <p>Compute requirements (from {@link OperationInfo#getComputeRequirements()}) specify
 * execution preferences such as CPU or GPU. When a process has requirements that differ
 * from its context, it becomes an "isolation target" and may be executed separately
 * to ensure correct hardware usage.
 *
 * <h2>Optimization</h2>
 * <p>The optimization methods in this interface ensure that operation metadata
 * (particularly the operation ID) is preserved when creating optimized versions
 * of computations. This is important for profiling and debugging.
 *
 * @param <P> the type of child processes
 * @param <T> the type of result produced by this process
 *
 * @author Michael Murray
 * @see ParallelProcess
 * @see ComputableBase
 * @see ComputeRequirement
 */
public interface ComputableParallelProcess<P extends Process<?, ?>, T> extends ParallelProcess<P, T>, OperationInfo {

	/**
	 * Flag to enable detailed logging during optimization.
	 * When enabled, optimization start and end events are logged with process names.
	 * Disabled by default for performance.
	 */
	boolean enableOptimizationLog = false;

	/**
	 * Determines if this process should be isolated from its parent context based on
	 * compute requirements.
	 *
	 * <p>A process becomes an isolation target when it has compute requirements that
	 * are not present in the current context. This ensures that processes requiring
	 * specific hardware (e.g., GPU) are executed in an appropriate environment.
	 *
	 * @param context the current process context
	 * @return {@code true} if this process should be isolated, {@code false} otherwise
	 */
	@Override
	default boolean isIsolationTarget(ProcessContext context) {
		if (getComputeRequirements() == null || getComputeRequirements().isEmpty())
			return ParallelProcess.super.isIsolationTarget(context);

		List<ComputeRequirement> requirements = new ArrayList<>();

		if (context instanceof ComputableProcessContext) {
			ComputableProcessContext ctx = (ComputableProcessContext) context;
			if (ctx.getRequirements() != null)
				requirements.addAll(ctx.getRequirements());
		}

		// If this process has requirements that are not part of the
		// existing context, then it must be isolated
		if (getComputeRequirements().stream().anyMatch(r -> !requirements.contains(r))) {
			return true;
		}

		return ParallelProcess.super.isIsolationTarget(context);
	}

	/**
	 * Creates a new {@link ParallelProcessContext} for this process.
	 * The context is created as a {@link ComputableProcessContext} which includes
	 * compute requirement information.
	 *
	 * @param ctx the parent process context
	 * @return a new computable process context for this process
	 */
	@Override
	default ParallelProcessContext createContext(ProcessContext ctx) {
		return ComputableProcessContext.of(ctx, this);
	}

	/**
	 * Optimizes the given process within the specified context.
	 *
	 * <p>This method extends the parent optimization with optional debug logging.
	 * When {@link #enableOptimizationLog} is {@code true}, start and end events
	 * are logged with indentation based on context depth.
	 *
	 * @param ctx the process context for optimization
	 * @param process the process to optimize
	 * @return the optimized process
	 */
	@Override
	default Process<P, T> optimize(ProcessContext ctx, Process<P, T> process) {
		Supplier<String> info = enableOptimizationLog ? () -> {
			StringBuilder msg = new StringBuilder();
			for (int i = 0; i < ctx.getDepth(); i++) {
				msg.append("\t");
			}

			msg.append("start optimize ").append(OperationInfo.nameWithId(process));
			return msg.toString();
		} : null;

		if (info != null) {
			Console.root.features(ComputableParallelProcess.class)
					.log("start optimize " + info.get());
		}

		Process<P, T> result = ParallelProcess.super.optimize(ctx, process);

		if (info != null) {
			Console.root.features(ComputableParallelProcess.class)
					.log("end optimize " + info.get());
		}

		return result;
	}

	/**
	 * Optimizes this parallel process for the given context while preserving
	 * operation metadata.
	 *
	 * <p>This method delegates to {@link ParallelProcess#optimize(ProcessContext)}
	 * and ensures that the operation {@link OperationMetadata#getId() id} is
	 * preserved in the optimized result. This is important for maintaining
	 * identity across optimization passes for profiling and debugging.
	 *
	 * @param ctx the process context for optimization
	 * @return the optimized parallel process with preserved metadata ID
	 * @throws UnsupportedOperationException if the result has no metadata
	 * @see OperationMetadata#getId()
	 */
	@Override
	default ParallelProcess<P, T> optimize(ProcessContext ctx) {
		ParallelProcess<P, T> result = ParallelProcess.super.optimize(ctx);

		if (result instanceof OperationInfo) {
			if (((OperationInfo) result).getMetadata() == null) {
				throw new UnsupportedOperationException();
			}

			((OperationInfo) result).getMetadata().setId(getMetadata().getId());
		}

		return result;
	}

	/**
	 * Generates a replacement parallel process with the specified inputs while
	 * preserving operation metadata.
	 *
	 * <p>This method delegates to {@link ParallelProcess#generate(List)} and ensures
	 * that the operation {@link OperationMetadata#getId() id} is preserved in the
	 * replacement. This maintains identity for profiling and debugging purposes.
	 *
	 * @param inputs the list of input processes for the replacement
	 * @return the replacement parallel process with preserved metadata ID
	 * @throws UnsupportedOperationException if the result has no metadata
	 * @see OperationMetadata#getId()
	 */
	default ParallelProcess<P, T> generateReplacement(List<P> inputs) {
		ParallelProcess<P, T> result = generate(inputs);

		if (result instanceof OperationInfo) {
			if (((OperationInfo) result).getMetadata() == null) {
				throw new UnsupportedOperationException();
			}

			((OperationInfo) result).getMetadata().setId(getMetadata().getId());
		}

		return result;
	}
}
