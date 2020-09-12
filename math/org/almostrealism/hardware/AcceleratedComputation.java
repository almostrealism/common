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

import io.almostrealism.c.OpenCLPrintWriter;
import io.almostrealism.code.Argument;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeEncoder;
import io.almostrealism.code.Variable;
import org.almostrealism.relation.Computation;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class AcceleratedComputation<T extends MemWrapper> extends AcceleratedProducer<T> implements Computation<T>, NameProvider {
	private static long functionId = 0;

	private HardwareOperatorMap operators;
	private Map<Producer, List<Variable>> variables;

	public AcceleratedComputation(Producer<?>... inputArgs) {
		super(null, true, inputArgs);
		setFunctionName(functionName(getClass()));
		initArgumentNames();
		variables = new HashMap<>();
	}

	public AcceleratedComputation(Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(null, true, inputArgs, additionalArguments);
		setFunctionName(functionName(getClass()));
		initArgumentNames();
		variables = new HashMap<>();
	}

	public AcceleratedComputation(boolean kernel, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(null, kernel, inputArgs, additionalArguments);
		setFunctionName(functionName(getClass()));
		initArgumentNames();
		variables = new HashMap<>();
	}

	protected void initArgumentNames() {
		for (int i = 0; i < getInputProducers().length; i++) {
			if (getInputProducers()[i] != null) {
				getInputProducers()[i].setName(getArgumentName(i));
			}
		}
	}

	public void addVariable(Variable v) {
		if (v.getProducer() == null) {
			throw new IllegalArgumentException("Producer must be provided for variable");
		}

		List<Variable> existing = variables.get(v.getProducer());
		if (existing == null) {
			existing = new ArrayList<>();
			variables.put(v.getProducer(), existing);
		}

		existing.add(v);
	}

	public List<Variable> getVariables() {
		return variables.values().stream().flatMap(List::stream).collect(Collectors.toList());
	}

	protected void writeVariables(Consumer<String> out) {
		getVariables().forEach(var -> {
			out.accept(getNumberType());
			out.accept(" ");
			out.accept(var.getName());
			out.accept(" = ");
			out.accept(var.getExpression());
			out.accept(";\n");
		});
	}

	@Override
	public String getArgumentValueName(String v, int pos, boolean assignment, int kernelIndex) {
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

	@Override
	public synchronized HardwareOperator getOperator() {
		if (operators == null) {
			operators = Hardware.getLocalHardware().getFunctions().getOperators(getFunctionDefinition());
		}

		return operators.get(getFunctionName(), getArgsCount());
	}

	public String getFunctionDefinition() {
		Scope scope = getScope(this);
		ScopeEncoder encoder = new ScopeEncoder(OpenCLPrintWriter::new);
		return encoder.apply(scope);
	}

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
