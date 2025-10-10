/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.ProducerComputationBase;
import io.almostrealism.scope.HybridScope;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.mem.MemoryDataDestinationProducer;

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
													HardwareFeatures {

	private final int memLength;

	private BiFunction<MemoryData, Integer, T> postprocessor;

	public AcceleratedConditionalStatementAdapter(int memLength, IntFunction<MemoryBank<T>> kernelDestination) {
		this(memLength, kernelDestination, null, null, null, null);
	}

	public AcceleratedConditionalStatementAdapter(int memLength,
												  IntFunction<MemoryBank<T>> kernelDestination,
												  Supplier<Evaluable> leftOperand,
												  Supplier<Evaluable> rightOperand,
												  Supplier<Evaluable<? extends T>> trueValue,
												  Supplier<Evaluable<? extends T>> falseValue) {
		this.memLength = memLength;

		List inputs = new ArrayList();
		inputs.add(new MemoryDataDestinationProducer(this, kernelDestination));
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
	public long getCountLong() { return getShape().getCountLong(); }

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
	public Scope<T> getScope(KernelStructureContext context) {
		HybridScope<T> scope = new HybridScope<>(this);
		scope.getVariables().addAll(getVariables());

		ArrayVariable<?> outputVariable = (ArrayVariable<?>) getOutputVariable();
		List<Variable<?, ?>> vars = new ArrayList<>();
		vars.addAll(getCondition().getDependencies());

		scope.code().accept("if (");
		scope.code().accept(getCondition().getSimpleExpression(getLanguage()));
		scope.code().accept(") {\n");

		KernelIndex k = kernel(context);
		Expression m = k.multiply(getMemLength());

		for (int i = 0; i < getMemLength(); i++) {
			ExpressionAssignment<?> var = outputVariable.reference(m.add(i)).assign(getTrueValueExpression().apply(i));
			vars.addAll(var.getDependencies());

			scope.code().accept("\t");
			scope.code().accept(var.getDestination().getSimpleExpression(getLanguage()));
			scope.code().accept(" = ");
			scope.code().accept(var.getExpression().getSimpleExpression(getLanguage()));
			scope.code().accept(";\n");
		}

		scope.code().accept("} else {\n");

		for (int i = 0; i < getMemLength(); i++) {
			ExpressionAssignment<?> var = outputVariable.reference(m.add(i)).assign(getFalseValueExpression().apply(i));
			vars.addAll(var.getDependencies());

			scope.code().accept("\t");
			scope.code().accept(var.getDestination().getSimpleExpression(getLanguage()));
			scope.code().accept(" = ");
			scope.code().accept(var.getExpression().getSimpleExpression(getLanguage()));
			scope.code().accept(";\n");
		}

		scope.code().accept("}\n");

		scope.setDependencies(vars);
		return scope;
	}

	@Override
	public T postProcessOutput(MemoryData output, int offset) {
		return getPostprocessor() == null ? CollectionProducerComputation.super.postProcessOutput(output, offset) : getPostprocessor().apply(output, offset);
	}

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
	}

	@Override
	public <T> Producer<?> delegate(Producer<T> original, Producer<T> actual) {
		return CollectionProducerComputation.super.delegate(original, actual);
	}
}
