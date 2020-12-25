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

package org.almostrealism.bool;

import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.AcceleratedEvaluable;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.DynamicAcceleratedEvaluable;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public abstract class AcceleratedConditionalStatementAdapter<T extends MemWrapper>
											extends DynamicAcceleratedEvaluable<MemWrapper, T>
											implements AcceleratedConditionalStatement<T> {
	private int memLength;

	private BiFunction<Variable<MemWrapper>, List<Variable<?>>, String> compacted;

	public AcceleratedConditionalStatementAdapter(int memLength, Supplier<T> blankValue) {
		super(blankValue);
		this.memLength = memLength;
	}

	public AcceleratedConditionalStatementAdapter(int memLength,
												  Supplier<T> blankValue,
												  Supplier<Evaluable> leftOperand,
												  Supplier<Evaluable> rightOperand,
												  Supplier<Evaluable<? extends T>> trueValue,
												  Supplier<Evaluable<? extends T>> falseValue) {
		super(blankValue, leftOperand, rightOperand, trueValue, falseValue);
		this.memLength = memLength;
	}

	public int getMemLength() { return memLength; }

	@Override
	public String getBody(Variable<MemWrapper> outputVariable, List<Variable<?>> existingVariables) {
		if (compacted == null) {
			StringBuffer buf = new StringBuffer();

			writeVariables(buf::append, existingVariables);

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
			return compacted.apply(outputVariable, existingVariables);
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (!isCompactable()) return;

		DynamicAcceleratedOperation trueOperation =
				(DynamicAcceleratedOperation) (getTrueValue() == null ? null : getTrueValue().getProducer().get());
		DynamicAcceleratedOperation falseOperation =
				(DynamicAcceleratedOperation) (getFalseValue() == null ? null : getFalseValue().getProducer().get());

		if (trueOperation != null) trueOperation.compact();
		if (falseOperation != null) falseOperation.compact();

		compacted = (outputVariable, existingVariables) -> {
			StringBuffer buf = new StringBuffer();

			writeVariables(buf::append, existingVariables);

			List<Variable> allVariables = new ArrayList<>();
			allVariables.addAll(existingVariables);
			allVariables.addAll(getVariables());

			buf.append("if (");
			buf.append(getCondition().getExpression());
			buf.append(") {\n");
			if (trueOperation != null) {
				buf.append(trueOperation.getBody(outputVariable, allVariables));
			}
			buf.append("} else {\n");
			if (falseOperation != null) {
				buf.append(falseOperation.getBody(outputVariable, allVariables));
			}
			buf.append("}\n");

			return buf.toString();
		};

		List<ArrayVariable<? extends MemWrapper>> newArgs = new ArrayList<>();
		if (getArguments().get(0) != null) newArgs.add(getArguments().get(0));
		getOperands().stream().map(ArrayVariable::getProducer).map(Supplier::get)
				.filter(p -> p instanceof OperationAdapter)
				.map(p -> {
					((OperationAdapter) p).compile();
					return AcceleratedEvaluable.excludeResult(((OperationAdapter) p).getArguments());
				})
				.flatMap(List::stream)
				.forEach(arg -> newArgs.add((ArrayVariable) arg));
		getOperands().stream()
				.map(ArrayVariable::getProducer)
				.forEach(this::absorbVariables);

		if (trueOperation != null) {
			trueOperation.compile();
			newArgs.addAll(AcceleratedEvaluable.excludeResult(trueOperation.getArguments()));
		}

		if (falseOperation != null) {
			falseOperation.compile();
			newArgs.addAll(AcceleratedEvaluable.excludeResult(falseOperation.getArguments()));
		}

		setArguments(newArgs);
		removeDuplicateArguments();
	}

	protected boolean isCompactable() {
		if (compacted != null) return false;

		if (getTrueValue() != null && getTrueValue().getProducer().get() instanceof DynamicAcceleratedOperation == false)
			return false;
		if (getFalseValue() != null && getFalseValue().getProducer().get() instanceof DynamicAcceleratedOperation == false)
			return false;
		for (ArrayVariable a : getOperands()) {
			if (a.getProducer() instanceof MultiExpression == false)
				return false;
		}

		return true;
	}

	protected boolean isCompacted() { return compacted != null; }
}
