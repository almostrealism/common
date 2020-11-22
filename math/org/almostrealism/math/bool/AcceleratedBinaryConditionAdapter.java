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
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.util.Evaluable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public abstract class AcceleratedBinaryConditionAdapter<T extends MemWrapper> extends AcceleratedConditionalStatementAdapter<T> {
	private String operator;
	private Argument leftOperand, rightOperand;
	private Argument trueValue, falseValue;

	private String condition;

	public AcceleratedBinaryConditionAdapter(String operator, int memLength,
											 Function<Integer, Supplier<Evaluable<T>>> blankValue) {
		this(operator, memLength, blankValue, null, null, null, null);
	}

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Function<Integer, Supplier<Evaluable<T>>> blankValue,
											 Supplier<Evaluable> leftOperand,
											 Supplier<Evaluable> rightOperand,
											 Supplier<Evaluable<T>> trueValue,
											 Supplier<Evaluable<T>> falseValue) {
		this(operator, memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue);
	}

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Supplier<Evaluable<T>> blankValue,
											 Supplier<Evaluable> leftOperand,
											 Supplier<Evaluable> rightOperand,
											 Supplier<Evaluable<T>> trueValue,
											 Supplier<Evaluable<T>> falseValue) {
		super(memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
		this.operator = operator;
		this.leftOperand = getArguments().get(1);
		this.rightOperand = getArguments().get(2);
		this.trueValue = getArguments().get(3);
		this.falseValue = getArguments().get(4);
	}

	@Override
	public String getCondition() {
		if (condition == null) {
			StringBuffer buf = new StringBuffer();
			buf.append(getArgumentValueName(1, 0));
			buf.append(" ");
			buf.append(operator);
			buf.append(" ");
			buf.append(getArgumentValueName(2, 0));
			return buf.toString();
		} else {
			return condition;
		}
	}

	@Override
	public List<Argument<Scalar>> getOperands() {
		return Arrays.asList(leftOperand, rightOperand);
	}

	@Override
	public Argument getTrueValue() { return trueValue; }

	@Override
	public Argument getFalseValue() { return falseValue; }

	@Override
	public boolean isCompacted() {
		return super.isCompacted() && condition != null;
	}

	@Override
	public void compact() {
		super.compact();

		if (super.isCompacted() && condition == null) {
			List<Argument<Scalar>> operands = getOperands();

			MultiExpression op1 = (MultiExpression) operands.get(0).getProducer();
			MultiExpression op2 = (MultiExpression) operands.get(1).getProducer();

			addVariable(new Variable<>(getVariableName(0), true, op1.getValue(0), operands.get(0).getProducer()));
			addVariable(new Variable<>(getVariableName(1), true, op2.getValue(0), operands.get(1).getProducer()));

			StringBuffer buf = new StringBuffer();
			buf.append(getVariableName(0));
			buf.append(" ");
			buf.append(operator);
			buf.append(" ");
			buf.append(getVariableName(1));
			condition = buf.toString();
		}
	}
}
