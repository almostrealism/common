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

import java.util.List;
import java.util.stream.Collectors;

public class Quotient extends NAryExpression<Double> {
	public Quotient(Expression<Double>... values) {
		super(Double.class, "/", values);
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		return new Quotient(children.toArray(new Expression[0]));
	}

	@Override
	public Expression<Double> simplify() {
		Expression<Double> flat = super.simplify();
		if (!enableSimplification) return flat;
		if (!(flat instanceof Quotient)) return flat;

		List<Expression<?>> children = flat.getChildren().subList(1, flat.getChildren().size()).stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 1.0)
				.collect(Collectors.toList());
		children.add(0, flat.getChildren().get(0));

		if (children.size() == 1) return (Expression<Double>) children.get(0);
		if (children.isEmpty()) return (Expression) getChildren().iterator().next();
		return generate(children);
	}
}
