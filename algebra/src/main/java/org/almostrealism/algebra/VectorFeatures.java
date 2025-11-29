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

package org.almostrealism.algebra;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.IndexProjectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Provides convenient factory methods for creating {@link Vector} computations and vector operations.
 *
 * <p>
 * {@link VectorFeatures} extends {@link ScalarFeatures} to provide specialized methods for working
 * with 3D vectors in the computation graph framework. This interface is designed to be mixed into
 * classes that need to create vector computations.
 * </p>
 *
 * <h2>Usage Examples</h2>
 * <pre>{@code
 * public class VectorComputation implements VectorFeatures {
 *     public Producer<PackedCollection> compute() {
 *         // Create constant vectors
 *         CollectionProducer<PackedCollection> v1 = vector(1.0, 0.0, 0.0);
 *         CollectionProducer<PackedCollection> v2 = value(new Vector(0, 1, 0));
 *
 *         // Vector operations
 *         CollectionProducer<PackedCollection> dot = dotProduct(v1, v2);
 *         CollectionProducer<PackedCollection> cross = crossProduct(v1, v2);
 *         CollectionProducer<PackedCollection> normalized = normalize(v1);
 *
 *         // Component extraction
 *         CollectionProducer<PackedCollection> x = x(v1);
 *         CollectionProducer<PackedCollection> y = y(v1);
 *         CollectionProducer<PackedCollection> z = z(v1);
 *
 *         // Dynamic vector from components
 *         return vector(x, y, z);
 *     }
 * }
 * }</pre>
 *
 * <h2>Vector Construction Patterns</h2>
 * <pre>{@code
 * // From explicit coordinates
 * CollectionProducer<PackedCollection> v1 = vector(1.0, 2.0, 3.0);
 *
 * // From array
 * double[] coords = {1, 2, 3};
 * CollectionProducer<PackedCollection> v2 = vector(coords);
 *
 * // From function
 * CollectionProducer<PackedCollection> v3 = vector(i -> i * 2.0);  // (0, 2, 4)
 *
 * // From existing Vector
 * Vector existing = new Vector(1, 2, 3);
 * CollectionProducer<PackedCollection> v4 = v(existing);
 *
 * // From component producers
 * CollectionProducer<PackedCollection> v5 = vector(scalar(1.0), scalar(2.0), scalar(3.0));
 *
 * // From vector bank at index
 * Producer<PackedCollection> bank = vectorBank(10);
 * CollectionProducer<PackedCollection> v6 = vector(bank, 5);  // 6th vector
 * }</pre>
 *
 * @author  Michael Murray
 * @see Vector
 * @see ScalarFeatures
 * @see CollectionProducer
 */
public interface VectorFeatures extends ScalarFeatures {
	/**
	 * Short form of {@link #value(Vector)}.
	 *
	 * @param value  the vector value
	 * @return a producer for the constant vector
	 */
	default CollectionProducer v(Vector value) { return value(value); }

	/**
	 * Creates a {@link CollectionProducer} that produces a constant {@link Vector} value.
	 * This method creates a computation that returns the values from the provided {@link Vector},
	 * effectively creating a constant computation that always returns the same values.
	 *
	 * @param value  the {@link Vector} containing the constant values
	 * @return a {@link CollectionProducer} that evaluates to the specified {@link Vector}
	 */
	default CollectionProducer value(Vector value) {
		return DefaultTraversableExpressionComputation.fixed(value);
	}

	/**
	 * Creates a {@link CollectionProducer} for a constant vector from explicit coordinates.
	 *
	 * @param x  the x coordinate
	 * @param y  the y coordinate
	 * @param z  the z coordinate
	 * @return a producer for the constant vector (x, y, z)
	 */
	default CollectionProducer vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	/**
	 * Creates a {@link CollectionProducer} for a constant vector from an array of coordinates.
	 * The array must contain at least 3 elements.
	 *
	 * @param v  the coordinate array [x, y, z]
	 * @return a producer for the constant vector
	 * @throws ArrayIndexOutOfBoundsException if the array has fewer than 3 elements
	 */
	default CollectionProducer vector(double v[]) { return vector(v[0], v[1], v[2]); }

