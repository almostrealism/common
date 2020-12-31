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

import io.almostrealism.code.ArgumentMap;
import io.almostrealism.code.ArrayVariable;
import io.almostrealism.code.DefaultScopeInputManager;
import io.almostrealism.code.MultiExpression;
import io.almostrealism.code.ScopeInputManager;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.code.expressions.InstanceReference;
import io.almostrealism.code.expressions.NAryExpression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class AcceleratedBinaryConditionAdapter<T extends MemWrapper> extends AcceleratedConditionalStatementAdapter<T> {
	private String operator;
	private Expression condition;

	private Supplier<Evaluable<? extends MemWrapper>> leftOperand, rightOperand;
	private Supplier<Evaluable<? extends MemWrapper>> trueValue, falseValue;
	private ArrayVariable<T> leftOpVar, rightOpVar;
	private ArrayVariable<T> trueVar, falseVar;

	public AcceleratedBinaryConditionAdapter(String operator, int memLength,
											 Function<Integer, Supplier<T>> blankValue) {
		this(operator, memLength, blankValue, null, null, null, null);
	}

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Function<Integer, Supplier<T>> blankValue,
											 Supplier<Evaluable> leftOperand,
											 Supplier<Evaluable> rightOperand,
											 Supplier<Evaluable<? extends T>> trueValue,
											 Supplier<Evaluable<? extends T>> falseValue) {
		this(operator, memLength, blankValue.apply(memLength), leftOperand, rightOperand, trueValue, falseValue);
	}

	public AcceleratedBinaryConditionAdapter(String operator,
											 int memLength,
											 Supplier<T> blankValue,
											 Supplier<Evaluable> leftOperand,
											 Supplier<Evaluable> rightOperand,
											 Supplier<Evaluable<? extends T>> trueValue,
											 Supplier<Evaluable<? extends T>> falseValue) {
		super(memLength, blankValue, leftOperand, rightOperand, trueValue, falseValue);
		this.operator = operator;
		this.leftOperand = getInputs().get(1);
		this.rightOperand = getInputs().get(2);
		this.trueValue = getInputs().get(3);
		this.falseValue = getInputs().get(4);
	}

	@Override
	public void prepareArguments(ArgumentMap map) {
		super.prepareArguments(map);
		ScopeLifecycle.prepareArguments(getOperands().stream(), map);
		ScopeLifecycle.prepareArguments(Stream.of(trueValue), map);
		ScopeLifecycle.prepareArguments(Stream.of(falseValue), map);
	}

	@Override
	public void prepareScope(ScopeInputManager manager) {
		super.prepareScope(manager);
		ScopeLifecycle.prepareScope(getOperands().stream(), manager);
		ScopeLifecycle.prepareScope(Stream.of(trueValue), manager);
		ScopeLifecycle.prepareScope(Stream.of(falseValue), manager);

		this.leftOpVar = getArgumentForInput(leftOperand);
		this.rightOpVar = getArgumentForInput(rightOperand);
		this.trueVar = getArgumentForInput(trueValue);
		this.falseVar = getArgumentForInput(falseValue);
	}

	@Override
	public Expression getCondition() {
		if (condition == null) {
			return new NAryExpression(Boolean.class, operator, getArgument(1).get(0), getArgument(2).get(0));
		} else {
			return condition;
		}
	}

	@Override
	public List<ArrayVariable<Scalar>> getOperands() {
		return Arrays.asList((ArrayVariable<Scalar>) leftOpVar, (ArrayVariable<Scalar>) rightOpVar);
	}

	@Override
	public ArrayVariable getTrueValue() { return trueVar; }

	@Override
	public ArrayVariable getFalseValue() { return falseVar; }

	@Override
	public boolean isCompacted() { return super.isCompacted() && condition != null; }

	@Override
	public void compact() {
		super.compact();
		
		if (super.isCompacted() && condition == null) {
			List<ArrayVariable<Scalar>> operands = getOperands();

			MultiExpression op1 = (MultiExpression) operands.get(0).getProducer();
			MultiExpression op2 = (MultiExpression) operands.get(1).getProducer();

			Variable v0 = new Variable<>(getVariableName(0), true, op1.getValue(0), operands.get(0).getProducer());
			Variable v1 = new Variable<>(getVariableName(1), true, op2.getValue(0), operands.get(1).getProducer());

			addVariable(v0);
			addVariable(v1);

			condition = new NAryExpression(Boolean.class, operator, new InstanceReference<>(v0), new InstanceReference<>(v1));
		}
	}
}
