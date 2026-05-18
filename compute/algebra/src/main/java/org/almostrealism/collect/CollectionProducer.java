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

package org.almostrealism.collect;

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Core interface for producers that generate {@link PackedCollection} instances through computational graphs.
 *
 * <p>
 * {@link CollectionProducer} is the fundamental building block for all collection-based computations
 * in the Almost Realism framework. It provides a rich API for building complex computational graphs
 * through method chaining, supporting:
 * <ul>
 *   <li><b>Shape transformations:</b> reshape, traverse, transpose, subset</li>
 *   <li><b>Arithmetic operations:</b> add, subtract, multiply, divide, pow, sqrt, exp, log</li>
 *   <li><b>Statistical operations:</b> sum, mean, variance, max, min, magnitude</li>
 *   <li><b>Comparison operations:</b> greaterThan, lessThan, and (logical)</li>
 *   <li><b>Advanced transformations:</b> repeat, enumerate, permute, pad, map, reduce</li>
 *   <li><b>Automatic differentiation:</b> delta, grad for gradient computation</li>
 * </ul>
 *
 * <h2>Core Design Principles</h2>
 * <ul>
 *   <li><b>Immutable Operations:</b> All operations return new producers, never modifying the original</li>
 *   <li><b>Deferred Execution:</b> Operations build a computational graph; actual computation happens on evaluation</li>
 *   <li><b>Hardware Acceleration:</b> Computations compile to optimized kernels for CPU/GPU execution</li>
 *   <li><b>Type Safety:</b> Shape information is tracked through the type system where possible</li>
 * </ul>
 *
 * <h2>Method Chaining Example</h2>
 * <pre>{@code
 * // Build a computation graph through method chaining
 * CollectionProducer input = v(Vector.class);
 * CollectionProducer result = input
 *     .reshape(shape(10, 3))      // Reshape to 10x3
 *     .subtract(input.mean(0))    // Subtract mean along axis 0
 *     .divide(input.variance(0))  // Divide by variance
 *     .pow(2.0)                   // Square all elements
 *     .sum();                     // Sum all elements
 *
 * // Execute the graph
 * PackedCollection output = result.get().evaluate();
 * }</pre>
 *
 * <h2>Shape Operations</h2>
 * <p>
 * Shape operations manipulate the traversal policy without changing data:
 * </p>
 * <pre>{@code
 * CollectionProducer x = ...; // shape (2, 3, 4)
 *
 * x.reshape(shape(6, 4))           // Reshape to 6x4
 * x.traverse(1)                    // Traverse along axis 1
 * x.transpose()                    // Transpose matrix (2D only)
 * x.subset(shape(2, 2), 0, 1)      // Extract 2x2 subset starting at (0,1)
 * }</pre>
 *
 * <h2>Arithmetic Operations</h2>
 * <p>
 * Element-wise arithmetic with automatic broadcasting:
 * </p>
 * <pre>{@code
 * CollectionProducer a = ...;
 * CollectionProducer b = ...;
 *
 * a.add(b)           // Element-wise addition
 * a.add(5.0)         // Add scalar to all elements
 * a.multiply(b)      // Element-wise multiplication
 * a.divide(2.0)      // Divide all elements by 2
 * a.pow(2.0)         // Square all elements
 * a.sqrt()           // Square root
 * a.exp()            // e^x
 * a.log()            // ln(x)
 * }</pre>
 *
 * <h2>Statistical Operations</h2>
 * <p>
 * Reduction operations with optional axis specification:
 * </p>
 * <pre>{@code
 * CollectionProducer data = ...; // shape (10, 5)
 *
 * data.sum()         // Sum all elements -> shape (1)
 * data.sum(0)        // Sum along axis 0 -> shape (5)
 * data.mean()        // Mean of all elements
 * data.mean(1)       // Mean along axis 1 -> shape (10)
 * data.variance()    // Variance
 * data.max()         // Maximum value
 * data.magnitude()   // L2 norm
 * }</pre>
 *
 * <h2>Comparison and Logical Operations</h2>
 * <p>
 * Boolean operations that produce 1.0 (true) or 0.0 (false):
 * </p>
 * <pre>{@code
 * CollectionProducer x = ...;
 * CollectionProducer y = ...;
 *
 * x.greaterThan(y)           // 1.0 where x > y, 0.0 elsewhere
 * x.lessThan(y)              // 1.0 where x < y, 0.0 elsewhere
 * x.and(y)                   // 1.0 where both non-zero
 *
 * // Conditional selection
 * x.greaterThan(y, trueVal, falseVal)  // Select based on comparison
 * }</pre>
 *
 * <h2>Advanced Transformations</h2>
 * <pre>{@code
 * CollectionProducer x = ...; // shape (3, 4)
 *
 * x.repeat(5)                // Repeat along axis 0 -> shape (15, 4)
 * x.enumerate(10)            // Enumerate indices -> shape (10)
 * x.permute(1, 0)            // Permute dimensions -> shape (4, 3)
 * x.pad(1, 2)                // Add padding -> shape (5, 8)
 * x.map(elem -> elem.pow(2)) // Map function over elements
 * }</pre>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>
 * Compute gradients for backpropagation:
 * </p>
 * <pre>{@code
 * CollectionProducer x = v(PackedCollection.class);
 * CollectionProducer y = x.pow(2).sum();
 *
 * // Compute dy/dx
 * CollectionProducer gradient = y.delta(x);
 * // Result: 2x (derivative of x^2)
 * }</pre>
 *
 * @author  Michael Murray
 * @see CollectionProducerBase
 * @see DeltaFeatures
 * @see PackedCollection
 * @see CollectionProducerComputation
 */
