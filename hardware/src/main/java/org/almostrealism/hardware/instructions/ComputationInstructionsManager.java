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
import io.almostrealism.scope.Scope;

import java.util.function.Supplier;

/**
 * NOTE: This class may be unnecessary, as the only case where it matters is
 * when the {@link Scope} contains multiple functions that can be called by
 * name and/or arg count and this is not something that is done in practice
 * beyond in OpenCL (which already has a dedicated {@link InstructionSetManager}).
 */
public class ComputationInstructionsManager extends ScopeInstructionsManager<DefaultExecutionKey> {

	public ComputationInstructionsManager(ComputeContext<?> computeContext,
										  Supplier<Scope<?>> scope) {
		super(computeContext, scope, null);
	}

	@Override
	public synchronized Execution getOperator(DefaultExecutionKey key) {
		return getInstructionSet().get(key.getFunctionName(), key.getArgsCount());
	}
}
