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

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Scope;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.cl.HardwareOperator;
import org.almostrealism.hardware.cl.HardwareOperatorMap;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public abstract class DynamicAcceleratedOperation<T extends MemoryData> extends AcceleratedOperation<T> implements ExplictBody<T> {
	private InstructionSet operators;

	@SafeVarargs
	public DynamicAcceleratedOperation(boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(kernel, args);
	}

	@SafeVarargs
	public DynamicAcceleratedOperation(boolean kernel, ArrayVariable<T>... args) {
		super(kernel, args);
	}

	@Override
	public synchronized Object[] apply(Object[] args) {
		try {
			return super.apply(args);
		} catch (HardwareException e) {
			String prog = getFunctionDefinition();
			e.setProgram(prog);
			System.out.println(prog);
			throw e;
		}
	}

	@Override
	public void kernelOperate(MemoryBank output, MemoryBank[] args) {
		try {
			super.kernelOperate(output, args);
		} catch (HardwareException e) {
			String prog = getFunctionDefinition();
			e.setProgram(prog);
			System.out.println(prog);
			throw e;
		}
	}

	@Override
	public synchronized Consumer<Object[]> getOperator() {
		if (operators == null || operators.isDestroyed()) {
			operators = Hardware.getLocalHardware().getComputeContext().deliver(Scope.verbatim(getFunctionDefinition()));
		}

		return operators.get(getFunctionName(), getArgsCount());
	}

	public String getFunctionDefinition() {
		return "__kernel void " + getFunctionName() + "(" +
				getFunctionArgsDefinition() +
				") {\n" +
				getBody(getOutputVariable()) +
				"}";
	}

	protected String getFunctionArgsDefinition() {
		StringBuilder buf = new StringBuilder();

		List<ArrayVariable<? extends T>> args = getArgumentVariables();

		for (int i = 0; i < args.size(); i++) {
			buf.append("__global ");
			if (i != 0) buf.append("const ");
			buf.append(getNumberTypeName());
			buf.append(" *");

			if (args.get(i) == null) {
				throw new IllegalArgumentException("Null Argument (" + i + ")");
			} else if (args.get(i).getName() == null) {
				throw new IllegalArgumentException("Null name for Argument " + i);
			}

			buf.append(getArgumentVariables().get(i).getName());
			buf.append(", ");
		}

		for (int i = 0; i < getArgumentVariables().size(); i++) {
			buf.append("const int ");
			buf.append(getArgumentVariables().get(i).getName());
			buf.append("Offset");
			buf.append(", ");
		}

		for (int i = 0; i < getArgumentVariables().size(); i++) {
			buf.append("const int ");
			buf.append(getArgumentVariables().get(i).getName());
			buf.append("Size");
			if (i < getArgsCount() - 1) buf.append(", ");
		}

		return buf.toString();
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
