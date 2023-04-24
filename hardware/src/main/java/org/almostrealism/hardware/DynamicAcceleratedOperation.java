/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class DynamicAcceleratedOperation<T extends MemoryData> extends AcceleratedOperation<T> {
	protected InstructionSet operators;

	@SafeVarargs
	public DynamicAcceleratedOperation(boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(kernel, args);
	}

	@SafeVarargs
	public DynamicAcceleratedOperation(boolean kernel, ArrayVariable<T>... args) {
		super(kernel, args);
	}

	@Override
	public synchronized Consumer<Object[]> getOperator() {
		if (operators == null || operators.isDestroyed()) {
			// operators = Hardware.getLocalHardware().getClComputeContext().deliver(Scope.verbatim(getFunctionDefinition()));
			throw new UnsupportedOperationException();
		}

		return operators.get(getFunctionName(), getArgsCount());
	}

	@Override
	public ArrayVariable getArgument(int index) {
		return getInputs() == null ? getArgumentVariables().get(index) : getArgumentForInput(getInputs().get(index));
	}

	@Override
	public void destroy() {
		super.destroy();

		if (operators != null) {
			operators.destroy();
			operators = null;
		}
	}
}
