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
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

public abstract class DynamicAcceleratedProducerAdapter<T extends MemWrapper> extends DynamicAcceleratedProducer<T> {
	private int memLength;
	private Function<Integer, String> variableRef;

	public DynamicAcceleratedProducerAdapter(int memLength, Producer<T> result, Producer<?>... inputArgs) {
		super(true, result, inputArgs, new Producer[0]);
		this.memLength = memLength;
	}

	public DynamicAcceleratedProducerAdapter(int memLength, Producer<T> result, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(true, result, inputArgs, additionalArguments);
		this.memLength = memLength;
	}

	public DynamicAcceleratedProducerAdapter(int memLength, boolean kernel, Producer<T> result, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(kernel, result, inputArgs, additionalArguments);
		this.memLength = memLength;
	}

	public int getMemLength() { return memLength; }

	@Override
	public String getBody(Function<Integer, String> outputVariable, List<Variable> existingVariables) {
		StringBuffer buf = new StringBuffer();
		writeVariables(buf::append, existingVariables);
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(outputVariable))
				.map(s -> s + "\n")
				.forEach(buf::append);
		return buf.toString();
	}

	public IntFunction<String> getAssignmentFunction(Function<Integer, String> outputVariable) {
		return i -> outputVariable.apply(i) + " = " + getValue(i) + ";";
	}

	public AcceleratedProducer getInputProducer(int index) {
		return (AcceleratedProducer) getInputProducers()[index].getProducer();
	}

	public String getInputProducerValue(int index, int pos) {
		return getInputProducerValue(getInputProducers()[index], pos);
	}

	public static String getInputProducerValue(Argument arg, int pos) {
		return ((DynamicAcceleratedProducerAdapter) arg.getProducer()).getValue(pos);
	}

	public String getValue(int pos) {
		return (isVariableRef() ? variableRef : getValueFunction()).apply(pos);
	}

	public abstract Function<Integer, String> getValueFunction();

	public boolean isVariableRef() { return variableRef != null;}

	public void convertToVariableRef() {
		if (variableRef == null) {
			IntStream.range(0, memLength)
					.mapToObj(variableForIndex(getValueFunction()))
					.forEach(this::addVariable);
			variableRef = i -> getVariableName(i);
		}
	}

	protected IntFunction<Variable> variableForIndex(Function<Integer, String> valueFunction) {
		return i -> new Variable(getVariableName(i), () -> valueFunction.apply(i), this);
	}

	public boolean isValueOnly() { return true; }

	protected boolean isCompletelyValueOnly() {
		// Confirm that all inputs are themselves dynamic accelerated adapters
		for (int i = 1; i < getInputProducers().length; i++) {
			if (getInputProducers()[i] == null)
				throw new IllegalArgumentException("Null input producer");
			if (getInputProducers()[i].getProducer() instanceof DynamicAcceleratedProducerAdapter == false)
				return false;
			if (!((DynamicAcceleratedProducerAdapter) getInputProducers()[i].getProducer()).isValueOnly())
				return false;
		}

		return true;
	}

	protected static List<Argument> extractStaticProducers(Argument args[]) {
		List<Argument> staticProducers = new ArrayList<>();

		for (int i = 1; i < args.length; i++) {
			if (args[i].getProducer() instanceof DynamicAcceleratedProducerAdapter && args[i].getProducer().isStatic()) {
				staticProducers.add(args[i]);
			}
		}

		return staticProducers;
	}

	protected static List<Argument> extractDynamicProducers(Argument args[]) {
		List<Argument> dynamicProducers = new ArrayList<>();

		for (int i = 1; i < args.length; i++) {
			if (args[i].getProducer() instanceof DynamicAcceleratedProducerAdapter == false ||
					!args[i].getProducer().isStatic()) {
				dynamicProducers.add(args[i]);
			}
		}

		return dynamicProducers;
	}
}
