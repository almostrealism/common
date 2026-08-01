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
import io.almostrealism.sequence.Index;

import java.util.OptionalLong;

/**
 * A collection expression that represents a diagonal matrix or tensor.
 *
 * <p>For each index, the 2-D position is computed and the value is taken from the
 * {@code values} expression only when the two position coordinates are equal (i.e.,
 * on the diagonal). Off-diagonal elements are zero.</p>
 *
 * <p>The optional {@link #setPositionShape(TraversalPolicy) positionShape} overrides
 * the shape used to compute positions, which is useful when the logical position space
 * differs from the output shape (e.g., for projection deltas).</p>
 */
public class DiagonalCollectionExpression extends CollectionExpressionAdapter {
	/** Whether to simplify the index expression before computing positions. */
	public static boolean enableIndexSimplification = true;

	/**
	 * Whether to automatically choose which position coordinate to use when
	 * looking up the diagonal value, based on expression complexity.
	 */
	public static boolean enableAutomaticPosition = true;

	/** Whether to allow the diagonal width to be scaled by an integer ratio. */
	public static boolean enableScalableWidth = true;

	/** The expression supplying diagonal element values. */
	private TraversableExpression<Double> values;

	/** Optional override of the shape used for position computation. */
	private TraversalPolicy positionShape;

	/**
	 * Creates a diagonal collection expression with the given shape and diagonal values.
	 *
	 * @param shape  the output shape
	 * @param values the expression supplying values along the diagonal
	 */
	public DiagonalCollectionExpression(TraversalPolicy shape, TraversableExpression<Double> values) {
		this("diagonal", shape, values);
	}

	/**
	 * Creates a named diagonal collection expression with the given shape and diagonal values.
	 *
	 * @param name   a descriptive name for this expression
	 * @param shape  the output shape
	 * @param values the expression supplying values along the diagonal
	 */
	protected DiagonalCollectionExpression(String name, TraversalPolicy shape, TraversableExpression<Double> values) {
		super(name, shape);
		this.values = values;

		if (shape.getCountLong() == 1 || shape.getSizeLong() == 1) {
			warn("Suspicious diagonal shape " + shape.toStringDetail());
		}
	}

	/**
	 * Returns the shape used to compute 2-D positions from flat indices.
	 *
	 * <p>Defaults to the output shape if no override has been set.</p>
	 *
	 * @return the position shape
	 */
	public TraversalPolicy getPositionShape() {
		return positionShape == null ? getShape() : positionShape;
	}

	/**
	 * Overrides the shape used to compute 2-D positions from flat indices.
	 *
	 * @param positionShape the position shape to use
	 */
	public void setPositionShape(TraversalPolicy positionShape) {
		this.positionShape = positionShape;
	}

	/**
	 * Returns the 2-D position components for the given flat index within the position shape.
	 *
	 * @param index the flat index expression
	 * @return an array of two position coordinate expressions
	 */
	protected Expression[] getPosition(Expression index) {
		return getPositionShape().flatten(true).position(index);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the diagonal value when the two position coordinates match, or zero otherwise.</p>
	 */
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

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the global index (possibly scaled) as the unique non-zero offset when
	 * the kernel index structure matches the diagonal pattern. Returns {@code null} if
	 * the diagonal width does not divide evenly into the position shape size.</p>
	 */
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
