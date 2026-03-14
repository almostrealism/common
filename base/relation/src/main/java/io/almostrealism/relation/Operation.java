/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.compute.Process;

import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * An {@link Operation} represents a side-effecting computation that produces
 * a {@link Runnable} for execution.
 *
 * <p>Unlike {@link Producer}, which produces values, an {@link Operation}
 * represents computations that are executed primarily for their side effects
 * (such as writing to memory, updating state, or performing I/O). The result
 * of compiling an {@link Operation} is a {@link Runnable} that can be executed.</p>
 *
 * <h2>Relationship to Producer</h2>
 * <p>{@link Operation} follows the same two-phase pattern as {@link Producer}:</p>
 * <ol>
 *   <li><b>Description Phase:</b> Build an operation graph</li>
 *   <li><b>Execution Phase:</b> Call {@link #get()} to compile, then {@link Runnable#run()}</li>
 * </ol>
 *
 * <p>The key difference is that {@link Producer#get()} returns an {@link Evaluable}
 * (which produces values), while {@link Operation#get()} returns a {@link Runnable}
 * (which performs actions).</p>
 *
 * <h2>Process Integration</h2>
 * <p>{@link Operation} extends {@link Process}, enabling:</p>
 * <ul>
 *   <li>Hierarchical composition of operations</li>
 *   <li>Optimization through process tree analysis</li>
 *   <li>Isolation for independent execution contexts</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create an operation
 * Operation writeOp = createWriteOperation(data, destination);
 *
 * // Optimize (optional)
 * Supplier<Runnable> optimized = Operation.optimized(writeOp);
 *
 * // Execute
 * optimized.get().run();
 * }</pre>
 *
 * @see Producer
 * @see Process
 * @see Runnable
 *
 * @author Michael Murray
 */
public interface Operation extends Process<Process<?, ?>, Runnable>, Supplier<Runnable> {
	/**
	 * Creates an isolated copy of this operation for independent execution.
	 *
	 * <p>Isolation allows the operation to be executed in a separate context
	 * without affecting or being affected by other operations.</p>
	 *
	 * @return an isolated process wrapping this operation
	 */
	@Override
	default Process<Process<?, ?>, Runnable> isolate() {
		return new IsolatedProcess(this);
	}

	/**
	 * Creates an {@link Operation} from a simple {@link Supplier} of {@link Runnable}.
	 *
	 * <p>This factory method wraps a supplier in the {@link Operation} interface,
	 * creating a leaf operation with no children in the process tree.</p>
	 *
	 * @param supplier the supplier to wrap
	 * @return an {@link Operation} that delegates to the supplier
	 */
	static Operation of(Supplier<Runnable> supplier) {
		return new Operation() {

			@Override
			public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

			@Override
			public Runnable get() { return supplier.get(); }
		};
	}

	/**
	 * Applies optimization to a process that produces a {@link Runnable}.
	 *
	 * <p>If the process implements {@link Process}, optimization transformations
	 * are applied. Otherwise, the original supplier is returned unchanged.</p>
	 *
	 * @param <P> the type of the process
	 * @param process the process to optimize
	 * @return an optimized supplier of {@link Runnable}
	 */
	static <P extends Supplier<Runnable>> Supplier<Runnable> optimized(P process) {
		if (process instanceof Process) {
			return ((Process<?, Runnable>) process).optimize();
		} else {
			return process;
		}
	}

	/**
	 * An isolated wrapper around an {@link Operation} for independent execution.
	 *
	 * <p>This class provides isolation by wrapping an operation in a separate
	 * process context. The isolated process delegates all behavior to the
	 * underlying operation while maintaining its own identity.</p>
	 */
	class IsolatedProcess implements Process<Process<?, ?>, Runnable> {
		private Operation op;

		private IsolatedProcess(Operation op) {
			this.op = op;
		}

		@Override
		public Collection<Process<?, ?>> getChildren() { return op.getChildren(); }

		@Override
		public Runnable get() { return op.get(); }

		@Override
		public long getOutputSize() {
			return op.getOutputSize();
		}
	}
}
