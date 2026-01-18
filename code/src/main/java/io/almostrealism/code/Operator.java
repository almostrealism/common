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

public interface Operator<T> extends Process<Process<?, ?>, Evaluable<? extends T>>, ProducerComputation<T> {
	@Override
	default Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return new IsolatedProcess<>(this);
	}

	class IsolatedProcess<T> implements Process<Process<?, ?>, Evaluable<? extends T>>, Producer<T> {
		private Operator<T> op;

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
