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
import io.almostrealism.code.ComputationProducerAdapter;
import io.almostrealism.code.InstanceReference;
import io.almostrealism.code.Expression;
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.relation.Computation;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

public abstract class DynamicAcceleratedProducerAdapter<T extends MemWrapper> extends ComputationProducerAdapter<T> implements MemWrapperComputation<T>, MultiExpression<Double>, ComputerFeatures {
	private int memLength;
	private IntFunction<InstanceReference> variableRef;

	public DynamicAcceleratedProducerAdapter(int memLength, Producer<T> result, Producer<?>... inputArgs) {
		this(memLength, result, inputArgs, new Producer[0]);
	}

	public DynamicAcceleratedProducerAdapter(int memLength, Producer<T> result, Producer<?>[] inputArgs, Object[] additionalArguments) {
		this.memLength = memLength;
		this.setArguments(Arrays.asList(arguments(
				AcceleratedProducer.includeResult(result,
						AcceleratedProducer.producers(inputArgs, additionalArguments)))));
		init();

		// Result should always be first
		if (getArgument(0) != null) getArgument(0).setSortHint(-1);
	}

	public int getMemLength() { return memLength; }

	@Override
	public Scope<T> getScope(NameProvider provider) {
		Scope<T> scope = new Scope<>(provider.getFunctionName());
		scope.getVariables().addAll(getVariables());
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(provider.getOutputVariable()))
				.forEach(v -> scope.getVariables().add((Variable) v));
		return scope;
	}

	public AcceleratedOperation getInputProducer(int index) {
		return (AcceleratedOperation) getArguments().get(index).getProducer();
	}

	public Expression<Double> getInputProducerValue(int index, int pos) {
		return getInputProducerValue(getArguments().get(index), pos);
	}

	public static Expression<Double> getInputProducerValue(Argument arg, int pos) {
		Optional<Computation> c = Hardware.getLocalHardware().getComputer().decompile(arg.getProducer());
		return ((DynamicAcceleratedProducerAdapter) c.get()).getValue(pos);
	}

	@Override
	public Expression getValue(int pos) {
		return (isVariableRef() ? variableRef : getValueFunction()).apply(pos);
	}

	public abstract IntFunction<Expression<Double>> getValueFunction();

	public boolean isVariableRef() { return variableRef != null;}

	public void convertToVariableRef() {
		if (variableRef == null) {
			IntStream.range(0, memLength)
					.mapToObj(variableForIndex(getValueFunction()))
					.forEach(this::addVariable);
			variableRef = i -> new InstanceReference(getVariable(i));
		}
	}

	protected IntFunction<Variable<Double>> variableForIndex(IntFunction<Expression<Double>> valueFunction) {
		return i -> new Variable(getVariableName(i), true, valueFunction.apply(i), compileProducer(this));
	}

	public boolean isValueOnly() { return true; }

	protected boolean isCompletelyValueOnly() {
		// Confirm that all inputs are themselves dynamic accelerated adapters
		for (int i = 1; i < getArguments().size(); i++) {
			if (getArguments().get(i) == null)
				throw new IllegalArgumentException("Null input producer");

			Optional<Computation> c = decompile(getArguments().get(i).getProducer());

			if (c.orElse(null) instanceof DynamicAcceleratedProducerAdapter == false)
				return false;
			if (!((DynamicAcceleratedProducerAdapter) c.get()).isValueOnly())
				return false;
		}

		return true;
	}

	public String getDefaultAnnotation() { return "__global"; }

	protected static List<Argument> extractStaticProducers(List<Argument> args) {
		List<Argument> staticProducers = new ArrayList<>();

		for (int i = 1; i < args.size(); i++) {
			if (args.get(i).getProducer() instanceof DynamicAcceleratedProducerAdapter &&
					args.get(i).getProducer().isStatic()) {
				staticProducers.add(args.get(i));
			}
		}

		return staticProducers;
	}

	protected static List<Argument> extractDynamicProducers(List<Argument> args) {
		List<Argument> dynamicProducers = new ArrayList<>();

		for (int i = 1; i < args.size(); i++) {
			if (args.get(i).getProducer() instanceof DynamicAcceleratedProducerAdapter == false ||
					!args.get(i).getProducer().isStatic()) {
				dynamicProducers.add(args.get(i));
			}
		}

		return dynamicProducers;
	}
}
