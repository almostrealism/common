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
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.TraversableExpressionComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

	default ExpressionComputation<Vector> vector(Supplier<Evaluable<? extends PackedCollection<?>>> bank, int index) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValueRelative(index * 3 + i)));
		return (ExpressionComputation<Vector>) new ExpressionComputation<Vector>(expression, bank).setPostprocessor(Vector.postprocessor());
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
	default ExpressionComputation<Scalar> dotProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> Sum.of(
				Product.of(args.get(1).getValueRelative(0), args.get(2).getValueRelative(0)),
				Product.of(args.get(1).getValueRelative(1), args.get(2).getValueRelative(1)),
				Product.of(args.get(1).getValueRelative(2), args.get(2).getValueRelative(2))
				));
		comp.add(args -> expressionForDouble(1.0));
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(comp, (Supplier) a, (Supplier) b).setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducer<Vector> crossProduct(Producer<Vector> a, Producer<Vector> b) {
		return vector(y(a).multiply(z(b)).subtract(z(a).multiply(y(b))),
				z(a).multiply(x(b)).subtract(x(a).multiply(z(b))),
				x(a).multiply(y(b)).subtract(y(a).multiply(x(b))));
	}

	default CollectionProducer<Vector> scalarMultiply(Producer<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default CollectionProducer<Vector> scalarMultiply(Producer<Vector> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b));
	}

	@Deprecated
	default CollectionProducer<Vector> scalarMultiply(Producer<Vector> a, Producer<Scalar> b) {
		return vector(multiply(a, vector(b, b, b)));
	}

	@Deprecated
	default CollectionProducer<Vector> vnormalize(Producer<Vector> p) {
		return vector(normalize(p));
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
