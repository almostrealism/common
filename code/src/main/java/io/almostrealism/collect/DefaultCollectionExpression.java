/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

import java.util.function.Function;

public class DefaultCollectionExpression extends CollectionExpressionBase {
	private final TraversalPolicy shape;
	private final Function<Expression<?>, Expression<?>> valueAt;

	public DefaultCollectionExpression(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		if (shape == null) {
			throw new IllegalArgumentException("Shape is required");
		}

		this.shape = shape;
		this.valueAt = valueAt;
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return (Expression) valueAt.apply(index);
	}

	public static CollectionExpression create(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		return new DefaultCollectionExpression(shape, valueAt);
	}
}