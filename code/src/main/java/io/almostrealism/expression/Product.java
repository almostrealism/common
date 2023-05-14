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

package io.almostrealism.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class Product extends NAryExpression<Double> {
	public Product(Expression<Double>... values) {
		super(Double.class, "*", values);
	}

	@Override
	public Expression<Double> generate(List<Expression<?>> children) {
		return new Product(children.toArray(new Expression[0]));
	}

	@Override
	public List<Expression<?>> flatten() {
		List<Expression<?>> flat = super.flatten();

		List<Expression<?>> terms = flat.stream()
				.filter(e -> e instanceof Product)
				.flatMap(e -> e.getChildren().stream())
				.collect(Collectors.toList());

		if (terms.size() == 0) return flat;

		List<Expression<?>> children = new ArrayList<>();
		children.addAll(terms);
		children.addAll(flat.stream()
				.filter(e -> !(e instanceof Product))
				.collect(Collectors.toList()));


		return children;
	}

	@Override
	public Expression<Double> simplify() {
		List<Expression<?>> children = super.simplify().flatten().stream()
				.filter(e -> !removeIdentities || e.doubleValue().orElse(-1) != 1.0)
				.collect(Collectors.toList());

		if (children.size() == 1) return (Expression<Double>) children.get(0);
		if (children.size() == 0) return (Expression<Double>) getChildren().iterator().next();

		List<Double> values = children.stream()
				.map(Expression::doubleValue)
				.filter(d -> d.isPresent())
				.map(d -> d.getAsDouble())
				.collect(Collectors.toList());

		if (values.size() <= 1) {
			return generate(children);
		}

		children = children.stream()
				.filter(e -> !e.doubleValue().isPresent())
				.collect(Collectors.toList());

		double product = values.stream().reduce(1.0, (a, b) -> a * b);

		if (product == 0.0) {
			return new DoubleConstant(0.0);
		} else if (product == 1.0) {
			if (children.isEmpty()) return new DoubleConstant(1.0);
			if (children.size() == 1) return (Expression<Double>) children.get(0);
			return generate(children);
		} else {
			List<Expression<?>> newChildren = new ArrayList<>();
			newChildren.addAll(children);
			newChildren.add(new DoubleConstant(product));
			if (newChildren.size() == 1) return (Expression<Double>) newChildren.get(0);
			return generate(newChildren);
		}
	}
}
