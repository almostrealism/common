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

package org.almostrealism.bool;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.ProducerComputationAdapter;
import io.almostrealism.code.HybridScope;
import io.almostrealism.code.expressions.MultiExpression;
import io.almostrealism.code.Scope;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.DynamicProducerForMemWrapper;
import org.almostrealism.hardware.ExplictBody;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.ProducerCache;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class AcceleratedConditionalStatementAdapter<T extends MemWrapper>
											extends ProducerComputationAdapter<MemWrapper, T>
											implements AcceleratedConditionalStatement<T>,
													ExplictBody<T>, ComputerFeatures {
	public static boolean enableCompaction = false;

	private int memLength;

	private Function<Variable<T>, String> compacted;

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

		List inputs = new ArrayList();
		inputs.add(new DynamicProducerForMemWrapper(args -> blankValue.get(), kernelDestination));
		inputs.add(leftOperand);
		inputs.add(rightOperand);
		inputs.add(trueValue);
		inputs.add(falseValue);
		this.setInputs(inputs);

		init();
	}

	public int getMemLength() { return memLength; }

	/**
	 * @return  "__global"
	 */
	public String getDefaultAnnotation() { return "__global"; }

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);

		// Result should always be first
		ArrayVariable arg = getArgumentForInput(getInputs().get(0));
		if (arg != null) arg.setSortHint(-1);
	}

	public String getBody(Variable<T> outputVariable) {
		if (compacted == null) {
			StringBuffer buf = new StringBuffer();

			buf.append("if (");
			buf.append(getCondition().getExpression());
			buf.append(") {\n");

			for (int i = 0; i < getMemLength(); i++) {
				buf.append("\t");
				buf.append(getVariableValueName(outputVariable, i, true));
				buf.append(" = ");
				buf.append(getVariableValueName(getTrueValue(), i));
				buf.append(";\n");
			}

			buf.append("} else {\n");

			for (int i = 0; i < getMemLength(); i++) {
				buf.append("\t");
				buf.append(getVariableValueName(outputVariable, i, true));
				buf.append(" = ");
				buf.append(getVariableValueName(getFalseValue(), i));
				buf.append(";\n");
			}

			buf.append("}\n");

			return buf.toString();
		} else {
			return compacted.apply(outputVariable);
		}
	}

	@Override
	public Scope<T> getScope() {
		HybridScope<T> scope = new HybridScope<>(this);
		scope.getVariables().addAll(getVariables());
		scope.code().accept(getBody(getOutputVariable()));
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
