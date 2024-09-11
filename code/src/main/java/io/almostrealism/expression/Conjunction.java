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

import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Conjunction extends NAryExpression<Boolean> {
	public Conjunction(List<Expression<?>> values) { super(Boolean.class, "&", values); }

	public Conjunction(Expression<Boolean>... values) {
		super(Boolean.class, "&", values);
	}

	@Override
	public Number evaluate(Number... children) {
		for (Number child : children) {
			if (child.doubleValue() == 0) return 0;
		}

		return 1;
	}

	@Override
	public Expression<Boolean> generate(List<Expression<?>> children) {
		return new Conjunction(children.toArray(new Expression[0]));
	}

	@Override
	public Expression<Boolean> simplify(KernelStructureContext context, int depth) {
		Expression<Boolean> flat = super.simplify(context, depth);
		if (!(flat instanceof Conjunction)) return flat;

		List<Expression<?>> children = new ArrayList<>();

		for (Expression<?> child : flat.getChildren()) {
			Optional<Boolean> value = child.booleanValue();
			if (value.isPresent()) {
				if (!value.get()) {
					return new BooleanConstant(false);
				}
			} else {
				children.add(child);
			}
		}

		if (children.isEmpty()) return new BooleanConstant(true);
		if (children.size() == 1) return (Expression) children.get(0);
		return new Conjunction(children);
	}

	public static Expression<Boolean> of(Expression<Boolean>... values) {
		return of(List.of(values));
	}

	public static Expression<Boolean> of(List<Expression<?>> values) {
		if (values.size() == 0)
			throw new IllegalArgumentException();

		if (values.size() == 1) return (Expression<Boolean>) values.get(0);
		return new Conjunction(values);
	}
}