	/**
	 * Creates a {@link CollectionProducer} for a constant vector from a function.
	 * The function is called with indices 0, 1, 2 to produce the x, y, z coordinates.
	 *
	 * @param values  function mapping index to coordinate value
	 * @return a producer for the constant vector
	 */
	default CollectionProducer vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	/**
	 * Creates a {@link CollectionProducer} for a dynamic vector by concatenating three component producers.
	 * Each component producer should produce a single scalar value.
	 *
	 * @param x  producer for the x coordinate
	 * @param y  producer for the y coordinate
	 * @param z  producer for the z coordinate
	 * @param <T>  the collection type (typically scalar-valued)
	 * @return a producer that combines the three components into a vector
	 */
	default <T extends PackedCollection> CollectionProducer vector(
												Producer<T> x,
												Producer<T> y,
												Producer<T> z) {
		return concat(shape(3), (Producer) x, (Producer) y, (Producer) z);
	}

	/**
	 * Creates a {@link CollectionProducer} that extracts a vector from a vector bank at the specified index.
	 * The vector is stored in the bank as 3 consecutive values starting at position (3 * index).
	 *
	 * @param bank  the packed collection containing multiple vectors
	 * @param index  the index of the vector to extract (0-based)
	 * @return a producer for the vector at the specified index
	 */
	default CollectionProducer vector(Producer<PackedCollection> bank, int index) {
		return c(shape(3), bank, c(3 * index, 3 * index + 1, 3 * index + 2));
	}

	/**
	 * Wraps an arbitrary {@link Producer} as a vector producer.
	 * This method projects the producer's values into a 3-element vector structure.
	 *
	 * @param value  the producer to wrap
	 * @return a vector producer wrapping the input producer
	 */
	default CollectionProducer vector(Producer<?> value) {
		return new DefaultTraversableExpressionComputation(
				"vector", shape(3),
				(Function<TraversableExpression[], CollectionExpression>) args ->
						new IndexProjectionExpression(shape(3), i -> i, args[1]),
				(Producer) value);
	}

	/**
	 * Creates a blank {@link Producer} for a {@link Vector}.
	 * This is typically used as a placeholder or output destination.
	 *
	 * @return a blank vector producer
	 */
	default Producer<PackedCollection> vector() { return Vector.blank(); }

	/**
	 * Extracts the x component (first element) from a vector producer.
	 *
	 * @param v  the vector producer
	 * @param <T>  the collection type
	 * @return a producer for the x component
	 */
	default <T extends PackedCollection> CollectionProducer x(Producer<T> v) {
		return c(v, 0);
	}

	/**
	 * Extracts the y component (second element) from a vector producer.
	 *
	 * @param v  the vector producer
	 * @param <T>  the collection type
	 * @return a producer for the y component
	 */
	default <T extends PackedCollection> CollectionProducer y(Producer<T> v) {
		return c(v, 1);
	}

	/**
	 * Extracts the z component (third element) from a vector producer.
	 *
	 * @param v  the vector producer
	 * @param <T>  the collection type
	 * @return a producer for the z component
	 */
	default <T extends PackedCollection> CollectionProducer z(Producer<T> v) {
		return c(v, 2);
	}

	/**
	 * Computes the dot product (inner product) of two vectors.
	 *
	 * <p>For batch processing with shape (N, 3), this computes the dot product for each
	 * of the N vector pairs, returning a shape (N, 1) result.</p>
	 *
	 * @param a  the first vector
	 * @param b  the second vector
	 * @return a producer for the scalar dot product
	 */
	default CollectionProducer dotProduct(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		CollectionProducer p = multiply(a, b);

		int axis = p.getShape().getDimensions() - 1;
		return multiply(a, b).sum(axis);
	}

