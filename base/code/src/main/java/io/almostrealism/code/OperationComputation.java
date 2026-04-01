/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.compute.Process;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Operation;

import java.util.Collection;

/**
 * A {@link Computation} that also implements {@link Operation}, representing a computation
 * whose result is consumed for its side effects (i.e., a void-valued computation).
 *
 * <p>{@code OperationComputation} bridges the {@link Computation} model (which owns a
 * {@link io.almostrealism.scope.Scope} and can be compiled) with the {@link Operation}
 * model (which produces a {@link Runnable} for side-effectful execution). The
 * {@link #isolate()} method wraps this computation in an {@link IsolatedProcess} to
 * break expression embedding and allow separate evaluation.</p>
 *
 * @param <T> the output type (typically {@code Void} for side-effect computations)
 *
 * @see Computation
 * @see Operation
 * @see io.almostrealism.compute.Process
 */
public interface OperationComputation<T> extends Computation<T>, Operation {
	/**
	 * {@inheritDoc}
	 *
	 * <p>Wraps this computation in an {@link IsolatedProcess} to isolate expression embedding.
	 *
	 * @return an isolated process wrapping this computation
	 */
	@Override
	default Process<Process<?, ?>, Runnable> isolate() {
		return new IsolatedProcess(this);
	}

	/**
	 * A process wrapper that isolates an {@link Operation} from the surrounding expression graph.
	 *
	 * <p>This class is the mechanism by which expression embedding is broken — wrapping an
	 * operation in {@code IsolatedProcess} prevents the optimizer from inlining the operation's
	 * expression tree into its callers.</p>
	 */
	class IsolatedProcess implements Process<Process<?, ?>, Runnable>, OperationInfo {
		/** The wrapped operation. */
		private Operation op;

		/**
		 * Creates an isolated process wrapping the given operation.
		 *
		 * @param op the operation to isolate
		 */
		private IsolatedProcess(Operation op) {
			this.op = op;
		}

		@Override
		public Collection<Process<?, ?>> getChildren() { return op.getChildren(); }

		@Override
		public Runnable get() { return op.get(); }

		@Override
		public OperationMetadata getMetadata() {
			return op instanceof OperationInfo ? ((OperationInfo) op).getMetadata() : null;
		}

		@Override
		public long getOutputSize() {
			return op.getOutputSize();
		}

		@Override
		public String describe() {
			return getMetadata().getShortDescription();
		}
	}
}
