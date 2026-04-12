/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.expression;

import io.almostrealism.sequence.IndexValues;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * A floor expression that rounds its operand down to the nearest integer.
 *
 * <p>Generates code of the form {@code floor(input)}. When the operand has a statically
 * known double value, the floor result is embedded directly in the generated literal.</p>
 */
public class Floor extends Expression<Double> {
	/**
	 * Constructs a floor expression for the given operand.
	 *
	 * @param input the expression whose value is to be floored
	 */
	public Floor(Expression<Double> input) {
		super(Double.class, input);
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		OptionalDouble v = getChildren().get(0).doubleValue();
		return v.isPresent() ? "floor(" + v.getAsDouble()+ ")" : "floor(" + getChildren().get(0).getExpression(lang) + ")";
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble v = getChildren().get(0).doubleValue();
		if (v.isPresent()) return OptionalDouble.of(Math.floor(v.getAsDouble()));
		return OptionalDouble.empty();
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		OptionalLong v = getChildren().get(0).upperBound(context);
		if (v.isPresent()) return v;
		return OptionalLong.empty();
	}

	@Override
	public boolean isValue(IndexValues values) {
		return getChildren().get(0).isValue(values);
	}

	@Override
	public Number value(IndexValues indexValues) {
		return Math.floor((double) getChildren().get(0).value(indexValues));
	}

	@Override
	public Number evaluate(Number... children) {
		return Math.floor(children[0].doubleValue());
	}

	@Override
	public Expression<Double> recreate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Floor((Expression<Double>) children.get(0));
	}

	/**
	 * Creates a floor expression for the given operand.
	 *
	 * @param in the expression to floor
	 * @return a new {@link Floor} expression
	 */
	public static Expression of(Expression in) {
		return new Floor(in);
	}
}
