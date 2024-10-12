/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.instructions;

import io.almostrealism.code.Execution;
import io.almostrealism.relation.Process;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.AcceleratedOperation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ProcessTreeInstructionsManager implements ComputableInstructionSetManager<ProcessTreePositionKey> {
	private Map<ProcessTreePositionKey, ProcessInstructions> instructions;

	public ProcessTreeInstructionsManager() {
		instructions = new HashMap<>();
	}

	@Override
	public Execution getOperator(ProcessTreePositionKey key) {
		return instructions.get(key).getOperator();
	}

	public <T, P extends Supplier<T>> Supplier<T> apply(ProcessTreePositionKey key, P process) {
		T compiled = process.get();

		if (!(compiled instanceof AcceleratedOperation<?>)) {
			return process;
		}

		AcceleratedComputationOperation<?> op = (AcceleratedComputationOperation) compiled;
		instructions.put(key, new ProcessInstructions(op.getExecutionKey(), op.getInstructionSetManager()));
		op.compile(this, key);
		return null;
	}

	@Override
	public int getOutputArgumentIndex(ProcessTreePositionKey key) {
		return instructions.get(key).getOutputArgumentIndex();
	}

	@Override
	public int getOutputOffset(ProcessTreePositionKey key) {
		return instructions.get(key).getOutputOffset();
	}

	public <P extends Process<?, ?>, T, V extends Process<P, T>> Process<P, T> applyAll(V process) {
		// Traverse process tree, calling apply for each node
		throw new UnsupportedOperationException();
	}

	protected class ProcessInstructions<K extends ExecutionKey> {
		private K key;
		private ComputableInstructionSetManager<K> instructions;

		public ProcessInstructions(K key,
								   ComputableInstructionSetManager<K> instructions) {
			this.key = key;
			this.instructions = instructions;
		}

		public K getKey() {
			return key;
		}

		public ComputableInstructionSetManager<K> getInstructions() {
			return instructions;
		}

		public Execution getOperator() {
			return getInstructions().getOperator(getKey());
		}

		public int getOutputArgumentIndex() {
			return getInstructions().getOutputArgumentIndex(getKey());
		}

		public int getOutputOffset() {
			return getInstructions().getOutputOffset(getKey());
		}
	}
}
