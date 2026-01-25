/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.collect;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.collect.UniformCollectionExpression;
import io.almostrealism.expression.Absolute;
import io.almostrealism.expression.Floor;
import io.almostrealism.expression.Max;
import io.almostrealism.expression.Min;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.computations.CollectionExponentialComputation;
import org.almostrealism.collect.computations.CollectionLogarithmComputation;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;

/**
 * Factory interface for mathematical functions on collections.
 * This interface provides methods for exponential, logarithm, absolute value,
 * floor, min, max, sigmoid, and other mathematical operations.
 *
 * @author Michael Murray
 * @see CollectionFeatures
 */
public interface MathFeatures extends ArithmeticFeatures {

	/**
	 * Computes the exponential (e^x) of each element in a collection.
	 *
	 * @param value the collection containing values
	 * @return a {@link CollectionProducer} that generates the exponential values
	 */
	default CollectionProducer exp(Producer<PackedCollection> value) {
		return new CollectionExponentialComputation(shape(value), false, value);
	}

	/**
	 * Computes the exponential (e^x) of each element, ignoring zero values.
	 *
	 * @param value the collection containing values
	 * @return a {@link CollectionProducer} that generates the exponential values
	 */
	default CollectionProducer expIgnoreZero(Producer<PackedCollection> value) {
		return new CollectionExponentialComputation(shape(value), true, value);
	}

	/**
	 * Computes the natural logarithm of each element in a collection.
	 *
	 * @param value the collection containing values
	 * @return a {@link CollectionProducer} that generates the logarithm values
	 */
	default CollectionProducer log(Producer<PackedCollection> value) {
		return new CollectionLogarithmComputation(shape(value), value);
	}

	/**
	 * Computes the floor of each element in a collection.
	 *
	 * @param value the collection containing values
	 * @return a {@link CollectionProducerComputationBase} that generates the floored values
	 */
	default CollectionProducerComputationBase floor(Producer<PackedCollection> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation(
				"floor", shape,
				args -> new UniformCollectionExpression("floor", shape, in -> Floor.of(in[0]), args[1]),
				value);
	}

	/**
	 * Computes the element-wise minimum of two collections.
	 *
	 * @param a the first collection
	 * @param b the second collection
	 * @return a {@link CollectionProducerComputationBase} that generates the minimum values
	 */
	default CollectionProducerComputationBase min(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("min", shape,
				args -> new UniformCollectionExpression("min", shape,
								in -> Min.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	/**
	 * Computes the element-wise maximum of two collections.
	 *
	 * @param a the first collection
	 * @param b the second collection
	 * @return a {@link CollectionProducerComputationBase} that generates the maximum values
	 */
	default CollectionProducerComputationBase max(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		TraversalPolicy shape;

		if (shape(a).getSize() == shape(b).getSize()) {
			shape = shape(a);
		} else {
			shape = shape(1);
		}

		return new DefaultTraversableExpressionComputation("max", shape,
				args -> new UniformCollectionExpression("max", shape,
								in -> Max.of(in[0], in[1]), args[1], args[2]),
				a, b);
	}

	/**
	 * Applies the rectified linear unit (ReLU) function to each element.
	 *
	 * @param a the collection to rectify
	 * @return a {@link CollectionProducer} that generates the rectified values
	 */
	default CollectionProducer rectify(Producer<PackedCollection> a) {
		return compute("rectify", shape -> args ->
						rectify(shape, args[1]), a);
	}

	/**
	 * Computes the modulo operation element-wise.
	 *
	 * @param a the dividend collection
	 * @param b the divisor collection
	 * @return a {@link CollectionProducer} that generates the modulo values
	 */
	default CollectionProducer mod(Producer<PackedCollection> a, Producer<PackedCollection> b) {
		return compute("mod", shape -> args ->
						mod(shape, args[1], args[2]), a, b);
	}

	/**
	 * Bounds each element between min and max values.
	 *
	 * @param a the collection to bound
	 * @param min the minimum value
	 * @param max the maximum value
	 * @return a {@link CollectionProducerComputationBase} that generates the bounded values
	 */
	default CollectionProducerComputationBase bound(Producer<PackedCollection> a, double min, double max) {
		return min(max(a, c(min)), c(max));
	}

	/**
	 * Computes the absolute value of each element in a collection.
	 *
	 * @param value the collection containing values
	 * @return a {@link CollectionProducer} that generates the absolute values
	 */
	default CollectionProducer abs(Producer<PackedCollection> value) {
		TraversalPolicy shape = shape(value);
		return new DefaultTraversableExpressionComputation(
				"abs", shape, DeltaFeatures.MultiTermDeltaStrategy.NONE, true,
				args -> new UniformCollectionExpression("abs", shape, in -> new Absolute(in[0]), args[1]),
				value);
	}

	/**
	 * Computes the magnitude (L2 norm) of a vector.
	 *
	 * @param vector the vector to compute magnitude for
	 * @return a {@link CollectionProducer} that generates the magnitude
	 */
	default CollectionProducer magnitude(Producer<PackedCollection> vector) {
		if (shape(vector).getSize() == 1) {
			return abs(vector);
		} else {
			return sq(vector).sum().sqrt();
		}
	}

	/**
	 * Applies the sigmoid activation function to each element.
	 *
	 * @param input the collection to apply sigmoid to
	 * @return a {@link CollectionProducer} that generates the sigmoid values
	 */
	default CollectionProducer sigmoid(Producer<PackedCollection> input) {
		return divide(c(1.0), minus(input).exp().add(c(1.0)));
	}

	// Required for compute method
	CollectionProducer compute(String name, java.util.function.Function<TraversalPolicy, java.util.function.Function<io.almostrealism.collect.TraversableExpression[], io.almostrealism.collect.CollectionExpression>> expression,
							   Producer<PackedCollection>... arguments);
}
