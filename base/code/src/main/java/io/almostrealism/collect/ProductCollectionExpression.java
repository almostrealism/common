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

import io.almostrealism.expression.Product;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link UniformCollectionExpression} that computes the element-wise product of its operands.
 *
 * <p>When {@link #enableDiagonalDelta} is {@code true} and the differentiation target appears
 * exactly once among the operands (with no references to the target in any other operand),
 * the delta is computed as a {@link DiagonalCollectionExpression} whose diagonal values are the
 * product of the remaining operands. This avoids generating a full Jacobian for simple
 * element-wise products.</p>
 */
public class ProductCollectionExpression extends UniformCollectionExpression {
	/**
	 * Whether to use the diagonal optimisation in {@link #delta} when the differentiation
	 * target appears exactly once among the operands.
	 */
	public static boolean enableDiagonalDelta = true;

	/**
	 * Creates a product collection expression with the given shape and operands.
	 *
	 * @param shape    the output shape
	 * @param operands the operand expressions to multiply element-wise
	 */
	public ProductCollectionExpression(TraversalPolicy shape, TraversableExpression... operands) {
		super("product", shape, Product::of, operands);
		setIndexPolicy(UniformCollectionExpression.NonZeroIndexPolicy.DISJUNCTIVE);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>When the diagonal optimisation is enabled and the target appears exactly once among
	 * the operands, returns a {@link DiagonalCollectionExpression} whose diagonal values are
	 * the product of the remaining operands. Otherwise delegates to the parent implementation.</p>
	 */
	@Override
	public CollectionExpression delta(CollectionExpression target) {
		if (!enableDiagonalDelta ||
				!(target instanceof CollectionVariable) ||
				((CollectionVariable) target).getProducer() instanceof TraversableExpression) {
			return super.delta(target);
		}

		List<TraversableExpression<Double>> matches = new ArrayList<>();
		List<TraversableExpression<Double>> others = new ArrayList<>();

		getOperands().forEach(e -> {
			if (TraversableExpression.match(e, target)) {
				matches.add(e);
			} else {
				others.add(e);
			}
		});

		DefaultIndex index = generateTemporaryIndex();

		if (matches.size() != 1 ||
				others.stream().map(e -> e.getValueAt(index))
						.anyMatch(e -> e.containsReference((Variable) target))) {
			return super.delta(target);
		}

		if (others.isEmpty()) {
			throw new UnsupportedOperationException();
		}

		TraversalPolicy shape = getShape().append(target.getShape());
		TraversableExpression<Double> values = others.size() == 1 ?
				others.get(0) : product(shape, others);

		DiagonalCollectionExpression delta = new DiagonalCollectionExpression(shape, values);
		delta.setPositionShape(new TraversalPolicy(getShape().getTotalSize(),
								target.getShape().getTotalSize()).traverse(1));
		return delta;
	}
}
