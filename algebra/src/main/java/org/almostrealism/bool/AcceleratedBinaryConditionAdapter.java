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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.NAryExpression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

@Deprecated
public abstract class AcceleratedBinaryConditionAdapter<T extends PackedCollection<?>> extends AcceleratedConditionalStatementAdapter<T> {
	private String operator;

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 IntFunction<MemoryBank<T>> kernelDestination,
											 Supplier<Evaluable> leftOperand,
											 Supplier<Evaluable> rightOperand,
											 Supplier<Evaluable<? extends T>> trueValue,
											 Supplier<Evaluable<? extends T>> falseValue) {
		super(memLength, kernelDestination, leftOperand, rightOperand, trueValue, falseValue);
		this.operator = operator;
	}

	@Override
	public Expression getCondition() {
		return new NAryExpression(Boolean.class, operator,
				getArgument(1).getValueRelative(0),
				getArgument(2).getValueRelative(0));
	}

	@Override
	public List<ArrayVariable<Double>> getOperands() {
		return Arrays.asList(getArgument(1), getArgument(2));
	}

	@Override
	public IntFunction<Expression<Double>> getTrueValueExpression() {
		return i -> getArgument(3).getValueRelative(i);
	}

	@Override
	public IntFunction<Expression<Double>> getFalseValueExpression() {
		return i -> getArgument(4).getValueRelative(i);
	}
}
