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

import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.relation.Operation;
import io.almostrealism.compute.Process;

import java.util.Collection;

public interface OperationComputation<T> extends Computation<T>, Operation {
	@Override
	default Process<Process<?, ?>, Runnable> isolate() {
		return new IsolatedProcess(this);
	}

	class IsolatedProcess implements Process<Process<?, ?>, Runnable>, OperationInfo {
		private Operation op;

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
