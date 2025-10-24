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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

import java.util.function.Function;
import java.util.function.IntFunction;

public interface VectorFeatures extends ScalarFeatures {
	default CollectionProducer<Vector> v(Vector value) { return value(value); }

	default CollectionProducer<Vector> value(Vector value) {
		return DefaultTraversableExpressionComputation.fixed(value, Vector.postprocessor());
	}

	default CollectionProducer<Vector> vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default CollectionProducer<Vector> vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default CollectionProducer<Vector> vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	default <T extends PackedCollection<?>> CollectionProducer<Vector> vector(
												Producer<T> x,
												Producer<T> y,
												Producer<T> z) {
		return concat(shape(3), (Producer) x, (Producer) y, (Producer) z);
	}

	default CollectionProducer<Vector> vector(Producer<PackedCollection<?>> bank, int index) {
		CollectionProducerComputationBase c = (CollectionProducerComputationBase)
				c(shape(3), bank, c(3 * index, 3 * index + 1, 3 * index + 2));
		c.setPostprocessor(Vector.postprocessor());
		return c;
	}

	default CollectionProducer<Vector> vector(Producer<?> value) {
		TraversableExpressionComputation c = new DefaultTraversableExpressionComputation(
				"vector", shape(3),
				(Function<TraversableExpression[], CollectionExpression>) args ->
						new IndexProjectionExpression(shape(3), i -> i, args[1]),
				(Producer) value);
		c.setPostprocessor(Vector.postprocessor());
		return c;
	}

	default Producer<Vector> vector() { return Vector.blank(); }

	default <T extends PackedCollection<?>> CollectionProducer<T> x(Producer<Vector> v) {
		return c(v, 0);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> y(Producer<Vector> v) {
		return c(v, 1);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> z(Producer<Vector> v) {
		return c(v, 2);
	}

	@Deprecated
	default CollectionProducer<PackedCollection<?>> dotProduct(Producer<Vector> a, Producer<Vector> b) {
		// Use general tensor operations: multiply element-wise, then sum
		// Works correctly with both single vectors and batches via traversal
		return multiply((Producer) a, (Producer) b).sum();
	}

	default CollectionProducer<Vector> crossProduct(Producer<Vector> a, Producer<Vector> b) {
		return new DefaultTraversableExpressionComputation<>("crossProduct", shape(3), args ->
				CollectionExpression.create(shape(3), idx -> {
					Expression x = Sum.of(
							Product.of(args[1].getValueRelative(e(1)), args[2].getValueRelative(e(2))),
							Product.of(args[1].getValueRelative(e(2)), args[2].getValueRelative(e(1))).minus()
					);
					Expression y = Sum.of(
							Product.of(args[1].getValueRelative(e(2)), args[2].getValueRelative(e(0))),
							Product.of(args[1].getValueRelative(e(0)), args[2].getValueRelative(e(2))).minus()
					);
					Expression z = Sum.of(
							Product.of(args[1].getValueRelative(e(0)), args[2].getValueRelative(e(1))),
							Product.of(args[1].getValueRelative(e(1)), args[2].getValueRelative(e(0))).minus()
					);

					Expression p = idx.imod(3);
					Expression result = conditional(p.eq(1), y, x);
					result = conditional(p.eq(2), z, result);
					return result;
				}), a, b);
	}

	@Deprecated
	default CollectionProducer<Vector> scalarMultiply(Producer<Vector> a, Producer<Scalar> b) {
		return vector(multiply(a, vector(b, b, b)));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> length(int depth, Producer<T> value) {
		return length(traverse(depth, value));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> length(Producer<?> value) {
		return sqrt(lengthSq(value));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lengthSq(Producer<?> value) {
		return multiply((Producer) value, (Producer) value).sum();
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> normalize(Producer<T> value) {
		return multiply(value, length(value).pow(-1.0));
	}

	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
