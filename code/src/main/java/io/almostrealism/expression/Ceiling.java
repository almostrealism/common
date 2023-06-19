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

package io.almostrealism.expression;

import java.util.List;
import java.util.OptionalDouble;

public class Ceiling extends Expression<Double> {
	public Ceiling(Expression<Double> input) {
		super(Double.class, null, input);
	}

	@Override
	public String getExpression() {
		OptionalDouble v = getChildren().get(0).doubleValue();
		return v.isPresent() ? "ceil(" + v.getAsDouble()+ ")" : "ceil(" + getChildren().get(0).getExpression() + ")";
	}

	@Override
	public OptionalDouble doubleValue() {
		OptionalDouble v = getChildren().get(0).doubleValue();
		if (v.isPresent()) return OptionalDouble.of(Math.ceil(v.getAsDouble()));
		return OptionalDouble.empty();
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		if (children.size() != 1) {
			throw new UnsupportedOperationException();
		}

		return new Ceiling((Expression<Double>) children.get(0));
	}
}
