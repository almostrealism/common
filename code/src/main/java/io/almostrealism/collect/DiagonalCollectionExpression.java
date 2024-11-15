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
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.kernel.NoOpKernelStructureContext;

public class DiagonalCollectionExpression extends CollectionExpressionAdapter {
	public static boolean enableIndexSimplification = true;

	private TraversableExpression<Double> values;
	private TraversalPolicy positionShape;

	public DiagonalCollectionExpression(TraversalPolicy shape, TraversableExpression<Double> values) {
		super(null, shape);
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
		if (index.isFP()) {
			warn("FP index - " + index.getExpressionSummary());
			index = index.toInt();
		}

		if (enableIndexSimplification) {
			KernelStructureContext ctx = index.getStructureContext();
			if (ctx == null) ctx = new NoOpKernelStructureContext();
			index = index.simplify(ctx);
		}

		if (getTotalShape() == null || !getTotalShape().isFixedCount() ||
				getTotalShape().getTotalSizeLong() > getPositionShape().getTotalSizeLong()) {
			index = index.imod(getPositionShape().getTotalSizeLong());
		}

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
