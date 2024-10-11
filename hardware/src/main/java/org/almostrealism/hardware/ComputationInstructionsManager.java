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

package org.almostrealism.hardware;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;

public class ComputationInstructionsManager extends AbstractInstructionSetManager {
	private Scope<?> scope;
	private InstructionSet operators;
	private int outputArgIndex;
	private int outputOffset;

	public ComputationInstructionsManager(ComputeContext<?> computeContext,
										  String functionName, int argsCount,
										  Scope<?> scope) {
		super(computeContext, functionName, argsCount);
		this.scope = scope;
	}

	public int getOutputArgumentIndex() {
		return outputArgIndex;
	}

	public void setOutputArgumentIndex(int outputArgIndex) {
		this.outputArgIndex = outputArgIndex;
	}

	public int getOutputOffset() {
		return outputOffset;
	}

	public void setOutputOffset(int outputOffset) {
		this.outputOffset = outputOffset;
	}

	@Override
	public synchronized Execution getOperator() {
		if (operators == null || operators.isDestroyed()) {
			operators = getComputeContext().deliver(scope);
			HardwareOperator.recordCompilation(!getComputeContext().isCPU());
		}

		return operators.get(getFunctionName(), getArgsCount());
	}
}
