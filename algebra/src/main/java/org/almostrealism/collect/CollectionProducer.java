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
 * CollectionProducer<Vector> input = v(Vector.class);
 * CollectionProducer<PackedCollection> result = input
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
 * CollectionProducer<PackedCollection> x = ...; // shape (2, 3, 4)
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
 * CollectionProducer<PackedCollection> a = ...;
 * CollectionProducer<PackedCollection> b = ...;
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
 * CollectionProducer<PackedCollection> data = ...; // shape (10, 5)
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
 * CollectionProducer<PackedCollection> x = ...;
 * CollectionProducer<PackedCollection> y = ...;
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
 * CollectionProducer<PackedCollection> x = ...; // shape (3, 4)
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
 * CollectionProducer<PackedCollection> x = v(PackedCollection.class);
 * CollectionProducer<PackedCollection> y = x.pow(2).sum();
 *
 * // Compute dy/dx
 * CollectionProducer<PackedCollection> gradient = y.delta(x);
 * // Result: 2x (derivative of x^2)
 * }</pre>
 *
 * @param <T>  the shape type produced by this producer
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

	default <V extends PackedCollection> CollectionProducerComputation<V> repeat(int repeat) {
		return repeat(repeat, this);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> repeat(int axis, int repeat) {
		return repeat(axis, repeat, this);
	}

	default <V extends PackedCollection> CollectionProducer subset(TraversalPolicy shape, int... position) {
		return subset(shape, this, position);
	}

	default <V extends PackedCollection> CollectionProducer transpose() {
		TraversalPolicy shape = shape(this);

		if (shape.getTraversalAxis() == 0 && shape.getDimensions() == 2 &&
				shape.length(0) == shape.length(1) &&
				Algebraic.isDiagonal(shape.length(0), this)) {
			// Square, diagonal, 2D matrices are symmetric
			// and do not require any transformation
			return this;
		}

		CollectionProducerComputation<V> result = enumerate(shape.getTraversalAxis() + 1, 1);
		return (CollectionProducer) reshape(shape(result).trim(), result);
	}

	default <V extends PackedCollection> CollectionProducer transpose(int axis) {
		CollectionProducerComputation<V> result = traverse(axis - 1).enumerate(axis, 1);
		return (CollectionProducer) reshape(shape(result).trim(), result);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> enumerate(int len) {
		return enumerate(0, len, len, 1);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> enumerate(int axis, int len) {
		return enumerate(axis, len, len, 1);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> enumerate(int axis, int len, int stride) {
		return enumerate(axis, len, stride, 1);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> enumerate(int axis, int len, int stride, int repeat) {
		return enumerate(axis, len, stride, repeat, this);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> enumerate(TraversalPolicy shape) {
		return enumerate(shape, this);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> enumerate(TraversalPolicy shape, TraversalPolicy stride) {
		return enumerate(shape, stride, this);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> permute(int... order) {
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
	 * @param <V> The type of PackedCollection
	 * @return A CollectionProducerComputation that produces the padded collection
	 * 
	 * @see org.almostrealism.collect.computations.PackedCollectionPad
	 * @see org.almostrealism.collect.CollectionFeatures#pad(Producer, int...)
	 */
	default <V extends PackedCollection> CollectionProducerComputation<V> pad(int... depths) {
		return pad(this, depths);
	}

	default CollectionProducer valueAt(Producer<PackedCollection>... pos) {
		return c((Producer) this, pos);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> map(Function<CollectionProducerComputation<PackedCollection>, CollectionProducer> mapper) {
		return map(this, mapper);
	}

	default <V extends PackedCollection> CollectionProducerComputation<V> map(TraversalPolicy itemShape, Function<CollectionProducerComputation<PackedCollection>, CollectionProducer> mapper) {
		return map(itemShape, this, mapper);
	}

	default <T extends PackedCollection> CollectionProducerComputation<T> reduce(Function<CollectionProducerComputation<?>, CollectionProducer> mapper) {
		return reduce(this, mapper);
	}

	/**
	 * @deprecated Use {@link #repeat(int)}
	 */
	@Deprecated
	default <V extends PackedCollection> CollectionProducer expand(int repeat) {
		return (CollectionProducer) repeat(repeat, this).consolidate();
	}

	default <V extends PackedCollection> CollectionProducer add(Producer<V> value) {
		return add((Producer) this, value);
	}

	default <V extends PackedCollection> CollectionProducer add(double value) {
		return add((Producer) this, c(value));
	}

	default <V extends PackedCollection> CollectionProducer subtract(double value) {
		return subtract((Producer) this, c(value));
	}

	default <V extends PackedCollection> CollectionProducer subtract(Producer<V> value) {
		return subtract((Producer) this, value);
	}

	default <T extends PackedCollection> CollectionProducerComputation<T> subtractIgnoreZero(Producer<T> value) {
		return subtractIgnoreZero((Producer) this, value);
	}

	default <T extends PackedCollection> CollectionProducer mul(double value) {
		return multiply(value);
	}

	default <T extends PackedCollection> CollectionProducer multiply(double value) {
		return multiply((Producer) this, c(value));
	}

	default <V extends PackedCollection> CollectionProducer mul(Producer<V> value) {
		return multiply(value);
	}

	default <V extends PackedCollection> CollectionProducer multiply(Producer<V> value) {
		return multiply((Producer) this, value);
	}

	default <V extends PackedCollection> CollectionProducer div(double value) {
		return divide(value);
	}

	default <V extends PackedCollection> CollectionProducer divide(double value) {
		return divide((Producer) this, c(value));
	}

	default <V extends PackedCollection> CollectionProducer divide(Producer<V> value) {
		return divide((Producer) this, value);
	}

	default <T extends PackedCollection> CollectionProducer sqrt() {
		return sqrt((Producer) this);
	}

	default <T extends PackedCollection> CollectionProducer pow(double value) {
		return pow((Producer) this, c(value));
	}

	default <V extends PackedCollection> CollectionProducer pow(Producer<V> value) {
		return pow((Producer) this, value);
	}

	default <V extends PackedCollection> CollectionProducer reciprocal() {
		return pow(c(-1.0));
	}

	default <V extends PackedCollection> CollectionProducer minus() {
		return minus((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer exp() {
		return exp((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer expIgnoreZero() {
		return (CollectionProducer) expIgnoreZero((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer log() {
		return log((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer sq() {
		return sq((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer abs() {
		return abs((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer magnitude() {
		return magnitude((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer magnitude(int axis) {
		return magnitude(traverse(axis, (Producer) this));
	}

	default <T extends PackedCollection> CollectionProducerComputationBase max(int axis) {
		return max(traverse(axis, (Producer) this));
	}

	default <T extends PackedCollection> CollectionProducerComputationBase max() {
		return max((Producer) this);
	}

	default <T extends PackedCollection> CollectionProducerComputationBase indexOfMax() {
		return indexOfMax((Producer) this);
	}

	default <T extends PackedCollection> CollectionProducerComputationBase min() {
		// TODO  return min((Producer) this);
		throw new UnsupportedOperationException();
	}

	default <V extends PackedCollection> CollectionProducer mod(double mod) {
		return mod((Producer) this, c(mod));
	}

	default <V extends PackedCollection> CollectionProducer mod(Producer<V> mod) {
		return mod((Producer) this, (Producer) mod);
	}

	default <V extends PackedCollection> CollectionProducer sum(int axis) {
		return sum(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection> CollectionProducer sum() {
		return sum((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer mean(int axis) {
		return mean(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection> CollectionProducer mean() {
		return mean((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer subtractMean(int axis) {
		return subtractMean(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection> CollectionProducer subtractMean() {
		return subtractMean((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer variance(int axis) {
		return variance(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection> CollectionProducer variance() {
		return variance((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer sigmoid() {
		return sigmoid((Producer) this);
	}

	default <V extends PackedCollection> CollectionProducer greaterThan(Producer<?> operand,
																		Producer<V> trueValue, Producer<V> falseValue) {
		return greaterThan(operand, trueValue, falseValue, false);
	}

	default <V extends PackedCollection> CollectionProducer greaterThan(Producer<?> operand,
																		Producer<V> trueValue, Producer<V> falseValue,
																		boolean includeEqual) {
		return greaterThan(this, operand, trueValue, falseValue, includeEqual);
	}

	default <T extends PackedCollection> CollectionProducer lessThan(Supplier operand) {
		return lessThan(operand, false);
	}

	default <T extends PackedCollection> CollectionProducer lessThan(Supplier operand, boolean includeEqual) {
		return lessThan(operand, null, null, includeEqual);
	}

	default <T extends PackedCollection> CollectionProducer lessThan(Supplier<Evaluable<? extends PackedCollection>> operand,
																	 Supplier<Evaluable<? extends PackedCollection>> trueValue,
																	 Supplier<Evaluable<? extends PackedCollection>> falseValue) {
		return lessThan(operand, trueValue, falseValue, false);
	}

	default <T extends PackedCollection> CollectionProducer lessThan(Supplier<Evaluable<? extends PackedCollection>> operand,
																	 Supplier<Evaluable<? extends PackedCollection>> trueValue,
																	 Supplier<Evaluable<? extends PackedCollection>> falseValue,
																	 boolean includeEqual) {
		return lessThan(this, (Producer) operand, (Producer) trueValue, (Producer) falseValue, includeEqual);
	}

	/**
	 * Produces 1.0 if this > operand, 0.0 otherwise.
	 */
	default CollectionProducer greaterThan(Producer<?> operand) {
		return greaterThan(this, operand);
	}

	/**
	 * Produces 1.0 if this >= operand, 0.0 otherwise.
	 */
	default CollectionProducer greaterThanOrEqual(Producer<?> operand) {
		return greaterThanOrEqual(this, operand);
	}

	/**
	 * Produces 1.0 if this &lt; operand, 0.0 otherwise.
	 */
	default CollectionProducer lessThan(Producer<?> operand) {
		return lessThan(this, operand);
	}

	/**
	 * Produces 1.0 if this &lt;= operand, 0.0 otherwise.
	 */
	default CollectionProducer lessThanOrEqual(Producer<?> operand) {
		return lessThanOrEqual(this, operand);
	}

	/**
	 * Produces 1.0 if this AND operand are both non-zero, 0.0 otherwise.
	 */
	default CollectionProducer and(Producer<?> operand) {
		return and(this, operand);
	}

	/**
	 * Produces trueValue if this AND operand are both non-zero, otherwise returns falseValue.
	 */
	default <V extends PackedCollection> CollectionProducer and(Producer<?> operand,
																Producer<V> trueValue,
																Producer<V> falseValue) {
		return and(this, operand, trueValue, falseValue);
	}

	default CollectionProducer attemptDelta(Producer<?> target) {
		return attemptDelta(this, target);
	}

	default CollectionProducer delta(Producer<?> target) {
		CollectionProducer delta = attemptDelta(target);
		if (delta != null) return delta;

		throw new UnsupportedOperationException();
	}

	default <V extends PackedCollection> CollectionProducer grad(Producer<?> target, Producer<PackedCollection> gradient) {
		return combineGradient((CollectionProducer) this, (Producer) target, (Producer) gradient);
	}

	default MultiTermDeltaStrategy getDeltaStrategy() {
		return MultiTermDeltaStrategy.NONE;
	}
}
