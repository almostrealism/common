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
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.ParallelProcessContext;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.ProcessContext;
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public interface ComputableParallelProcess<P extends Process<?, ?>, T> extends ParallelProcess<P, T>, OperationInfo {
	boolean enableOptimizationLog = false;

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

	@Override
	default ParallelProcessContext createContext(ProcessContext ctx) {
		return ComputableProcessContext.of(ctx, this);
	}

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
	 * Delegates to {@link ParallelProcess#optimize(ProcessContext)} while also ensuring that
	 * the operation {@link OperationMetadata#getId() id} is preserved.
	 *
	 * @see  OperationMetadata#getId()
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
	 * Delegates to {@link ComputableParallelProcess#generate(List)} while also ensuring that
	 * the operation {@link OperationMetadata#getId() id} is preserved.
	 *
	 * @see  OperationMetadata#getId()
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
