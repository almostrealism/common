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

public interface Operation extends Process<Process<?, ?>, Runnable>, Supplier<Runnable> {
	@Override
	default Process<Process<?, ?>, Runnable> isolate() {
		return new IsolatedProcess(this);
	}

	static Operation of(Supplier<Runnable> supplier) {
		return new Operation() {

			@Override
			public Collection<Process<?, ?>> getChildren() { return Collections.emptyList(); }

			@Override
			public Runnable get() { return supplier.get(); }
		};
	}

	static <P extends Supplier<Runnable>> Supplier<Runnable> optimized(P process) {
		if (process instanceof Process) {
			return ((Process<?, Runnable>) process).optimize();
		} else {
			return process;
		}
	}

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
