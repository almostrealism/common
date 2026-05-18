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

/**
 * A {@link CollectionExpression} whose element values are computed by a Java lambda.
 *
 * <p>This is the general-purpose collection expression: any index-to-value mapping that
 * can be expressed as a {@link Function} over {@link Expression} objects can be wrapped
 * by this class. Use the static {@link #create} factory method for convenience.</p>
 */
public class DefaultCollectionExpression extends CollectionExpressionAdapter {
	/** The function that maps an index expression to a value expression. */
	private final Function<Expression<?>, Expression<?>> valueAt;

	/**
	 * Creates a collection expression with the given shape and value function.
	 *
	 * @param shape   the shape of the collection
	 * @param valueAt a function mapping an index expression to the corresponding value expression
	 */
	public DefaultCollectionExpression(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		super(null, shape);
		this.valueAt = valueAt;
	}

	/** {@inheritDoc} Returns {@code valueAt.apply(index)}. */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		return (Expression) valueAt.apply(index);
	}

	/**
	 * Creates a {@link CollectionExpressionBase} with the given shape and value function.
	 *
	 * @param shape   the shape of the collection
	 * @param valueAt a function mapping an index expression to the corresponding value expression
	 * @return a new {@code DefaultCollectionExpression}
	 */
	public static CollectionExpressionBase create(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		return new DefaultCollectionExpression(shape, valueAt);
	}
}
