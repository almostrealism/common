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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.HardwareOperator;

import java.util.HashMap;
import java.util.Map;

public class ComputationInstructionsManager
		extends AbstractInstructionSetManager<DefaultExecutionKey>
		implements ComputableInstructionSetManager<DefaultExecutionKey> {
	private Scope<?> scope;
	private InstructionSet operators;

	private Map<DefaultExecutionKey, Integer> outputArgIndices;
	private Map<DefaultExecutionKey, Integer> outputOffsets;

	public ComputationInstructionsManager(ComputeContext<?> computeContext,
										  Scope<?> scope) {
		super(computeContext);
		this.scope = scope;
		this.outputArgIndices = new HashMap<>();
		this.outputOffsets = new HashMap<>();
	}

	@Override
	public int getOutputArgumentIndex(DefaultExecutionKey key) {
		return outputArgIndices.get(key);
	}

	public void setOutputArgumentIndex(DefaultExecutionKey key, int outputArgIndex) {
		this.outputArgIndices.put(key, outputArgIndex);
	}

	@Override
	public int getOutputOffset(DefaultExecutionKey key) {
		return outputOffsets.get(key);
	}

	public void setOutputOffset(DefaultExecutionKey key, int outputOffset) {
		this.outputOffsets.put(key, outputOffset);
	}

	@Override
	public synchronized Execution getOperator(DefaultExecutionKey key) {
		if (operators == null || operators.isDestroyed()) {
			operators = getComputeContext().deliver(scope);
			HardwareOperator.recordCompilation(!getComputeContext().isCPU());
		}

		return operators.get(key.getFunctionName(), key.getArgsCount());
	}
}
