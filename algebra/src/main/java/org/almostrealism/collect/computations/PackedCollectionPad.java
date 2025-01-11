/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.DefaultCollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Conjunction;
import io.almostrealism.expression.Expression;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PackedCollectionPad<T extends PackedCollection<?>> extends TraversableExpressionComputation<T> {
	private TraversalPolicy inputShape;
	private TraversalPolicy position;

	public PackedCollectionPad(TraversalPolicy shape, TraversalPolicy position,
							   Producer<?> input) {
		super("pad", shape, (Supplier) input);
		this.inputShape = shape(input);
		this.position = position;

		if (shape.getDimensions() != position.getDimensions()) {
			throw new IllegalArgumentException();
		}

		for (int i = 0; i < shape.getDimensions(); i++) {
			if (shape.length(i) < (position.length(i) + inputShape.length(i))) {
				throw new IllegalArgumentException();
			}
		}
	}

	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return DefaultCollectionExpression.create(getShape(), idx -> {
			Expression<?> superPos[] = getShape().position(idx);
			Expression<?> innerPos[] = new Expression[superPos.length];
			List<Expression<?>> conditions = new ArrayList<>();

			for (int i = 0; i < superPos.length; i++) {
				int offset = position.length(i);

				if (offset == 0) {
					innerPos[i] = superPos[i];
				} else {
					innerPos[i] = superPos[i].subtract(offset);
					conditions.add(innerPos[i].greaterThanOrEqual(0));
				}

				if (offset + inputShape.length(i) < getShape().length(i)) {
					conditions.add(innerPos[i].lessThan(inputShape.length(i)));
				}
			}

			Expression<?> out = args[1].getValueAt(inputShape.index(innerPos));
			if (conditions.isEmpty()) return out;

			return conditional(Conjunction.of(conditions), out, e(0.0));
		});
	}

	@Override
	public PackedCollectionPad<T> generate(List<Process<?, ?>> children) {
		return new PackedCollectionPad<>(getShape(), position, (Producer<?>) children.get(1));
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		Supplier in = getInputs().get(1);

		TraversalPolicy shape = getShape();
		TraversalPolicy targetShape = shape(target);
		TraversalPolicy deltaShape = shape.append(targetShape);

		TraversalPolicy position = this.position;
		while (position.getDimensions() < deltaShape.getDimensions()) {
			position = position.appendDimension(0);
		}

		return new PackedCollectionPad<>(deltaShape, position, delta((Producer) in, target));
	}
}
