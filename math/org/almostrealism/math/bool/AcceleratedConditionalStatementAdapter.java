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

package org.almostrealism.math.bool;

import io.almostrealism.code.Argument;
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.OperationAdapter;
import io.almostrealism.code.Variable;
import org.almostrealism.hardware.AcceleratedComputationOperation;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.DynamicAcceleratedOperation;
import org.almostrealism.hardware.DynamicAcceleratedProducer;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public abstract class AcceleratedConditionalStatementAdapter<T extends MemWrapper>
											extends DynamicAcceleratedProducer<MemWrapper, T>
											implements AcceleratedConditionalStatement<T> {
	private int memLength;

	private BiFunction<Variable<MemWrapper>, List<Variable<?>>, String> compacted;

	public AcceleratedConditionalStatementAdapter(int memLength, Supplier<Evaluable<? extends T>> blankValue) {
		super(blankValue);
		this.memLength = memLength;
	}

	public AcceleratedConditionalStatementAdapter(int memLength,
												  Supplier<Evaluable<? extends T>> blankValue,
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
			buf.append(getCondition());
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

		compacted = (outputVariable, existingVariables) -> {
			StringBuffer buf = new StringBuffer();

			writeVariables(buf::append, existingVariables);

			List<Variable> allVariables = new ArrayList<>();
			allVariables.addAll(existingVariables);
			allVariables.addAll(getVariables());

			buf.append("if (");
			buf.append(getCondition());
			buf.append(") {\n");
			if (getTrueValue() != null) {
				buf.append(((DynamicAcceleratedOperation) getTrueValue().getProducer().get()).getBody(outputVariable, allVariables));
			}
			buf.append("} else {\n");
			if (getFalseValue() != null) {
				buf.append(((DynamicAcceleratedOperation) getFalseValue().getProducer().get()).getBody(outputVariable, allVariables));
			}
			buf.append("}\n");

			return buf.toString();
		};

		List<Argument<? extends MemWrapper>> newArgs = new ArrayList<>();
		if (getArguments().get(0) != null) newArgs.add(getArguments().get(0));
//		TODO  May still need to compile them(?)
//		getOperands().stream().map(Argument::getProducer)
//				.filter(p -> p instanceof AcceleratedComputationOperation)
//				.forEach(p -> ((AcceleratedComputationOperation) p).compile());
		getOperands().stream()
				.map(o -> AcceleratedProducer.excludeResult(((OperationAdapter) o.getProducer()).getArguments()))
				.flatMap(List::stream)
				.forEach(arg -> newArgs.add((Argument) arg));
		getOperands().stream()
				.map(Argument::getProducer)
				.forEach(this::absorbVariables);
		if (getTrueValue() != null) {
			if (getTrueValue().getProducer() instanceof AcceleratedComputationOperation) {
				((AcceleratedComputationOperation) getTrueValue().getProducer()).compile();
			}

			newArgs.addAll(AcceleratedProducer.excludeResult(((OperationAdapter)
					getTrueValue().getProducer()).getArguments()));
		}

		if (getFalseValue() != null) {
			if (getFalseValue().getProducer() instanceof AcceleratedComputationOperation) {
				((AcceleratedComputationOperation) getFalseValue().getProducer()).compile();
			}

			newArgs.addAll(AcceleratedProducer.excludeResult(((OperationAdapter)
					getFalseValue().getProducer()).getArguments()));
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
		for (Argument a : getOperands()) {
			if (a.getProducer() instanceof MultiExpression == false)
				return false;
		}

		return true;
	}

	protected boolean isCompacted() { return compacted != null; }
}
