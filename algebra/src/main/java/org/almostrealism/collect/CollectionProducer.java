/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.collect.CollectionProducerBase;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.bool.AcceleratedConditionalStatementCollection;
import org.almostrealism.bool.GreaterThanCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.function.Function;
import java.util.function.Supplier;

public interface CollectionProducer<T extends Shape<?>> extends CollectionProducerBase<T, CollectionProducer<T>>, Shape<CollectionProducer<T>>, CollectionFeatures {

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> repeat(int repeat) {
		return repeat(repeat, this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int len) {
		return enumerate(0, len, len, 1);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len) {
		return enumerate(axis, len, len, 1);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride) {
		return enumerate(axis, len, stride, 1);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> enumerate(int axis, int len, int stride, int repeat) {
		return enumerate(axis, len, stride, repeat, this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return map(this, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> map(TraversalPolicy itemShape, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return map(itemShape, this, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> reduce(Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return reduce(this, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputation<T> expand(int repeat, Function<CollectionProducerComputation<?>, CollectionProducerComputation<?>> mapper) {
		return expand(repeat, this, mapper);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> add(Producer<T> value) {
		return add((Producer) this, value);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeAdd(Producer<T> value) {
		return relativeAdd((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> subtract(Producer<T> value) {
		return subtract((Producer) this, value);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeSubtract(Producer<T> value) {
		return relativeSubtract((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> multiply(Producer<T> value) {
		return multiply((Producer) this, value);
	}

	@Deprecated
	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> relativeMultiply(Producer<T> value) {
		return relativeMultiply((Supplier) this, (Supplier) value, null);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> divide(Producer<T> value) {
		return divide((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> pow(double value) {
		return pow((Producer) this, c(value));
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> pow(Producer<T> value) {
		return pow((Producer) this, value);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> minus() {
		return minus((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> exp() {
		return exp((Producer) this);
	}

	default <T extends PackedCollection<?>> CollectionProducerComputationBase<T, T> sum() {
		return sum((Producer) this);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand) {
		return _greaterThan(operand, false);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand, boolean includeEqual) {
		return _greaterThan(operand, null, null, includeEqual);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																   Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																   Supplier<Evaluable<? extends PackedCollection<?>>> falseValue) {
		return _greaterThan(operand, trueValue, falseValue, false);
	}

	default AcceleratedConditionalStatementCollection _greaterThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																   Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																   Supplier<Evaluable<? extends PackedCollection<?>>> falseValue,
																   boolean includeEqual) {
		return new GreaterThanCollection(this, operand, trueValue, falseValue, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _lessThan(Supplier operand) {
		return _lessThan(operand, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _lessThan(Supplier operand, boolean includeEqual) {
		return _lessThan(operand, null, null, includeEqual);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _lessThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																Supplier<Evaluable<? extends PackedCollection<?>>> falseValue) {
		return _lessThan(operand, trueValue, falseValue, false);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> _lessThan(Supplier<Evaluable<? extends PackedCollection<?>>> operand,
																Supplier<Evaluable<? extends PackedCollection<?>>> trueValue,
																Supplier<Evaluable<? extends PackedCollection<?>>> falseValue,
																boolean includeEqual) {
		return _lessThan(this, (Producer) operand, (Producer) trueValue, (Producer) falseValue, includeEqual);
	}
}
