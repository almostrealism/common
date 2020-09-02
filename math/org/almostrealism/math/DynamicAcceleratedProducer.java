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

package org.almostrealism.math;

import org.almostrealism.util.CollectionUtils;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

public abstract class DynamicAcceleratedProducer<T extends MemWrapper> extends AcceleratedProducer<T> {
	private static long functionId = 0;

	private HardwareOperatorMap operators;

	public DynamicAcceleratedProducer(Producer<?>... inputArgs) {
		super(null, true, inputArgs);
		setFunctionName(functionName(getClass()));
		initArgumentNames();
	}

	public DynamicAcceleratedProducer(Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(null, true, inputArgs, additionalArguments);
		setFunctionName(functionName(getClass()));
		initArgumentNames();
	}

	public DynamicAcceleratedProducer(boolean kernel, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(null, kernel, inputArgs, additionalArguments);
		setFunctionName(functionName(getClass()));
		initArgumentNames();
	}

	protected void initArgumentNames() {
		for (int i = 0; i < getInputProducers().length; i++) {
			if (getInputProducers()[i] != null) {
				getInputProducers()[i].setName(getArgumentName(i));
			}
		}
	}

	protected String getArgumentName(int index) {
		return getFunctionName() + "_v" + index;
	}

	protected String getVariableName(int index) {
		return getFunctionName() + "_l" + index;
	}

	protected String getArgumentValueName(int index, int pos) {
		return getArgumentValueName(index, pos, 0);
	}

	protected String getArgumentValueName(int index, int pos, int kernelIndex) {
		return getArgumentValueName(getArgumentName(index), pos, kernelIndex);
	}

	protected String getArgumentValueName(Argument arg, int pos) {
		return getArgumentValueName(arg.getName(), pos, 0);
	}

	protected String getArgumentValueName(Argument arg, int pos, int kernelIndex) {
		return getArgumentValueName(arg.getName(), pos, kernelIndex);
	}

	private String getArgumentValueName(String v, int pos, int kernelIndex) {
		return getArgumentValueName(v, pos, true, kernelIndex);
	}

	private String getArgumentValueName(String v, int pos, boolean assignment) {
		return getArgumentValueName(v, pos, assignment, 0);
	}

	private String getArgumentValueName(String v, int pos, boolean assignment, int kernelIndex) {
		String name;

		if (isKernel()) {
			String kernelOffset = kernelIndex < 0 ? "" :
					("get_global_id(" + kernelIndex + ") * " + v + "Size + ");

			if (pos == 0) {
				name = v + "[" + kernelOffset + v + "Offset]";
			} else {
				name = v + "[" + kernelOffset + v + "Offset + " + pos + "]";
			}
		} else {
			if (pos == 0) {
				name = v + "[" + v + "Offset]";
			} else {
				name = v + "[" + v + "Offset + " + pos + "]";
			}
		}

		if (isCastEnabled() && !assignment) {
			return "(float)" + name;
		} else {
			return name;
		}
	}

	public boolean isCastEnabled() {
		return Hardware.getLocalHardware().isGPU() && Hardware.getLocalHardware().isDoublePrecision();
	}

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
		buf.append(getBody(i -> getArgumentValueName(0, i)));
		buf.append("}");
		return buf.toString();
	}

	protected String getFunctionArgsDefinition() {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < getInputProducers().length; i++) {
			buf.append("__global ");
			if (i != 0) buf.append("const ");
			buf.append(getNumberType());
			buf.append(" *");
			if (getInputProducers()[i].getName() == null) {
				throw new IllegalArgumentException("Null name for Argument " + i);
			}

			buf.append(getInputProducers()[i].getName());
			buf.append(", ");
		}

		for (int i = 0; i < getInputProducers().length; i++) {
			buf.append("const int ");
			buf.append(getInputProducers()[i].getName());
			buf.append("Offset");
			buf.append(", ");
		}

		for (int i = 0; i < getInputProducers().length; i++) {
			buf.append("const int ");
			buf.append(getInputProducers()[i].getName());
			buf.append("Size");
			if (i < (getArgsCount() - 1)) buf.append(", ");
		}

		return buf.toString();
	}

	public abstract String getBody(Function<Integer, String> outputVariable);

	protected void removeDuplicateArguments() {
		List<Argument> args = new ArrayList<>();
		args.addAll(Arrays.asList(inputProducers));

		List<String> names = new ArrayList<>();
		Iterator<Argument> itr = args.iterator();

		while (itr.hasNext()) {
			Argument arg = itr.next();
			if (names.contains(arg.getName())) {
				itr.remove();
			} else {
				names.add(arg.getName());
			}
		}

		inputProducers = args.toArray(new Argument[0]);
	}

	protected static String functionName(Class c) {
		String s = c.getSimpleName();
		if (s.length() == 0) {
			s = "anonymous";
		}

		if (s.length() < 2) {
			throw new IllegalArgumentException(c.getName() + " has too short of a simple name to use for a function");
		}

		return "f_" + s.substring(0, 1).toLowerCase() + s.substring(1) + "_" + functionId++;
	}
}