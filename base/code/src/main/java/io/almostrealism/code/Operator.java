/*
 * Copyright 2023 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.code;

import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import java.util.Collection;

/**
 * A {@link ProducerComputation} that also implements the {@link io.almostrealism.compute.Process}
 * interface, representing a computation that produces evaluable values.
 *
 * <p>{@code Operator} is the value-producing counterpart to {@link OperationComputation}.
 * It wraps a computation that returns a typed result and provides an {@link #isolate()} method
 * to break expression embedding via {@link IsolatedProcess}.</p>
 *
 * @param <T> the type of value produced by this operator
 *
 * @see ProducerComputation
 * @see OperationComputation
 * @see io.almostrealism.compute.Process
 */
public interface Operator<T> extends Process<Process<?, ?>, Evaluable<? extends T>>, ProducerComputation<T> {
	/**
	 * {@inheritDoc}
	 *
	 * <p>Wraps this operator in an {@link IsolatedProcess} to isolate it from the expression graph.
	 *
	 * @return an isolated process wrapping this operator
	 */
	@Override
	default Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return new IsolatedProcess<>(this);
	}

	/**
	 * A process wrapper that isolates an {@link Operator} from the surrounding expression graph.
	 *
	 * <p>Used to break expression embedding when an operator's output should not be inlined
	 * into its callers during optimization.
	 *
	 * @param <T> the type of value produced by the wrapped operator
	 */
	class IsolatedProcess<T> implements Process<Process<?, ?>, Evaluable<? extends T>>, Producer<T> {
		/** The wrapped operator. */
		private Operator<T> op;

		/**
		 * Creates an isolated process wrapping the given operator.
		 *
		 * @param op the operator to isolate
		 */
		public IsolatedProcess(Operator<T> op) {
			this.op = op;
		}

		@Override
		public Collection<Process<?, ?>> getChildren() {
			return op.getChildren();
		}

		@Override
		public Evaluable<T> get() {
			return op.get();
		}

		@Override
		public long getOutputSize() {
			return op.getOutputSize();
		}
	}
}
