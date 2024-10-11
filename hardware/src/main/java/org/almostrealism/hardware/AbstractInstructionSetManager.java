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

public abstract class AbstractInstructionSetManager implements InstructionSetManager {
	private ComputeContext<?> computeContext;
	private String functionName;
	private int argsCount;

	public AbstractInstructionSetManager(ComputeContext<?> computeContext,
										 String functionName, int argsCount) {
		this.computeContext = computeContext;
		this.functionName = functionName;
		this.argsCount = argsCount;
	}

	public ComputeContext<?> getComputeContext() { return computeContext; }
	public String getFunctionName() { return functionName; }
	public int getArgsCount() { return argsCount; }
}