	/**
	 * Computes the cross product (vector product) of two vectors.
	 * Returns a x b = (a2*b3 - a3*b2, a3*b1 - a1*b3, a1*b2 - a2*b1).
	 *
	 * <p>
	 * The cross product produces a vector perpendicular to both input vectors,
	 * with magnitude equal to the area of the parallelogram formed by the vectors.
	 * The direction follows the right-hand rule.
	 * </p>
	 *
	 * @param a  the first vector
	 * @param b  the second vector
	 * @return a producer for the cross product vector
	 */
	default CollectionProducer crossProduct(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy inputShape = shape(a);

		return new DefaultTraversableExpressionComputation("crossProduct", inputShape, args ->
				CollectionExpression.create(inputShape, idx -> {
					// For batch processing with shape (N, 3):
					// idx ranges from 0 to N*3-1
					// batchIdx = idx / 3, componentIdx = idx % 3
					Expression batchIdx = idx.divide(e(3)).floor();
					Expression componentIdx = idx.imod(3);

					// Calculate base indices for this batch item
					Expression base = batchIdx.multiply(e(3));

					// Access components using batch-aware indexing
					Expression a0 = args[1].getValueAt(base.add(e(0)));
					Expression a1 = args[1].getValueAt(base.add(e(1)));
					Expression a2 = args[1].getValueAt(base.add(e(2)));
					Expression b0 = args[2].getValueAt(base.add(e(0)));
					Expression b1 = args[2].getValueAt(base.add(e(1)));
					Expression b2 = args[2].getValueAt(base.add(e(2)));

					// Compute cross product components
					Expression x = Sum.of(Product.of(a1, b2), Product.of(a2, b1).minus());
					Expression y = Sum.of(Product.of(a2, b0), Product.of(a0, b2).minus());
					Expression z = Sum.of(Product.of(a0, b1), Product.of(a1, b0).minus());

					// Select component based on idx % 3
					Expression result = conditional(componentIdx.eq(1), y, x);
					result = conditional(componentIdx.eq(2), z, result);
					return result;
				}), (Producer) a, (Producer) b);
	}

	/**
	 * Computes the length (magnitude) of a value at a specified traversal depth.
	 * The depth parameter determines how many dimensions to traverse before computing length.
	 *
	 * @param depth  the traversal depth
	 * @param value  the value producer
	 * @return a producer for the length
	 */
	default CollectionProducer length(int depth, Producer<PackedCollection> value) {
		return length(traverse(depth, value));
	}

	/**
	 * Computes the length (magnitude) of a vector: ||v|| = sqrt(v1^2 + v2^2 + v3^2).
	 *
	 * @param value  the vector producer
	 * @return a producer for the vector length
	 */
	default CollectionProducer length(Producer<PackedCollection> value) {
		return sqrt(lengthSq(value));
	}

	/**
	 * Computes the squared length (squared magnitude) of a vector: ||v||^2 = v1^2 + v2^2 + v3^2.
	 * This is more efficient than {@link #length(Producer)} when only comparisons are needed,
	 * as it avoids the square root computation.
	 *
	 * <p>For batch vectors with shape (N, M), this returns shape (N, 1).
	 * For a single vector with shape (M), this returns shape (1).</p>
	 *
	 * @param value  the vector producer
	 * @return a producer for the squared vector length
	 */
	default CollectionProducer lengthSq(Producer<PackedCollection> value) {
		CollectionProducer squared = multiply(value, value);

		int axis = shape(value).getDimensions() - 1;
		return squared.sum(axis);
	}

	/**
	 * Normalizes a vector to unit length: v_hat = v / ||v||.
	 * The resulting vector has the same direction as the input but magnitude 1.
	 *
	 * <p>For batch vectors with shape (N, M), the length has shape (N, 1),
	 * which is repeated M times to match the input shape for element-wise division.</p>
	 *
	 * @param value  the vector producer
	 * @return a producer for the normalized (unit) vector
	 */
	default CollectionProducer normalize(Producer<PackedCollection> value) {
		TraversalPolicy valueShape = shape(value);
		CollectionProducer invLen = length(value).pow(-1.0);

		// For batch vectors (N, M), length produces (N, 1)
		// We need to repeat along the innermost axis to get (N, M) for proper broadcasting
		if (valueShape.getDimensions() >= 2) {
			int vectorDim = valueShape.length(valueShape.getDimensions() - 1);
			// repeat adds a dimension: (N, 1) -> (N, 1, M)
			// We need to reshape back to (N, M) to match the input shape
			invLen = repeat(vectorDim, invLen).reshape(valueShape);
		}

		return multiply(value, invLen);
	}

	/**
	 * Returns a singleton instance of {@link VectorFeatures}.
	 *
	 * @return a VectorFeatures instance
	 */
	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
