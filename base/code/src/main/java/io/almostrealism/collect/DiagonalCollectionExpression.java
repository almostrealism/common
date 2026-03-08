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

import io.almostrealism.expression.Constant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;

import java.util.OptionalLong;

public class DiagonalCollectionExpression extends CollectionExpressionAdapter {
	public static boolean enableIndexSimplification = true;
	public static boolean enableAutomaticPosition = true;
	public static boolean enableScalableWidth = true;

	private TraversableExpression<Double> values;
	private TraversalPolicy positionShape;

	public DiagonalCollectionExpression(TraversalPolicy shape, TraversableExpression<Double> values) {
		this("diagonal", shape, values);
	}

	protected DiagonalCollectionExpression(String name, TraversalPolicy shape, TraversableExpression<Double> values) {
		super(name, shape);
		this.values = values;

		if (shape.getCountLong() == 1 || shape.getSizeLong() == 1) {
			warn("Suspicious diagonal shape " + shape.toStringDetail());
		}
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
		if (index.isFP()) {
			warn("FP index - " + index.getExpressionSummary());
			index = index.toInt();
		}

		if (enableIndexSimplification) {
			index = index.simplify();
		}

		if (getTotalShape() == null || !getTotalShape().isFixedCount() ||
				getTotalShape().getTotalSizeLong() > getPositionShape().getTotalSizeLong()) {
			index = index.imod(getPositionShape().getTotalSizeLong());
		}

		Expression pos[] = getPosition(index);

		if (enableAutomaticPosition && (pos[0].countNodes() > pos[1].countNodes() || pos[1] instanceof Constant)) {
			return conditional(pos[0].eq(pos[1]), values.getValueAt(pos[1]), e(0));
		} else {
			return conditional(pos[0].eq(pos[1]), values.getValueAt(pos[0]), e(0));
		}
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (!Index.child(globalIndex, localIndex).equals(targetIndex))
			return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);

		OptionalLong width = localIndex.getLimit();
		if (width.isEmpty() || getPositionShape().getSizeLong() % width.getAsLong() != 0)
			return null;

		long r = getPositionShape().getSizeLong() / width.getAsLong();

		if (r != 1) {
			return enableScalableWidth ? ((Expression) globalIndex).divide(r) : null;
		} else {
			return (Expression) globalIndex;
		}
	}
}
