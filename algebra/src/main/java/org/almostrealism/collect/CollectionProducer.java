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
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.calculus.DeltaFeatures;
import org.almostrealism.bool.AcceleratedConditionalStatementCollection;
import org.almostrealism.bool.GreaterThanCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;

import java.util.function.Function;
import java.util.function.Supplier;

public interface CollectionProducer<T extends Shape<?>> extends
		CollectionProducerBase<T, CollectionProducer<T>>,
		DeltaFeatures {

	@Override
	default CollectionProducer<T> reshape(int... dims) {
		return CollectionProducerBase.super.reshape(dims);
	}

	@Override
	CollectionProducer<T> reshape(TraversalPolicy shape);

	@Override
	CollectionProducer<T> traverse(int axis);

	@Override
	default CollectionProducer<T> consolidate() {
		return CollectionProducerBase.super.consolidate();
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> repeat(int repeat) {
		return repeat(repeat, this);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> repeat(int axis, int repeat) {
		return repeat(axis, repeat, this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> subset(TraversalPolicy shape, int... position) {
		return subset(shape, this, position);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> transpose() {
		TraversalPolicy shape = shape(this);

		if (shape.getTraversalAxis() == 0 && shape.getDimensions() == 2 &&
				shape.length(0) == shape.length(1) &&
				Algebraic.isDiagonal(shape.length(0), this)) {
			// Square, diagonal, 2D matrices are symmetric
			// and do not require any transformation
			return (CollectionProducer<V>) this;
		}

		CollectionProducerComputation<V> result = enumerate(shape.getTraversalAxis() + 1, 1);
		return (CollectionProducer<V>) reshape(shape(result).trim(), result);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> transpose(int axis) {
		CollectionProducerComputation<V> result = traverse(axis - 1).enumerate(axis, 1);
		return (CollectionProducer<V>) reshape(shape(result).trim(), result);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> enumerate(int len) {
		return enumerate(0, len, len, 1);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> enumerate(int axis, int len) {
		return enumerate(axis, len, len, 1);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> enumerate(int axis, int len, int stride) {
		return enumerate(axis, len, stride, 1);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> enumerate(int axis, int len, int stride, int repeat) {
		return enumerate(axis, len, stride, repeat, this);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> enumerate(TraversalPolicy shape) {
		return enumerate(shape, this);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> enumerate(TraversalPolicy shape, TraversalPolicy stride) {
		return enumerate(shape, stride, this);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> permute(int... order) {
		return permute(this, order);
	}

	/**
	 * Convenience method to apply symmetric padding to this collection.
	 * This method creates a {@link PackedCollectionPad} computation that adds the specified
	 * amount of zero-padding to each dimension.
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
	 * @see PackedCollectionPad
	 * @see org.almostrealism.collect.CollectionFeatures#pad(Producer, int...)
	 */
	default <V extends PackedCollection<?>> CollectionProducerComputation<V> pad(int... depths) {
		return pad(this, depths);
	}

	default CollectionProducer<T> valueAt(Producer<PackedCollection<?>>... pos) {
		return c((Producer) this, pos);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> map(Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return map(this, mapper);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> map(TraversalPolicy itemShape, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return map(itemShape, this, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return reduce(this, mapper);
	}

	/**
	 * @deprecated Use {@link #repeat(int)}
	 */
	@Deprecated
	default <V extends PackedCollection<?>> CollectionProducer<V> expand(int repeat) {
		return (CollectionProducer) repeat(repeat, this).consolidate();
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> expand(int repeat, Function<CollectionProducerComputation<PackedCollection<?>>, CollectionProducer<?>> mapper) {
		return expand(repeat, this, mapper);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> add(Producer<V> value) {
		return add((Producer) this, value);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> add(double value) {
		return add((Producer) this, c(value));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> subtract(double value) {
		return subtract((Producer) this, c(value));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> subtract(Producer<V> value) {
		return subtract((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> subtractIgnoreZero(Producer<T> value) {
		return subtractIgnoreZero((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> mul(double value) {
		return multiply(value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> multiply(double value) {
		return multiply((Producer) this, c(value));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> mul(Producer<V> value) {
		return multiply(value);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> multiply(Producer<V> value) {
		return multiply((Producer) this, value);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> div(double value) {
		return divide(value);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> divide(double value) {
		return divide((Producer) this, c(value));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> divide(Producer<V> value) {
		return divide((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> sqrt() {
		return sqrt((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> pow(double value) {
		return pow((Producer) this, c(value));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> pow(Producer<V> value) {
		return pow((Producer) this, value);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> reciprocal() {
		return pow(c(-1.0));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> minus() {
		return minus((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> exp() {
		return exp((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> expIgnoreZero() {
		return expIgnoreZero((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> log() {
		return log((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> sq() {
		return sq((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> abs() {
		return abs((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> magnitude() {
		return magnitude((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> magnitude(int axis) {
		return magnitude(traverse(axis, (Producer) this));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max(int axis) {
		return max(traverse(axis, (Producer) this));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> max() {
		return max((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> indexOfMax() {
		return indexOfMax((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> min() {
		// TODO  return min((Producer) this);
		throw new UnsupportedOperationException();
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> mod(double mod) {
		return mod((Producer) this, c(mod));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> mod(Producer<V> mod) {
		return mod((Producer) this, (Producer) mod);
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> sum(int axis) {
		return sum(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection<?>> CollectionProducerComputation<V> sum() {
		return sum((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> mean(int axis) {
		return mean(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> mean() {
		return mean((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> subtractMean(int axis) {
		return subtractMean(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> subtractMean() {
		return subtractMean((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> variance(int axis) {
		return variance(traverse(axis, (Producer) this));
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> variance() {
		return variance((Producer) this);
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> sigmoid() {
		return sigmoid((Producer) this);
	}

	default AcceleratedConditionalStatementCollection greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																  Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																  Supplier<Evaluable<? extends PackedCollection<?>>> falseValue) {
		return greaterThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementCollection greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																  Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																  Supplier<Evaluable<? extends PackedCollection<?>>> falseValue,
																  boolean includeEqual) {
		return new GreaterThanCollection(this, operand, trueValue, falseValue, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lessThan(Supplier operand) {
		return lessThan(operand, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lessThan(Supplier operand, boolean includeEqual) {
		return lessThan(operand, null, null, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lessThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																		   Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																		   Supplier<Evaluable<? extends PackedCollection<?>>> falseValue) {
		return lessThan(operand, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lessThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																		   Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																		   Supplier<Evaluable<? extends PackedCollection<?>>> falseValue,
																		   boolean includeEqual) {
		return lessThan(this, (Producer) operand, (Producer) trueValue, (Producer) falseValue, includeEqual);
	}

	default CollectionProducer<T> attemptDelta(Producer<?> target) {
		return attemptDelta(this, target);
	}

	default CollectionProducer<T> delta(Producer<?> target) {
		CollectionProducer<T> delta = attemptDelta(target);
		if (delta != null) return delta;

		throw new UnsupportedOperationException();
	}

	default <V extends PackedCollection<?>> CollectionProducer<V> grad(Producer<?> target, Producer<T> gradient) {
		return combineGradient((CollectionProducer) this, (Producer) target, (Producer) gradient);
	}

	default MultiTermDeltaStrategy getDeltaStrategy() {
		return MultiTermDeltaStrategy.NONE;
	}
}
