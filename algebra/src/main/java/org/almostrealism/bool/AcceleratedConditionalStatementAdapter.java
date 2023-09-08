/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.bool;

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.PhysicalScope;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.code.HybridScope;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;

// TODO  This should extend CollectionProducerComputationBase
public abstract class AcceleratedConditionalStatementAdapter<T extends PackedCollection<?>>
											extends ProducerComputationBase<MemoryData, T>
											implements CollectionProducerComputation<T>,
													AcceleratedConditionalStatement<T>,
													DestinationSupport<MemoryData>,
													ComputerFeatures {

	private final int memLength;

	private Supplier<MemoryData> destination;

	private BiFunction<MemoryData, Integer, T> postprocessor;

	public AcceleratedConditionalStatementAdapter(int memLength, Supplier<T> blankValue, IntFunction<MemoryBank<T>> kernelDestination) {
		this(memLength, blankValue, kernelDestination, null, null, null, null);
	}

	public AcceleratedConditionalStatementAdapter(int memLength,
												  Supplier<T> blankValue,
												  IntFunction<MemoryBank<T>> kernelDestination,
												  Supplier<Evaluable> leftOperand,
												  Supplier<Evaluable> rightOperand,
												  Supplier<Evaluable<? extends T>> trueValue,
												  Supplier<Evaluable<? extends T>> falseValue) {
		this.memLength = memLength;
		this.destination = (Supplier) blankValue;

		List inputs = new ArrayList();
		inputs.add(new MemoryDataDestination(this, kernelDestination));
		inputs.add(leftOperand);
		inputs.add(rightOperand);
		inputs.add(trueValue);
		inputs.add(falseValue);
		this.setInputs(inputs);

		init();
	}

	public int getMemLength() { return memLength; }

	@Override
	public TraversalPolicy getShape() { return new TraversalPolicy(memLength); }

	@Override
	public int getCount() { return getShape().getCount(); }

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public void setDestination(Supplier<MemoryData> destination) { this.destination = destination; }

	@Override
	public Supplier<MemoryData> getDestination() { return destination; }

	public BiFunction<MemoryData, Integer, T> getPostprocessor() {
		return postprocessor;
	}

	public void setPostprocessor(BiFunction<MemoryData, Integer, T> postprocessor) {
		this.postprocessor = postprocessor;
	}

	// TODO  The hybrid scope (by way of ExplicitScope) includes every argument as a dependency.
	// TODO  To eliminate this problem, the actual dependencies need to be specified.
	// TODO  They can be extracted from getTrueValueExpression and getFalseValueExpression
	// TODO  and passed to the HybridScope directly.
	@Override
	public Scope<T> getScope() {
		HybridScope<T> scope = new HybridScope<>(this);
		scope.getVariables().addAll(getVariables());

		ArrayVariable<?> outputVariable = (ArrayVariable<?>) getOutputVariable();
		List<Variable<?, ?>> vars = new ArrayList<>();

		Variable<?, ?> condition = new Variable<>("", getCondition());
		vars.add(condition);

		scope.code().accept("if (");
		scope.code().accept(condition.getExpression().getSimpleExpression());
		scope.code().accept(") {\n");

		for (int i = 0; i < getMemLength(); i++) {
			// Variable<?, ?> var = new Variable(outputVariable.valueAt(i).getSimpleExpression(), getTrueValueExpression().apply(i), outputVariable);
			Variable<?, ?> var = outputVariable.ref(i).assign(getTrueValueExpression().apply(i));
			vars.add(var);

			scope.code().accept("\t");
			scope.code().accept(var.getName());
			scope.code().accept(" = ");
			scope.code().accept(var.getExpression().getSimpleExpression());
			scope.code().accept(";\n");
		}

		scope.code().accept("} else {\n");

		for (int i = 0; i < getMemLength(); i++) {
			// Variable<?, ?> var = new Variable(outputVariable.valueAt(i).getSimpleExpression(), getFalseValueExpression().apply(i), outputVariable);
			Variable<?, ?> var = outputVariable.ref(i).assign(getFalseValueExpression().apply(i));
			vars.add(var);

			scope.code().accept("\t");
			scope.code().accept(var.getName());
			scope.code().accept(" = ");
			scope.code().accept(var.getExpression().getSimpleExpression());
			scope.code().accept(";\n");
		}

		scope.code().accept("}\n");

		scope.setDependencies(vars);
		return scope;
	}

	protected boolean isCompacted() { return false; }

	@Override
	public T postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? CollectionProducerComputation.super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
	}
}
