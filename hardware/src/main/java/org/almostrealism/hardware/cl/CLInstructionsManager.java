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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import org.almostrealism.hardware.instructions.AbstractInstructionSetManager;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;

import java.util.HashMap;
import java.util.Map;

public class CLInstructionsManager extends AbstractInstructionSetManager<DefaultExecutionKey> {
	private static final Map<String, ThreadLocal<CLOperator>> operators = new HashMap<>();

	private Class<?> sourceClass;

	public CLInstructionsManager(ComputeContext<?> computeContext, Class<?> sourceClass) {
		super(computeContext);
		this.sourceClass = sourceClass;
	}

	public CLComputeContext getComputeContext() {
		return (CLComputeContext) super.getComputeContext();
	}

	public Class<?> getSourceClass() { return sourceClass; }

	@Override
	public Execution getOperator(DefaultExecutionKey key) {
		// TODO  This needs to be by class in addition to function, as function names may collide
		synchronized (CLInstructionsManager.class) {
			if (operators.get(key.getFunctionName()) == null) {
				operators.put(key.getFunctionName(), new ThreadLocal<>());
			}

			if (operators.get(key.getFunctionName()).get() == null) {
				operators.get(key.getFunctionName()).set(getComputeContext()
						.getFunctions().getOperators(getSourceClass()).get(key.getFunctionName(), key.getArgsCount()));
			}
		}

		return operators.get(key.getFunctionName()).get();
	}
}