public interface CollectionProducer extends
		CollectionProducerBase<PackedCollection, CollectionProducer>,
		DeltaFeatures {

	@Override
	default CollectionProducer reshape(int... dims) {
		return CollectionProducerBase.super.reshape(dims);
	}

	@Override
	CollectionProducer reshape(TraversalPolicy shape);

	@Override
	CollectionProducer traverse(int axis);

	@Override
	default CollectionProducer consolidate() {
		return CollectionProducerBase.super.consolidate();
	}

	/**
	 * Repeats this collection the specified number of times along the traversal axis.
	 *
	 * @param repeat the number of repetitions
	 * @return a computation for the repeated collection
	 */
	default CollectionProducerComputation repeat(int repeat) {
		return repeat(repeat, this);
	}

	/**
	 * Repeats this collection the specified number of times along the given axis.
	 *
	 * @param axis   the axis along which to repeat
	 * @param repeat the number of repetitions
	 * @return a computation for the repeated collection
	 */
	default CollectionProducerComputation repeat(int axis, int repeat) {
		return repeat(axis, repeat, this);
	}

	/**
	 * Extracts a contiguous sub-region from this collection.
	 *
	 * @param shape    the shape of the desired subset
	 * @param position the starting position (per dimension) within this collection
	 * @return a producer for the extracted subset
	 */
	default CollectionProducer subset(TraversalPolicy shape, int... position) {
		return subset(shape, this, position);
	}

	/**
	 * Returns the transpose of this collection by enumerating along the traversal axis.
	 * For square diagonal 2D matrices, returns {@code this} directly as they are symmetric.
	 *
	 * @return a producer for the transposed collection
	 */
	default CollectionProducer transpose() {
		TraversalPolicy shape = shape(this);

		if (shape.getTraversalAxis() == 0 && shape.getDimensions() == 2 &&
				shape.length(0) == shape.length(1) &&
				Algebraic.isDiagonal(shape.length(0), this)) {
			// Square, diagonal, 2D matrices are symmetric
			// and do not require any transformation
			return this;
		}

		CollectionProducerComputation result = enumerate(shape.getTraversalAxis() + 1, 1);
		return (CollectionProducer) reshape(shape(result).trim(), result);
	}

	/**
	 * Returns the transpose of this collection along the specified axis.
	 *
	 * @param axis the axis to transpose along
	 * @return a producer for the transposed collection
	 */
	default CollectionProducer transpose(int axis) {
		CollectionProducerComputation result = traverse(axis - 1).enumerate(axis, 1);
		return (CollectionProducer) reshape(shape(result).trim(), result);
	}

	/**
	 * Enumerates this collection with windows of the specified length along axis 0.
	 *
	 * @param len the window length
	 * @return a computation for the enumerated collection
	 */
	default CollectionProducerComputation enumerate(int len) {
		return enumerate(0, len, len, 1);
	}

	/**
	 * Enumerates this collection with windows of the specified length along the given axis.
	 *
	 * @param axis the axis along which to slide the window
	 * @param len  the window length
	 * @return a computation for the enumerated collection
	 */
	default CollectionProducerComputation enumerate(int axis, int len) {
		return enumerate(axis, len, len, 1);
	}

	/**
	 * Enumerates this collection with the specified window length and stride along the given axis.
	 *
	 * @param axis   the axis along which to slide the window
	 * @param len    the window length
	 * @param stride the step size between window positions
	 * @return a computation for the enumerated collection
	 */
	default CollectionProducerComputation enumerate(int axis, int len, int stride) {
		return enumerate(axis, len, stride, 1);
	}

	/**
	 * Enumerates this collection with the specified window length, stride, and repetition count
	 * along the given axis.
	 *
	 * @param axis   the axis along which to slide the window
	 * @param len    the window length
	 * @param stride the step size between window positions
	 * @param repeat the number of times to repeat each window
	 * @return a computation for the enumerated collection
	 */
	default CollectionProducerComputation enumerate(int axis, int len, int stride, int repeat) {
		return enumerate(axis, len, stride, repeat, this);
	}

	/**
	 * Enumerates this collection using the specified window shape.
	 *
	 * @param shape the shape of each enumerated window
	 * @return a computation for the enumerated collection
	 */
	default CollectionProducerComputation enumerate(TraversalPolicy shape) {
		return enumerate(shape, this);
	}

	/**
	 * Enumerates this collection using the specified window shape and stride shape.
	 *
	 * @param shape  the shape of each enumerated window
	 * @param stride the stride shape defining the step between windows
	 * @return a computation for the enumerated collection
	 */
	default CollectionProducerComputation enumerate(TraversalPolicy shape, TraversalPolicy stride) {
		return enumerate(shape, stride, this);
	}

	/**
	 * Permutes the dimensions of this collection in the specified order.
	 *
	 * @param order the new dimension ordering (e.g., {1, 0} swaps dimensions)
	 * @return a computation for the permuted collection
	 */
	default CollectionProducerComputation permute(int... order) {
		return permute(this, order);
	}

	/**
	 * Convenience method to apply symmetric padding to this collection.
	 * This method creates a {@link org.almostrealism.collect.computations.PackedCollectionPad}
	 * computation that adds the specified amount of zero-padding to each dimension.
	 * 
	 * <p><strong>Example:</strong></p>
	 * <pre>{@code
	 * CollectionProducer<?> input = ...; // 2x3 collection
	 * CollectionProducer<?> padded = input.pad(1, 2); // Results in 4x7 collection
	 * // Adds 1 unit of padding to each side of dimension 0: 2 + 1+1 = 4
	 * // Adds 2 units of padding to each side of dimension 1: 3 + 2+2 = 7
	 * }</pre>
	 * 
	 * @param depths Padding depth for each dimension. depths[i] specifies the amount of
	 *               zero-padding to add before and after the data in dimension i
	 * @return A CollectionProducerComputation that produces the padded collection
	 * 
	 * @see org.almostrealism.collect.computations.PackedCollectionPad
	 * @see org.almostrealism.collect.CollectionFeatures#pad(Producer, int...)
	 */
	default CollectionProducerComputation pad(int... depths) {
		return pad(this, depths);
	}

	/**
	 * Selects values from this collection at the given position indices.
	 *
	 * @param pos the position producers specifying which elements to retrieve
	 * @return a producer for the values at the given positions
	 */
	default CollectionProducer valueAt(Producer<PackedCollection>... pos) {
		return c(this, pos);
	}

	/**
	 * Applies the given mapping function to each element of this collection,
	 * using the collection's natural element shape.
	 *
	 * @param mapper a function from an element producer to its mapped result
	 * @return a computation for the mapped collection
	 */
	default CollectionProducerComputation map(Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return map(this, mapper);
	}

	/**
	 * Applies the given mapping function to each item of this collection,
	 * where each item has the specified shape.
	 *
	 * @param itemShape the shape of each item within this collection
	 * @param mapper    a function from an item producer to its mapped result
	 * @return a computation for the mapped collection
	 */
	default CollectionProducerComputation map(TraversalPolicy itemShape, Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return map(itemShape, this, mapper);
	}

	/**
	 * Reduces this entire collection to a scalar by applying the given mapper to the whole collection.
	 *
	 * @param <T>    the collection type
	 * @param mapper a function from the full collection producer to a single-element result
	 * @return a computation for the reduced scalar result
	 */
	default <T extends PackedCollection> CollectionProducerComputation reduce(Function<CollectionProducerComputation, CollectionProducer> mapper) {
		return reduce(this, mapper);
	}

	/**
	 * @deprecated Use {@link #repeat(int)}
	 */
	@Deprecated
	default CollectionProducer expand(int repeat) {
		return repeat(repeat, this).consolidate();
	}

	/**
	 * Adds the given producer to this collection element-wise.
	 *
	 * @param value the collection to add
	 * @return a producer for the element-wise sum
	 */
	default CollectionProducer add(Producer<PackedCollection> value) {
		return add(this, value);
	}

	/**
	 * Adds the given scalar value to every element of this collection.
	 *
	 * @param value the scalar to add
	 * @return a producer for the resulting collection
	 */
	default CollectionProducer add(double value) {
		return add(this, c(value));
	}

	/**
	 * Subtracts the given scalar value from every element of this collection.
	 *
	 * @param value the scalar to subtract
	 * @return a producer for the resulting collection
	 */
	default CollectionProducer subtract(double value) {
		return subtract(this, c(value));
	}

	/**
	 * Subtracts the given collection from this collection element-wise.
	 *
	 * @param value the collection to subtract
	 * @return a producer for the element-wise difference
	 */
	default CollectionProducer subtract(Producer<PackedCollection> value) {
		return subtract(this, value);
	}

	/**
	 * Subtracts the given collection from this collection element-wise, treating zero values
	 * in either operand as if they were the identity (ignoring them).
	 *
	 * @param value the collection to subtract
	 * @return a computation for the result
	 */
	default CollectionProducerComputation subtractIgnoreZero(Producer<PackedCollection> value) {
		return subtractIgnoreZero(this, value);
	}

	/**
	 * Multiplies every element of this collection by the given scalar. Alias for {@link #multiply(double)}.
	 *
	 * @param <T>   the collection type
	 * @param value the scalar multiplier
	 * @return a producer for the scaled collection
	 */
	default <T extends PackedCollection> CollectionProducer mul(double value) {
		return multiply(value);
	}

	/**
	 * Multiplies every element of this collection by the given scalar.
	 *
	 * @param <T>   the collection type
	 * @param value the scalar multiplier
	 * @return a producer for the scaled collection
	 */
	default <T extends PackedCollection> CollectionProducer multiply(double value) {
		return multiply(this, c(value));
	}

	/**
	 * Multiplies this collection by the given producer element-wise. Alias for {@link #multiply(Producer)}.
	 *
	 * @param value the collection to multiply by
	 * @return a producer for the element-wise product
	 */
	default CollectionProducer mul(Producer<PackedCollection> value) {
		return multiply(value);
	}

	/**
	 * Multiplies this collection by the given producer element-wise.
	 *
	 * @param value the collection to multiply by
	 * @return a producer for the element-wise product
	 */
	default CollectionProducer multiply(Producer<PackedCollection> value) {
		return multiply(this, value);
	}

	/**
	 * Divides every element of this collection by the given scalar. Alias for {@link #divide(double)}.
	 *
	 * @param value the scalar divisor
	 * @return a producer for the resulting collection
	 */
	default CollectionProducer div(double value) {
		return divide(value);
	}

	/**
	 * Divides every element of this collection by the given scalar.
	 *
	 * @param value the scalar divisor
	 * @return a producer for the resulting collection
	 */
	default CollectionProducer divide(double value) {
		return divide(this, c(value));
	}

	/**
	 * Divides this collection by the given producer element-wise.
	 *
	 * @param value the collection to divide by
	 * @return a producer for the element-wise quotient
	 */
	default CollectionProducer divide(Producer<PackedCollection> value) {
		return divide(this, value);
	}

	/**
	 * Applies the square root to every element of this collection.
	 *
	 * @param <T> the collection type
	 * @return a producer for the element-wise square root
	 */
	default <T extends PackedCollection> CollectionProducer sqrt() {
		return sqrt(this);
	}

	/**
	 * Raises every element of this collection to the given power.
	 *
	 * @param <T>   the collection type
	 * @param value the exponent
	 * @return a producer for the element-wise power
	 */
	default <T extends PackedCollection> CollectionProducer pow(double value) {
		return pow(this, c(value));
	}

	/**
	 * Raises every element of this collection to the corresponding power in the given producer.
	 *
	 * @param value the exponent collection
	 * @return a producer for the element-wise power
	 */
	default CollectionProducer pow(Producer<PackedCollection> value) {
		return pow(this, value);
	}

	/**
	 * Returns the reciprocal of every element in this collection (equivalent to {@code pow(-1.0)}).
	 *
	 * @return a producer for the element-wise reciprocal
	 */
	default CollectionProducer reciprocal() {
		return pow(c(-1.0));
	}

	/**
	 * Negates every element of this collection.
	 *
	 * @return a producer for the element-wise negation
	 */
	default CollectionProducer minus() {
		return minus(this);
	}

	/**
	 * Applies the natural exponential function (e^x) to every element of this collection.
	 *
	 * @return a producer for the element-wise exponential
	 */
	default CollectionProducer exp() {
		return exp(this);
	}

	/**
	 * Applies the natural exponential function to every element, treating zero inputs
	 * as producing zero outputs rather than 1.0.
	 *
	 * @return a producer for the element-wise exponential, with zero-ignoring behavior
	 */
	default CollectionProducer expIgnoreZero() {
		return expIgnoreZero(this);
	}

	/**
	 * Applies the natural logarithm to every element of this collection.
	 *
	 * @return a producer for the element-wise natural log
	 */
	default CollectionProducer log() {
		return log(this);
	}

	/**
	 * Squares every element of this collection.
	 *
	 * @return a producer for the element-wise square
	 */
	default CollectionProducer sq() {
		return sq(this);
	}

	/**
	 * Applies the absolute value to every element of this collection.
	 *
	 * @return a producer for the element-wise absolute value
	 */
	default CollectionProducer abs() {
		return abs(this);
	}

	/**
	 * Computes the magnitude (Euclidean norm) of this collection treated as a vector.
	 *
	 * @return a producer for the scalar magnitude
	 */
	default CollectionProducer magnitude() {
		return magnitude(this);
	}

	/**
	 * Computes the magnitude (Euclidean norm) along the specified axis.
	 *
	 * @param axis the axis along which to compute the magnitude
	 * @return a producer for the magnitude along the given axis
	 */
	default CollectionProducer magnitude(int axis) {
		return magnitude(traverse(axis, this));
	}

	/**
	 * Returns the maximum element along the specified axis.
	 *
	 * @param <T>  the collection type
	 * @param axis the axis to reduce over
	 * @return a computation for the maximum value along the axis
	 */
	default <T extends PackedCollection> CollectionProducerComputationBase max(int axis) {
		return max(traverse(axis, this));
	}

	/**
	 * Returns the maximum element across the entire collection.
	 *
	 * @param <T> the collection type
	 * @return a computation for the maximum value
	 */
	default <T extends PackedCollection> CollectionProducerComputationBase max() {
		return max(this);
	}

	/**
	 * Returns the index of the maximum element in this collection.
	 *
	 * @param <T> the collection type
	 * @return a computation for the index of the maximum value
	 */
	default <T extends PackedCollection> CollectionProducerComputationBase indexOfMax() {
		return indexOfMax(this);
	}

	/**
	 * Returns the minimum element across this collection.
	 *
	 * @param <T> the collection type
	 * @return a computation for the minimum value
	 * @throws UnsupportedOperationException always (not yet implemented)
	 */
	default <T extends PackedCollection> CollectionProducerComputationBase min() {
		// TODO  return min((Producer) this);
		throw new UnsupportedOperationException();
	}

	/**
	 * Applies element-wise modulo with the given scalar.
	 *
	 * @param mod the modulus value
	 * @return a producer for the element-wise modulo result
	 */
	default CollectionProducer mod(double mod) {
		return mod(this, c(mod));
	}

	/**
	 * Applies element-wise modulo with the given producer.
	 *
	 * @param mod the modulus collection
	 * @return a producer for the element-wise modulo result
	 */
	default CollectionProducer mod(Producer<PackedCollection> mod) {
		return mod(this, mod);
	}

	/**
	 * Computes the sum of all elements along the specified axis.
	 *
	 * @param axis the axis to sum over
	 * @return a producer for the summed result
	 */
	default CollectionProducer sum(int axis) {
		return sum(traverse(axis, this));
	}

	/**
	 * Computes the sum of all elements in this collection.
	 *
	 * @return a producer for the total sum
	 */
	default CollectionProducer sum() {
		return sum(this);
	}

	/**
	 * Computes the mean of all elements along the specified axis.
	 *
	 * @param axis the axis to average over
	 * @return a producer for the mean along the axis
	 */
	default CollectionProducer mean(int axis) {
		return mean(traverse(axis, this));
	}

	/**
	 * Computes the mean of all elements in this collection.
	 *
	 * @return a producer for the overall mean
	 */
	default CollectionProducer mean() {
		return mean(this);
	}

	/**
	 * Subtracts the mean along the specified axis from each element.
	 *
	 * @param axis the axis along which to compute and subtract the mean
	 * @return a producer for the mean-centered collection
	 */
	default CollectionProducer subtractMean(int axis) {
		return subtractMean(traverse(axis, this));
	}

	/**
	 * Subtracts the overall mean from each element of this collection.
	 *
	 * @return a producer for the mean-centered collection
	 */
	default CollectionProducer subtractMean() {
		return subtractMean(this);
	}

	/**
	 * Computes the variance of all elements along the specified axis.
	 *
	 * @param axis the axis along which to compute variance
	 * @return a producer for the variance along the axis
	 */
	default CollectionProducer variance(int axis) {
		return variance(traverse(axis, this));
	}

	/**
	 * Computes the variance of all elements in this collection.
	 *
	 * @return a producer for the overall variance
	 */
	default CollectionProducer variance() {
		return variance(this);
	}

	/**
	 * Applies the sigmoid function (1 / (1 + e^-x)) element-wise to this collection.
	 *
	 * @return a producer for the element-wise sigmoid
	 */
	default CollectionProducer sigmoid() {
		return sigmoid(this);
	}

	/**
	 * Returns the true value where this {@code > operand}, the false value elsewhere.
	 *
	 * @param operand    the collection to compare against
	 * @param trueValue  the value returned where the condition holds
	 * @param falseValue the value returned where the condition does not hold
	 * @return a producer for the conditional result
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> operand,
																		Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue) {
		return greaterThan(operand, trueValue, falseValue, false);
	}

	/**
	 * Returns the true value where this {@code > operand} (or {@code >=} if {@code includeEqual}),
	 * the false value elsewhere.
	 *
	 * @param operand      the collection to compare against
	 * @param trueValue    the value returned where the condition holds
	 * @param falseValue   the value returned where the condition does not hold
	 * @param includeEqual if true, the condition also includes equality
	 * @return a producer for the conditional result
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> operand,
																		Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
																		boolean includeEqual) {
		return greaterThan(this, operand, trueValue, falseValue, includeEqual);
	}

	/**
	 * Returns 1.0 where this {@code < operand}, 0.0 elsewhere.
	 *
	 * @param <T>     the collection type
	 * @param operand the collection or supplier to compare against
	 * @return a producer for the binary comparison result
	 */
	default <T extends PackedCollection> CollectionProducer lessThan(Supplier operand) {
		return lessThan(operand, false);
	}

	/**
	 * Returns 1.0 where this {@code < operand} (or {@code <=} if {@code includeEqual}), 0.0 elsewhere.
	 *
	 * @param <T>          the collection type
	 * @param operand      the collection or supplier to compare against
	 * @param includeEqual if true, the condition also includes equality
	 * @return a producer for the binary comparison result
	 */
	default <T extends PackedCollection> CollectionProducer lessThan(Supplier operand, boolean includeEqual) {
		return lessThan(operand, null, null, includeEqual);
	}

	/**
	 * Returns the true value where this {@code < operand}, the false value elsewhere.
	 *
	 * @param <T>        the collection type
	 * @param operand    the collection supplier to compare against
	 * @param trueValue  the value returned where the condition holds
	 * @param falseValue the value returned where the condition does not hold
	 * @return a producer for the conditional result
	 */
	default <T extends PackedCollection> CollectionProducer lessThan(Supplier<Evaluable<? extends PackedCollection>> operand,
																	 Supplier<Evaluable<? extends PackedCollection>> trueValue,
																	 Supplier<Evaluable<? extends PackedCollection>> falseValue) {
		return lessThan(operand, trueValue, falseValue, false);
	}

	/**
	 * Returns the true value where this {@code < operand} (or {@code <=} if {@code includeEqual}),
	 * the false value elsewhere.
	 *
	 * @param <T>          the collection type
	 * @param operand      the collection supplier to compare against
	 * @param trueValue    the value returned where the condition holds (or null for 1.0)
	 * @param falseValue   the value returned where the condition does not hold (or null for 0.0)
	 * @param includeEqual if true, the condition also includes equality
	 * @return a producer for the conditional result
	 */
	default <T extends PackedCollection> CollectionProducer lessThan(Supplier<Evaluable<? extends PackedCollection>> operand,
																	 Supplier<Evaluable<? extends PackedCollection>> trueValue,
																	 Supplier<Evaluable<? extends PackedCollection>> falseValue,
																	 boolean includeEqual) {
		return lessThan(this, (Producer) operand, (Producer) trueValue, (Producer) falseValue, includeEqual);
	}

	/**
	 * Produces 1.0 if this > operand, 0.0 otherwise.
	 */
	default CollectionProducer greaterThan(Producer<PackedCollection> operand) {
		return greaterThan(this, operand);
	}

	/**
	 * Produces 1.0 if this >= operand, 0.0 otherwise.
	 */
	default CollectionProducer greaterThanOrEqual(Producer<PackedCollection> operand) {
		return greaterThanOrEqual(this, operand);
	}

	/**
	 * Produces 1.0 if this &lt; operand, 0.0 otherwise.
	 */
	default CollectionProducer lessThan(Producer<PackedCollection> operand) {
		return lessThan(this, operand);
	}

	/**
	 * Produces 1.0 if this &lt;= operand, 0.0 otherwise.
	 */
	default CollectionProducer lessThanOrEqual(Producer<PackedCollection> operand) {
		return lessThanOrEqual(this, operand);
	}

	/**
	 * Produces 1.0 if this AND operand are both non-zero, 0.0 otherwise.
	 */
	default CollectionProducer and(Producer<PackedCollection> operand) {
		return and(this, operand);
	}

	/**
	 * Produces trueValue if this AND operand are both non-zero, otherwise returns falseValue.
	 */
	default CollectionProducer and(Producer<PackedCollection> operand,
																Producer<PackedCollection> trueValue,
																Producer<PackedCollection> falseValue) {
		return and(this, operand, trueValue, falseValue);
	}

	/**
	 * Attempts to compute the gradient of this computation with respect to the given target producer.
	 * Returns null if the gradient cannot be determined (e.g., target is not a dependency).
	 *
	 * @param target the producer with respect to which to differentiate
	 * @return a CollectionProducer for the gradient, or null if not computable
	 */
	default CollectionProducer attemptDelta(Producer<?> target) {
		return attemptDelta(this, target);
	}

	/**
	 * Computes the gradient of this computation with respect to the given target producer.
	 * Throws an exception if the gradient cannot be computed.
	 *
	 * @param target the producer with respect to which to differentiate
	 * @return a CollectionProducer for the gradient
	 * @throws UnsupportedOperationException if the gradient cannot be determined
	 */
	default CollectionProducer delta(Producer<?> target) {
		CollectionProducer delta = attemptDelta(target);
		if (delta != null) return delta;

		throw new UnsupportedOperationException();
	}

	/**
	 * Combines the gradient of this computation with the upstream gradient using the chain rule.
	 *
	 * @param target   the parameter with respect to which the gradient is being computed
	 * @param gradient the upstream gradient producer
	 * @return a producer for the combined gradient
	 */
	default CollectionProducer grad(Producer<?> target, Producer<PackedCollection> gradient) {
		return combineGradient(this, (Producer<PackedCollection>) target, gradient);
	}

	/**
	 * Returns the delta (gradient) strategy to use when multiple terms contribute gradients.
	 * The default is {@link MultiTermDeltaStrategy#NONE}.
	 *
	 * @return the strategy for combining multi-term gradients
	 */
	default MultiTermDeltaStrategy getDeltaStrategy() {
		return MultiTermDeltaStrategy.NONE;
	}
}
