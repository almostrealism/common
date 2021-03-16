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
import io.almostrealism.code.ProducerComputationAdapter;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.expressions.InstanceReference;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.MultiExpression;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.mem.MemWrapperDestination;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public abstract class DynamicProducerComputationAdapter<I extends MemWrapper, O extends MemWrapper>
		extends ProducerComputationAdapter<I, O>
		implements MemWrapperComputation<O>, KernelizedProducer<O>,
		DestinationSupport<O>, MultiExpression<Double>, ComputerFeatures {

	/**
	 * If set to true, then {@link Provider}s are treated as static for
	 * compaction. This is often desirable, because Providers may not
	 * change, but it is also likely to make many types of operations
	 * that update Provider values in place only execute properly the
	 * first time, since the original Provider value will be reused on
	 * the next run of the operation.
	 */
	public static final boolean enableStaticProviders = false;

	/**
	 * If set to true, then {@link #convertToVariableRef()} can be used
	 * to take the {@link Expression} from {@link #getValueFunction()} to
	 * a local variable in the rendered code. This can prevent
	 * {@link Expression}s from growing too large during compaction, when
	 * values are repeatedly embedded to form bigger and bigger
	 * {@link Expression}s.
	 */
	public static final boolean enableVariableRefConversion = false;

	private final int memLength;
	private IntFunction<InstanceReference> variableRef;
	private Supplier<O> destination;

	public DynamicProducerComputationAdapter(int memLength, Supplier<Evaluable<? extends O>> result,
											 IntFunction<MemoryBank<O>> kernelDestination,
											 Supplier<Evaluable<? extends I>>... inputArgs) {
		this(memLength, result, kernelDestination, inputArgs, new Evaluable[0]);
	}

	public DynamicProducerComputationAdapter(int memLength, Supplier<Evaluable<? extends O>> result,
											 IntFunction<MemoryBank<O>> kernelDestination,
											 Supplier<Evaluable<? extends I>>[] inputArgs,
											 Object[] additionalArguments) {
		this.memLength = memLength;
		this.destination = () -> (O) result.get().evaluate();
		this.setInputs(Arrays.asList(
				AcceleratedEvaluable.includeResult(
						new MemWrapperDestination(this, kernelDestination),
						AcceleratedEvaluable.producers(inputArgs, additionalArguments))));
		init();
	}

	public int getMemLength() { return memLength; }

	@Override
	public void setDestination(Supplier<O> destination) { this.destination = destination; }

	@Override
	public Supplier<O> getDestination() { return destination; }

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
	}

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = super.getScope();
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(this, getOutputVariable()))
				.forEach(v -> scope.getVariables().add((Variable) v));
		return scope;
	}

	public OperationAdapter getInputProducer(int index) {
		if (getInputs().get(index) instanceof OperationAdapter) {
			return (OperationAdapter) getInputs().get(index);
		}

		return null;
	}

	public boolean isInputProducerStatic(int index) {
		Supplier<Evaluable<? extends I>> producer = getInputs().get(index);
		if (producer instanceof OperationAdapter) {
			return ((OperationAdapter) producer).isStatic();
		}

		Evaluable<? extends I> evaluable = producer.get();
		if (evaluable instanceof Provider) return true;

		return false;
	}

	@Override
	public Expression getValue(int pos) {
		return (isVariableRef() ? variableRef : getValueFunction()).apply(pos);
	}

	public abstract IntFunction<Expression<Double>> getValueFunction();

	public boolean isVariableRef() { return variableRef != null;}

	public void convertToVariableRef() {
		if (enableVariableRefConversion && variableRef == null) {
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
		List<Supplier<Evaluable<? extends I>>> inputs = getInputs();
		// Confirm that all inputs are themselves dynamic accelerated adapters
		i: for (int i = 1; i < inputs.size(); i++) {
			if (inputs.get(i) == null)
				throw new IllegalArgumentException("Null input producer");

			Supplier<Evaluable<? extends I>> supplier = inputs.get(i);

			// A "value only" producer is acceptable
			if (supplier instanceof DynamicProducerComputationAdapter
					&& ((DynamicProducerComputationAdapter) supplier).isValueOnly()) {
				continue i;
			}

			// A Provider is always "value only"
			if (enableStaticProviders && supplier.get() instanceof Provider) {
				continue i;
			}

			return false;
		}

		return true;
	}

	public String getDefaultAnnotation() { return "__global"; }

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
		if (destination instanceof DestinationConsolidationArgumentMap.DestinationThreadLocal) {
			((DestinationConsolidationArgumentMap.DestinationThreadLocal) destination).destroy();
		}
	}

	protected static <T> List<ArrayVariable<? extends T>> extractStaticProducers(List<ArrayVariable<? extends T>> args) {
		List<ArrayVariable<? extends T>> staticProducers = new ArrayList<>();

		for (int i = 1; i < args.size(); i++) {
			if (args.get(i).getProducer() instanceof DynamicProducerComputationAdapter &&
					((DynamicProducerComputationAdapter) args.get(i).getProducer()).isStatic()) {
				staticProducers.add(args.get(i));
			}
		}

		return staticProducers;
	}

	protected static <T> List<ArrayVariable<? extends T>> extractDynamicProducers(List<ArrayVariable<? extends T>> args) {
		List<ArrayVariable<? extends T>> dynamicProducers = new ArrayList<>();

		for (int i = 1; i < args.size(); i++) {
			if (args.get(i).getProducer() instanceof DynamicProducerComputationAdapter == false ||
					!((DynamicProducerComputationAdapter) args.get(i).getProducer()).isStatic()) {
				dynamicProducers.add(args.get(i));
			}
		}

		return dynamicProducers;
	}
}
