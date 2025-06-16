/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.HardwareOperator;

import java.util.HashMap;
import java.util.Map;

public class ScopeInstructionsManager<K extends ExecutionKey>
		extends AbstractInstructionSetManager<K>
		implements ComputableInstructionSetManager<K> {

	// TODO  It is probably not necessary to store the entire Scope,
	// TODO  and there may be a lot of memory to save by storing only
	// TODO  the signature, function name, and argument count
	private Scope<?> scope;
	private InstructionSet operators;

	private Map<K, Integer> outputArgIndices;
	private Map<K, Integer> outputOffsets;

	public ScopeInstructionsManager(ComputeContext<?> computeContext,
									Scope<?> scope) {
		super(computeContext);
		this.scope = scope;
		this.outputArgIndices = new HashMap<>();
		this.outputOffsets = new HashMap<>();
	}

	@Override
	public int getOutputArgumentIndex(K key) {
		Integer argIndex = outputArgIndices.get(key);
		if (argIndex == null) {
			return -1;
		}

		return argIndex;
	}

	public void setOutputArgumentIndex(K key, int outputArgIndex) {
		this.outputArgIndices.put(key, outputArgIndex);
	}

	@Override
	public int getOutputOffset(K key) {
		return outputOffsets.get(key);
	}

	public void setOutputOffset(K key, int outputOffset) {
		this.outputOffsets.put(key, outputOffset);
	}

	protected synchronized InstructionSet getInstructionSet() {
		if (operators == null || operators.isDestroyed()) {
			operators = getComputeContext().deliver(scope);
			HardwareOperator.recordCompilation(!getComputeContext().isCPU());
		}

		return operators;
	}

	@Override
	public synchronized Execution getOperator(K key) {
		if (operators == null || operators.isDestroyed()) {
			operators = getComputeContext().deliver(scope);
			HardwareOperator.recordCompilation(!getComputeContext().isCPU());
		}

		return operators.get(scope.getName(), scope.getArguments().size());
	}
}
