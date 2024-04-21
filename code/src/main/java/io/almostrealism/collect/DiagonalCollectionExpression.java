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
import io.almostrealism.kernel.Index;

public class DiagonalCollectionExpression extends CollectionExpressionAdapter {
	private TraversableExpression<Double> values;
	private TraversalPolicy positionShape;

	public DiagonalCollectionExpression(TraversalPolicy shape, TraversableExpression<Double> values) {
		super(shape);
		this.values = values;
	}

	public TraversalPolicy getPositionShape() {
		return positionShape == null ? getShape() : positionShape;
	}

	public void setPositionShape(TraversalPolicy positionShape) {
		this.positionShape = positionShape;
	}

	protected Expression[] getPosition(Expression index) {
		return getPositionShape().flatten(true).position(index);
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		Expression pos[] = getPosition(index);
		return conditional(pos[0].eq(pos[1]), values.getValueAt(pos[0]), e(0));
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (!Index.child(globalIndex, localIndex).equals(targetIndex))
			return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);

		if (localIndex.getLimit().orElse(-1) != getPositionShape().getSizeLong())
			return null;

		return (Expression) globalIndex;
	}
}
