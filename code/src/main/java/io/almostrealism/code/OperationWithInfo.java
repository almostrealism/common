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

import io.almostrealism.relation.Operation;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Process;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public class OperationWithInfo implements Operation, OperationInfo {
	private final OperationMetadata metadata;
	private final Operation operation;

	public OperationWithInfo(OperationMetadata metadata, Operation operation) {
		this.metadata = metadata;
		this.operation = operation;
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public Collection<Process<?, ?>> getChildren() { return operation.getChildren(); }

	@Override
	public void compact() { operation.compact(); }

	@Override
	public Runnable get() { return new RunnableWithInfo(getMetadata(), operation.get()); }

	@Override
	public Process<Process<?, ?>, Runnable> isolate() {
		return this;
	}

	@Override
	public Parent<Process<?, ?>> generate(List<Process<?, ?>> children) {
		return this;
	}

	public static OperationWithInfo of(OperationMetadata metadata, Operation operation) {
		return new OperationWithInfo(metadata, operation);
	}

	public static OperationWithInfo of(OperationMetadata metadata, Supplier<Runnable> op) {
		return OperationWithInfo.of(metadata, Operation.of(op));
	}

	public static class RunnableWithInfo implements OperationInfo, Runnable {
		private final OperationMetadata metadata;
		private final Runnable runnable;

		public RunnableWithInfo(OperationMetadata metadata, Runnable runnable) {
			this.metadata = metadata;
			this.runnable = runnable;
		}

		@Override
		public OperationMetadata getMetadata() { return metadata; }

		@Override
		public void run() { runnable.run(); }

		public static RunnableWithInfo of(OperationMetadata metadata, Runnable runnable) {
			return new RunnableWithInfo(metadata, runnable);
		}
	}
}
