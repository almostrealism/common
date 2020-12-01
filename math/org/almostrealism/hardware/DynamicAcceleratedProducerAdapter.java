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
import io.almostrealism.code.expressions.InstanceReference;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public abstract class DynamicAcceleratedProducerAdapter<I extends MemWrapper, O extends MemWrapper> extends ComputationProducerAdapter<I, O> implements MemWrapperComputation<O>, MultiExpression<Double>, ComputerFeatures {
	private int memLength;
	private IntFunction<InstanceReference> variableRef;

	public DynamicAcceleratedProducerAdapter(int memLength, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>... inputArgs) {
		this(memLength, result, inputArgs, new Evaluable[0]);
	}

	public DynamicAcceleratedProducerAdapter(int memLength, Supplier<Evaluable<? extends O>> result, Supplier<Evaluable<? extends I>>[] inputArgs, Object[] additionalArguments) {
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
	public Scope<O> getScope(NameProvider provider) {
		Scope<O> scope = super.getScope(provider);
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(provider.getOutputVariable()))
				.forEach(v -> scope.getVariables().add((Variable) v));
		return scope;
	}

	public OperationAdapter getInputProducer(int index) {
		return (OperationAdapter) getArguments().get(index).getProducer();
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
		return i -> new Variable(getVariableName(i), true, valueFunction.apply(i), this);
	}

	public boolean isValueOnly() { return true; }

	protected boolean isCompletelyValueOnly() {
		// Confirm that all inputs are themselves dynamic accelerated adapters
		for (int i = 1; i < getArguments().size(); i++) {
			if (getArguments().get(i) == null)
				throw new IllegalArgumentException("Null input producer");

			if (getArgumentProducer(i) instanceof DynamicAcceleratedProducerAdapter == false)
				return false;
			if (!((DynamicAcceleratedProducerAdapter) getArgumentProducer(i)).isValueOnly())
				return false;
		}

		return true;
	}

	public String getDefaultAnnotation() { return "__global"; }

	protected static <T> List<Argument<? extends T>> extractStaticProducers(List<Argument<? extends T>> args) {
		List<Argument<? extends T>> staticProducers = new ArrayList<>();

		for (int i = 1; i < args.size(); i++) {
			if (args.get(i).getProducer() instanceof DynamicAcceleratedProducerAdapter &&
					((DynamicAcceleratedProducerAdapter) args.get(i).getProducer()).isStatic()) {
				staticProducers.add(args.get(i));
			}
		}

		return staticProducers;
	}

	protected static <T> List<Argument<? extends T>> extractDynamicProducers(List<Argument<? extends T>> args) {
		List<Argument<? extends T>> dynamicProducers = new ArrayList<>();

		for (int i = 1; i < args.size(); i++) {
			if (args.get(i).getProducer() instanceof DynamicAcceleratedProducerAdapter == false ||
					!((DynamicAcceleratedProducerAdapter) args.get(i).getProducer()).isStatic()) {
				dynamicProducers.add(args.get(i));
			}
		}

		return dynamicProducers;
	}
}
