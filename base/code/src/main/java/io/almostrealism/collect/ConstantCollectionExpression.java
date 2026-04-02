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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.sequence.Index;

/**
 * A {@link CollectionExpression} whose every element has the same constant value.
 *
 * <p>Regardless of the index, {@link #getValueAt} returns the fixed expression supplied at
 * construction time. When the entire collection is a single-element constant, or when the
 * constant value is zero, the unique non-zero offset is reported as {@code 0} to allow
 * the code generator to elide redundant memory accesses.</p>
 */
public class ConstantCollectionExpression extends CollectionExpressionAdapter {
	/** The constant expression returned for every index. */
	private final Expression<?> value;

	/**
	 * Creates a constant collection expression with the given shape and value.
	 *
	 * @param shape the shape of the collection
	 * @param value the constant expression to return for every element
	 */
	public ConstantCollectionExpression(TraversalPolicy shape, Expression<?> value) {
		super("constant", shape);
		this.value = value;
	}

	/** {@inheritDoc} Returns the constant value regardless of the index. */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		return (Expression) value;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns {@code 0} when the collection has a single element, or when the
	 * constant value is zero (no non-zero elements anywhere). Returns {@code null}
	 * otherwise (the offset is not uniquely determinable).</p>
	 */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (getShape().getTotalSizeLong() == 1) return new IntegerConstant(0);
		if (value.doubleValue().orElse(-1) == 0.0) return new IntegerConstant(0);
		return null;
	}

	/** {@inheritDoc} Returns {@code true}: the value does not depend on the index. */
	@Override
	public boolean isIndexIndependent() { return true; }
}
