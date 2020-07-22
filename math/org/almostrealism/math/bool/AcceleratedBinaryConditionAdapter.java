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

import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.math.DynamicAcceleratedProducer;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.DynamicProducer;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public abstract class AcceleratedBinaryConditionAdapter<T extends MemWrapper> extends AcceleratedConditionalStatementAdapter<T> {
	private String operator;
	private int memLength;
	private Argument leftOperand, rightOperand;
	private Argument trueValue, falseValue;

	private String condition;
	private List<Variable<String>> variables;

	public AcceleratedBinaryConditionAdapter(String operator, int memLength) {
		this(operator, memLength, DynamicProducer.forMemLength());
	}

	public AcceleratedBinaryConditionAdapter(String operator, int memLength,
											 Function<Integer, Producer<? extends MemWrapper>> blankValue) {
		this(operator, memLength, blankValue, null, null, null, null);
	}

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Function<Integer, Producer<? extends MemWrapper>> blankValue,
											 Producer<Scalar> leftOperand,
											 Producer<Scalar> rightOperand,
											 Producer<T> trueValue,
											 Producer<T> falseValue) {
		this(operator, memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue);
	}

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Producer<? extends MemWrapper> blankValue,
											 Producer<Scalar> leftOperand,
											 Producer<Scalar> rightOperand,
											 Producer<T> trueValue,
											 Producer<T> falseValue) {
		super(blankValue, leftOperand, rightOperand, trueValue, falseValue);
		this.operator = operator;
		this.memLength = memLength;
		this.leftOperand = inputProducers[1];
		this.rightOperand = inputProducers[2];
		this.trueValue = inputProducers[3];
		this.falseValue = inputProducers[4];
		this.variables = new ArrayList<>();
	}

	public int getMemLength() { return memLength; }

	@Override
	public List<Variable<String>> getVariables() { return variables; }

	@Override
	public String getCondition() {
		if (condition == null) {
			String v = getFunctionName() + "_v";
			StringBuffer buf = new StringBuffer();
			buf.append(v);
			buf.append("1[");
			buf.append(v);
			buf.append("1Offset] ");
			buf.append(operator);
			buf.append(" ");
			buf.append(v);
			buf.append("2[");
			buf.append(v);
			buf.append("2Offset]");
			return buf.toString();
		} else {
			return condition;
		}
	}

	@Override
	public List<Argument> getOperands() {
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
			List<Argument> operands = getOperands();

			variables.add(new Variable<>(getVariableName(0),
					((DynamicAcceleratedProducerAdapter) operands.get(0).getProducer()).getValue(0)));
			variables.add(new Variable<>(getVariableName(1),
					((DynamicAcceleratedProducerAdapter) operands.get(1).getProducer()).getValue(0)));

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
