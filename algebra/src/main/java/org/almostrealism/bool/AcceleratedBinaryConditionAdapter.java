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

import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.scope.Variable;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.NAryExpression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.MemoryData;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public abstract class AcceleratedBinaryConditionAdapter<T extends PackedCollection<?>> extends AcceleratedConditionalStatementAdapter<T> {
	private String operator;
	private Expression condition;

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Supplier<T> blankValue,
											 IntFunction<MemoryBank<T>> kernelDestination,
											 Supplier<Evaluable> leftOperand,
											 Supplier<Evaluable> rightOperand,
											 Supplier<Evaluable<? extends T>> trueValue,
											 Supplier<Evaluable<? extends T>> falseValue) {
		super(memLength, blankValue, kernelDestination, leftOperand, rightOperand, trueValue, falseValue);
		this.operator = operator;
	}

	@Override
	public Expression getCondition() {
		if (condition == null) {
			return new NAryExpression(Boolean.class, operator, getInputValue(1, 0), getInputValue(2, 0));
		} else {
			return condition;
		}
	}

	// TODO  Change to List<ArrayVariable<Double>>
	@Override
	public List<ArrayVariable<Scalar>> getOperands() {
		return Arrays.asList(getArgument(1), getArgument(2));
	}

	@Deprecated
	@Override
	public ArrayVariable getTrueValue() { return getArgument(3); }

	@Deprecated
	@Override
	public ArrayVariable getFalseValue() { return getArgument(4); }

	@Override
	public IntFunction<Expression<Double>> getTrueValueExpression() {
		return i -> getInputValue(3, i);
	}

	@Override
	public IntFunction<Expression<Double>> getFalseValueExpression() {
		return i -> getInputValue(4, i);
	}

	@Override
	public boolean isCompacted() { return super.isCompacted() && condition != null; }
}
