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
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerArgumentReference;
import io.almostrealism.code.ProducerComputationAdapter;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Provider;
import org.almostrealism.hardware.DestinationConsolidationArgumentMap.DestinationThreadLocal;
import org.almostrealism.hardware.collect.ExpressionValue;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Deprecated
public abstract class DynamicProducerComputationAdapter<I extends MemoryData, O extends MemoryData>
		extends ProducerComputationAdapter<I, O>
		implements MemoryDataComputation<O>, KernelizedProducer<O>,
		DestinationSupport<O>, MultiExpression<Double>, ExpressionValue, ComputerFeatures {

	/**
	 * If set to true, then {@link Provider}s are treated as value-only
	 * for compaction. This is desirable, because otherwise the presence
	 * of a {@link Provider} will prevent the compaction of an expression
	 * altogether.
	 */
	public static final boolean enableValueOnlyProviders = true;

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

	@SafeVarargs
	public DynamicProducerComputationAdapter(int memLength, Supplier<Evaluable<? extends O>> result,
											 IntFunction<MemoryBank<O>> kernelDestination,
											 Supplier<Evaluable<? extends I>>... inputArgs) {
		this(memLength, result, kernelDestination, inputArgs, new Evaluable[0]);
	}

	public DynamicProducerComputationAdapter(int memLength, Supplier<Evaluable<? extends O>> result,
											 IntFunction<MemoryBank<O>> kernelDestination,
											 Supplier<Evaluable<? extends I>>[] inputArgs,
											 Object[] additionalArguments) {
		if (result == null && !(this instanceof ProducerArgumentReference)) {
			throw new IllegalArgumentException();
		}

		this.memLength = memLength;
		this.destination = () -> (O) Optional.ofNullable(result).map(Supplier::get).map(Evaluable::evaluate).orElse(null);
		this.setInputs(Arrays.asList(
				AcceleratedEvaluable.includeResult(
						new MemoryDataDestination(this, kernelDestination),
						AcceleratedEvaluable.producers(inputArgs, additionalArguments))));
		init();
	}

	@Override
	public int getMemLength() { return memLength; }

	@Override
	public void setDestination(Supplier<O> destination) { this.destination = destination; }

	@Override
	public Supplier<O> getDestination() { return destination; }

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		// TODO  This causes cascading issues, as the output variable is reused by the referring
		// TODO  producer and then multiple arguments are sorted to be "first"
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
	}

	@Override
	public Scope<O> getScope() {
		Scope<O> scope = super.getScope();
		IntStream.range(0, memLength)
				.mapToObj(getAssignmentFunction(getOutputVariable()))
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
		if (enableStaticProviders && evaluable instanceof Provider) return true;

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

	protected IntFunction<Variable<Double, ?>> variableForIndex(IntFunction<Expression<Double>> valueFunction) {
		return i -> new Variable(getVariableName(i), true, valueFunction.apply(i), this);
	}

	public boolean isValueOnly() { return true; }

	protected boolean isCompletelyValueOnly() {
		return isCompletelyValueOnly(true);
	}

	protected boolean isCompletelyValueOnly(boolean considerProviders) {
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
			if (enableValueOnlyProviders && considerProviders && supplier.get() instanceof Provider) {
				continue i;
			}

			return false;
		}

		return true;
	}

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
		if (destination instanceof DestinationThreadLocal) {
			((DestinationThreadLocal) destination).destroy();
		}
	}

	protected static <T> List<ArrayVariable<? extends T>> extractStaticProducers(List<ArrayVariable<? extends T>> args) {
		return IntStream.range(0, args.size()).filter(i -> args.get(i).getProducer() instanceof DynamicProducerComputationAdapter &&
				((DynamicProducerComputationAdapter) args.get(i).getProducer()).isStatic()).mapToObj(args::get).collect(Collectors.toList());
	}

	protected static <T> List<ArrayVariable<? extends T>> extractDynamicProducers(List<ArrayVariable<? extends T>> args) {
		return IntStream.range(0, args.size()).filter(i -> !(args.get(i).getProducer() instanceof DynamicProducerComputationAdapter) ||
				!((DynamicProducerComputationAdapter) args.get(i).getProducer()).isStatic()).mapToObj(args::get).collect(Collectors.toList());
	}
}
