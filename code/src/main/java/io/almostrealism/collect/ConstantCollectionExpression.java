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

public class ConstantCollectionExpression extends CollectionExpressionBase {
	private final TraversalPolicy shape;
	private final Expression<?> value;

	public ConstantCollectionExpression(TraversalPolicy shape, Expression<?> value) {
		if (shape == null) {
			throw new IllegalArgumentException("Shape is required");
		}

		this.shape = shape;
		this.value = value;
	}

	@Override
	public TraversalPolicy getShape() {
		return shape;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return (Expression) value;
	}
}
