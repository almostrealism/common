/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.geometry;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.WeightedSumExpression;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.MatrixFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.ReshapeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface TransformMatrixFeatures extends MatrixFeatures {
	boolean enableCollectionExpression = true;

	default CollectionProducer<TransformMatrix> v(TransformMatrix v) { return value(v); }

	default CollectionProducer<TransformMatrix> value(TransformMatrix v) {
		return DefaultTraversableExpressionComputation.fixed(v, TransformMatrix.postprocessor());
	}

	default CollectionProducer<TransformMatrix> translationMatrix(Producer<Vector> offset) {
		CollectionProducer m = pad(shape(4, 4), c(offset).reshape(3, 1), 0, 3)
				.add(identity(4));

		if (m instanceof ReshapeProducer) {
			((CollectionProducerComputationBase) ((ReshapeProducer) m).getComputation())
					.setPostprocessor(TransformMatrix.postprocessor());
		} else {
			((CollectionProducerComputationBase) m)
					.setPostprocessor(TransformMatrix.postprocessor());
		}

		return m;
	}

	default CollectionProducer<TransformMatrix> scaleMatrix(Producer<Vector> scale) {
		CollectionProducerComputationBase m = (CollectionProducerComputationBase)
				diagonal(concat(shape(4), (Producer) scale, c(1.0)));
		return m.setPostprocessor(TransformMatrix.postprocessor());
	}

	default CollectionProducerComputation<Vector> transformAsLocation(TransformMatrix matrix,
																	  Producer<Vector> vector) {
		return transformAsLocation(v(matrix), vector);
	}

	default CollectionProducerComputation<Vector> transformAsLocation(Producer<TransformMatrix> matrix,
																	  Producer<Vector> vector) {
		return transform(matrix, vector, true);
	}

	default CollectionProducerComputation<Vector> transformAsOffset(TransformMatrix matrix,
																	Producer<Vector> vector) {
		return transformAsOffset(v(matrix), vector);
	}

	default CollectionProducerComputation<Vector> transformAsOffset(Producer<TransformMatrix> matrix,
																	Producer<Vector> vector) {
		return transform(matrix, vector, false);
	}

	default CollectionProducerComputation<Vector> transform(Producer<TransformMatrix> matrix,
															Producer<Vector> vector, boolean includeTranslation) {
		TraversalPolicy shape = shape(3);

		vector = concat(shape(4), (Producer) vector, c(1.0));

		DefaultTraversableExpressionComputation c = new DefaultTraversableExpressionComputation<>("transform", shape,
				(Function<TraversableExpression[], CollectionExpression>) args ->
						new WeightedSumExpression(shape, includeTranslation ? 4 : 3, args[1], args[2],
								(groupIndex, operandIndex) -> outputIndex -> {
									if (operandIndex == 0) {
										return e(groupIndex);
									} else if (operandIndex == 1) {
										return (Expression) outputIndex.multiply(4).add(e(groupIndex));
									} else {
										throw new IllegalArgumentException();
									}
								}),
				(Producer) vector, (Producer) matrix);
		c.setPostprocessor(Vector.postprocessor());
		return c;
	}

	static TransformMatrixFeatures getInstance() {
		return new TransformMatrixFeatures() {};
	}
}
