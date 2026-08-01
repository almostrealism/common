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

import java.util.function.UnaryOperator;

/**
 * A {@link CollectionExpression} that remaps the index before looking up a value in its input.
 *
 * <p>The {@code indexProjection} function transforms the output index into the index used
 * to query the input operand. This allows the output collection to present a reordered,
 * sliced, or otherwise index-transformed view of the input without copying data.</p>
 *
 * <p>The {@link #delta} implementation propagates the index projection through the
 * derivative so that automatic differentiation respects the remapping.</p>
 */
public class IndexProjectionExpression extends OperandCollectionExpression {
	/** The function that transforms output indices into input indices. */
	private UnaryOperator<Expression<?>> indexProjection;

	/**
	 * Creates an index projection expression.
	 *
	 * @param shape           the output shape
	 * @param indexProjection a function mapping output index expressions to input index expressions
	 * @param input           the input operand from which values are read
	 */
	public IndexProjectionExpression(TraversalPolicy shape,
									 UnaryOperator<Expression<?>> indexProjection,
									 TraversableExpression<Double> input) {
		super(null, shape, input);
		this.indexProjection = indexProjection;
	}

	/** {@inheritDoc} Returns {@code input.getValueAt(indexProjection.apply(index))}. */
	@Override
	public Expression<Double> getValueAt(Expression index) {
		return operands[0].getValueAt(indexProjection.apply(index));
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>If the input is a {@link CollectionExpression}, delegates to its delta and wraps
	 * the result in a new {@code IndexProjectionExpression} that propagates the projection
	 * through the joint (output, target) index space.</p>
	 */
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		TraversableExpression<Double> in = getOperands().get(0);
		if (!(in instanceof CollectionExpression))
			return super.delta(target);

		CollectionExpression delta = ((CollectionExpression) in).delta(target);

		TraversalPolicy outShape = getShape();
		TraversalPolicy inShape = ((CollectionExpression<?>) in).getShape();
		TraversalPolicy targetShape = target.getShape();

		int outSize = outShape.getTotalSize();
		int inSize = inShape.getTotalSize();
		int targetSize = targetShape.getTotalSize();

		TraversalPolicy deltaShape = new TraversalPolicy(inSize, targetSize);
		TraversalPolicy overallShape = new TraversalPolicy(outSize, targetSize);

		TraversalPolicy shape = outShape.append(targetShape);

		UnaryOperator<Expression<?>> project = idx -> {
			Expression pos[] = overallShape.position(idx);
			return deltaShape.index(indexProjection.apply(pos[0]), pos[1]);
		};

		return new IndexProjectionExpression(shape, project, delta);
	}

	/** {@inheritDoc} Applies the index projection to the target index before querying the input. */
	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return operands[0].uniqueNonZeroOffset(globalIndex, localIndex, indexProjection.apply(targetIndex));
	}
}
