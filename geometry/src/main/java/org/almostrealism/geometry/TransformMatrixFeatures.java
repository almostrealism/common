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

/**
 * A feature interface providing factory methods for creating and manipulating transformation matrices.
 * Extends {@link MatrixFeatures} to provide specialized methods for 4x4 homogeneous transformation matrices.
 *
 * <p>Key capabilities include:</p>
 * <ul>
 *   <li>Creating translation and scale matrices</li>
 *   <li>Transforming vectors as locations (with translation) or offsets (without translation)</li>
 *   <li>Hardware-accelerated matrix-vector multiplication</li>
 * </ul>
 *
 * @author Michael Murray
 * @see TransformMatrix
 * @see MatrixFeatures
 */
public interface TransformMatrixFeatures extends MatrixFeatures {
	/** Controls whether collection expressions are used for optimization. */
	boolean enableCollectionExpression = true;

	/**
	 * Wraps a {@link TransformMatrix} value as a producer.
	 *
	 * @param v the matrix to wrap
	 * @return a producer that yields the specified matrix
	 */
	default CollectionProducer<TransformMatrix> v(TransformMatrix v) { return value(v); }

	/**
	 * Creates a fixed producer for the given {@link TransformMatrix} value.
	 *
	 * @param v the matrix to wrap
	 * @return a producer that yields the specified matrix
	 */
	default CollectionProducer<TransformMatrix> value(TransformMatrix v) {
		return DefaultTraversableExpressionComputation.fixed(v, TransformMatrix.postprocessor());
	}

	/**
	 * Creates a translation matrix from the specified offset vector.
	 * The resulting matrix translates points by (x, y, z) when multiplied.
	 *
	 * @param offset the translation offset vector
	 * @return a producer for the translation matrix
	 */
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

	/**
	 * Creates a scale matrix from the specified scale factors.
	 * The resulting matrix scales points by (sx, sy, sz) when multiplied.
	 *
	 * @param scale a vector containing (scaleX, scaleY, scaleZ) factors
	 * @return a producer for the scale matrix
	 */
	default CollectionProducer<TransformMatrix> scaleMatrix(Producer<Vector> scale) {
		CollectionProducerComputationBase m = (CollectionProducerComputationBase)
				diagonal(concat(shape(4), (Producer) scale, c(1.0)));
		return m.setPostprocessor(TransformMatrix.postprocessor());
	}

	/**
	 * Transforms a vector as a location (point in space), including translation.
	 *
	 * @param matrix the transformation matrix
	 * @param vector the vector to transform
	 * @return a producer for the transformed vector
	 */
	default CollectionProducerComputation<Vector> transformAsLocation(TransformMatrix matrix,
																	  Producer<Vector> vector) {
		return transformAsLocation(v(matrix), vector);
	}

	/**
	 * Transforms a vector as a location (point in space), including translation.
	 *
	 * @param matrix a producer for the transformation matrix
	 * @param vector the vector to transform
	 * @return a producer for the transformed vector
	 */
	default CollectionProducerComputation<Vector> transformAsLocation(Producer<TransformMatrix> matrix,
																	  Producer<Vector> vector) {
		return transform(matrix, vector, true);
	}

	/**
	 * Transforms a vector as an offset (direction), excluding translation.
	 * This is appropriate for transforming direction vectors or normals.
	 *
	 * @param matrix the transformation matrix
	 * @param vector the vector to transform
	 * @return a producer for the transformed vector
	 */
	default CollectionProducerComputation<Vector> transformAsOffset(TransformMatrix matrix,
																	Producer<Vector> vector) {
		return transformAsOffset(v(matrix), vector);
	}

	/**
	 * Transforms a vector as an offset (direction), excluding translation.
	 * This is appropriate for transforming direction vectors or normals.
	 *
	 * @param matrix a producer for the transformation matrix
	 * @param vector the vector to transform
	 * @return a producer for the transformed vector
	 */
	default CollectionProducerComputation<Vector> transformAsOffset(Producer<TransformMatrix> matrix,
																	Producer<Vector> vector) {
		return transform(matrix, vector, false);
	}

	/**
	 * Transforms a vector by a matrix with control over translation inclusion.
	 *
	 * @param matrix a producer for the transformation matrix
	 * @param vector the vector to transform
	 * @param includeTranslation if true, includes translation (for points); if false, excludes it (for directions)
	 * @return a producer for the transformed vector
	 */
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

	/**
	 * Returns a default instance of {@link TransformMatrixFeatures}.
	 * Useful for accessing transformation utilities without implementing the interface.
	 *
	 * @return a default TransformMatrixFeatures instance
	 */
	static TransformMatrixFeatures getInstance() {
		return new TransformMatrixFeatures() {};
	}
}
