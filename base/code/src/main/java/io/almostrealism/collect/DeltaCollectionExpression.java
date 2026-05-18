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
import io.almostrealism.sequence.Index;

/**
 * A {@link CollectionExpression} that represents the Jacobian of one collection
 * with respect to another (automatic differentiation).
 *
 * <p>The shape of a {@code DeltaCollectionExpression} is the concatenation of the
 * delta expression's shape and the target expression's shape. For a flat index into
 * the joint space, the high-order portion selects the output element and the
 * low-order portion selects the input (target) element whose partial derivative
 * is being queried.</p>
 */
public class DeltaCollectionExpression extends CollectionExpressionBase {
	/** The expression whose derivatives are being computed. */
	private final CollectionExpression deltaExpression;

	/** The target expression with respect to which derivatives are taken. */
	private final CollectionExpression targetExpression;

	/**
	 * Creates a delta expression that computes the Jacobian of {@code deltaExpression}
	 * with respect to {@code targetExpression}.
	 *
	 * @param deltaExpression  the expression to differentiate
	 * @param targetExpression the expression with respect to which differentiation is performed
	 */
	public DeltaCollectionExpression(CollectionExpression deltaExpression,
									 CollectionExpression targetExpression) {
		this.deltaExpression = deltaExpression;
		this.targetExpression = targetExpression;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the concatenation of the delta expression's shape and the target's shape.</p>
	 */
	@Override
	public TraversalPolicy getShape() {
		return deltaExpression.getShape().append(targetExpression.getShape());
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Selects the output element by the high-order index, computes its delta with
	 * respect to the target expression, then evaluates the result at the low-order index.</p>
	 */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		return deltaExpression.getValueAt(index.divide(targetExpression.getShape().getTotalSize()))
				.delta(targetExpression)
				.getValueAt(index.imod(targetExpression.getShape().getTotalSize()));
	}

	/** {@inheritDoc} */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return deltaExpression.getValueAt(targetIndex.divide(targetExpression.getShape().getTotalSize()))
				.delta(targetExpression)
				.uniqueNonZeroOffset(globalIndex, localIndex,
						targetIndex.imod(targetExpression.getShape().getTotalSize()));
	}
}
