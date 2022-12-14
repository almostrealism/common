/*
 * Copyright 2022 Michael Murray
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
import io.almostrealism.code.ProducerComputationAdapter;
import io.almostrealism.code.HybridScope;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.ExplictBody;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ProducerCache;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class AcceleratedConditionalStatementAdapter<T extends MemoryData>
											extends ProducerComputationAdapter<MemoryData, T>
											implements AcceleratedConditionalStatement<T>,
													DestinationSupport<MemoryData>,
													ComputerFeatures {
	public static boolean enableCompaction = false;

	private final int memLength;

	private Supplier<MemoryData> destination;
	private Function<Variable<T, ?>, String> compacted;

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

	/**
	 * @return  GLOBAL
	 */
	@Override
	public PhysicalScope getDefaultPhysicalScope() { return PhysicalScope.GLOBAL; }

	@Override
	public void setDestination(Supplier<MemoryData> destination) { this.destination = destination; }

	@Override
	public Supplier<MemoryData> getDestination() { return destination; }

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
		scope.code().accept(condition.getExpression().getExpression());
		scope.code().accept(") {\n");

		for (int i = 0; i < getMemLength(); i++) {
			Variable<?, ?> var = new Variable(outputVariable.valueAt(i).getExpression(), getTrueValueExpression().apply(i), outputVariable);
			vars.add(var);

			scope.code().accept("\t");
			scope.code().accept(var.getName());
			scope.code().accept(" = ");
			scope.code().accept(var.getExpression().getExpression());
			scope.code().accept(";\n");
		}

		scope.code().accept("} else {\n");

		for (int i = 0; i < getMemLength(); i++) {
			Variable<?, ?> var = new Variable(outputVariable.valueAt(i).getExpression(), getFalseValueExpression().apply(i), outputVariable);
			vars.add(var);

			scope.code().accept("\t");
			scope.code().accept(var.getName());
			scope.code().accept(" = ");
			scope.code().accept(var.getExpression().getExpression());
			scope.code().accept(";\n");
		}

		scope.code().accept("}\n");

		scope.setDependencies(vars);
		return scope;
	}

	@Override
	public void compact() {
		super.compact();

		if (!enableCompaction || !isCompactable()) return;

		ExplictBody trueOperation =
				(ExplictBody) (getTrueValue() == null ? null : getTrueValue().getProducer().get());
		ExplictBody falseOperation =
				(ExplictBody) (getFalseValue() == null ? null : getFalseValue().getProducer().get());

		compacted = outputVariable -> {
			StringBuffer buf = new StringBuffer();

			buf.append("if (");
			buf.append(getCondition().getExpression());
			buf.append(") {\n");
			if (trueOperation != null) {
				buf.append(trueOperation.getBody(outputVariable));
			}
			buf.append("} else {\n");
			if (falseOperation != null) {
				buf.append(falseOperation.getBody(outputVariable));
			}
			buf.append("}\n");

			return buf.toString();
		};

		getOperands().stream()
				.map(ArrayVariable::getProducer)
				.forEach(this::absorbVariables);
	}

	protected boolean isCompactable() {
		if (compacted != null) return false;

		if (getTrueValue() != null && getTrueValue().getProducer().get() instanceof ExplictBody == false)
			return false;
		if (getFalseValue() != null && getFalseValue().getProducer().get() instanceof ExplictBody == false)
			return false;
		for (ArrayVariable a : getOperands()) {
			if (a.getProducer() instanceof MultiExpression == false)
				return false;
		}

		return true;
	}

	protected boolean isCompacted() { return compacted != null; }

	@Override
	public void destroy() {
		super.destroy();
		ProducerCache.purgeEvaluableCache(this);
	}
}
