/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.Argument;
import io.almostrealism.code.Variable;
import org.almostrealism.util.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public abstract class DynamicAcceleratedOperation<T extends MemWrapper> extends AcceleratedOperation<T> {
	private HardwareOperatorMap operators;

	public DynamicAcceleratedOperation(boolean kernel, Supplier<Evaluable<? extends T>>... args) {
		super(null, kernel, args);
	}

	public DynamicAcceleratedOperation(boolean kernel, Argument<T>... args) {
		super(null, kernel, args);
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
	public void kernelOperate(MemoryBank[] args) {
		try {
			super.kernelOperate(args);
		} catch (HardwareException e) {
			String prog = getFunctionDefinition();
			e.setProgram(prog);
			System.out.println(prog);
			throw e;
		}
	}

	@Override
	public synchronized HardwareOperator getOperator() {
		if (operators == null) {
			operators = Hardware.getLocalHardware().getFunctions().getOperators(getFunctionDefinition());
		}

		return operators.get(getFunctionName(), getArgsCount());
	}

	public String getFunctionDefinition() {
		StringBuffer buf = new StringBuffer();
		buf.append("__kernel void " + getFunctionName() + "(");
		buf.append(getFunctionArgsDefinition());
		buf.append(") {\n");
		buf.append(getBody(getOutputVariable(), new ArrayList<>()));
		buf.append("}");
		return buf.toString();
	}

	protected String getFunctionArgsDefinition() {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < getArguments().size(); i++) {
			buf.append("__global ");
			if (i != 0) buf.append("const ");
			buf.append(getNumberType());
			buf.append(" *");
			if (getArguments().get(i).getName() == null) {
				throw new IllegalArgumentException("Null name for Argument " + i);
			}

			buf.append(getArguments().get(i).getName());
			buf.append(", ");
		}

		for (int i = 0; i < getArguments().size(); i++) {
			buf.append("const int ");
			buf.append(getArguments().get(i).getName());
			buf.append("Offset");
			buf.append(", ");
		}

		for (int i = 0; i < getArguments().size(); i++) {
			buf.append("const int ");
			buf.append(getArguments().get(i).getName());
			buf.append("Size");
			if (i < (getArgsCount() - 1)) buf.append(", ");
		}

		return buf.toString();
	}

	public abstract String getBody(Variable<T> outputVariable, List<Variable<?>> existingVariables);
}
