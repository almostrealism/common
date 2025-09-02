/*
 * Copyright 2023 Michael Murray
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
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface VectorFeatures extends CollectionFeatures {
	default CollectionProducer<Vector> v(Vector value) { return value(value); }

	default CollectionProducer<Vector> value(Vector value) {
		return ExpressionComputation.fixed(value, Vector.postprocessor());
	}

	default CollectionProducer<Vector> vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default CollectionProducer<Vector> vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default CollectionProducer<Vector> vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	default ExpressionComputation<Vector> vector(Supplier<Evaluable<? extends Scalar>> x,
											   Supplier<Evaluable<? extends Scalar>> y,
											   Supplier<Evaluable<? extends Scalar>> z) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1 + i).getValueRelative(0)));
		return (ExpressionComputation<Vector>) new ExpressionComputation<Vector>(comp, (Supplier) x, (Supplier) y, (Supplier) z)
				.setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> vector(Supplier<Evaluable<? extends PackedCollection<?>>> bank, int index) {
		List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValueRelative(index * 3 + i)));
		return (ExpressionComputation<Vector>) new ExpressionComputation<Vector>(expression, bank).setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> vector(Producer<?> value) {
		if (value instanceof ExpressionComputation) {
			if (((ExpressionComputation) value).expression().size() != 3)
				throw new IllegalArgumentException();

			ExpressionComputation<Vector> c = (ExpressionComputation) value;

			return (ExpressionComputation<Vector>) new ExpressionComputation<Vector>(c.expression(),
						c.getInputs().subList(1, c.getInputs().size()).toArray(Supplier[]::new))
					.setPostprocessor(Vector.postprocessor());
		} else if (value instanceof Shape) {
			TraversalPolicy shape = ((Shape) value).getShape();

			List<Function<List<ArrayVariable<Double>>, Expression<Double>>> expressions =
					IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<ArrayVariable<Double>>, Expression<Double>>)
									np -> np.get(1).getValueRelative(i))
							.collect(Collectors.toList());
			return (ExpressionComputation<Vector>) new ExpressionComputation<>(expressions, (Supplier) value)
					.setPostprocessor(Vector.postprocessor());
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default Producer<Vector> vector() { return Vector.blank(); }

	default CollectionProducerComputationBase<?, Scalar> x(Supplier<Evaluable<? extends Vector>> v) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(0),
				args -> expressionForDouble(1.0)),
				(Supplier) v).setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducerComputationBase<?, Scalar> y(Supplier<Evaluable<? extends Vector>> v) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(1),
				args -> expressionForDouble(1.0)),
				(Supplier) v).setPostprocessor(Scalar.postprocessor());
	}

	default CollectionProducerComputationBase<?, Scalar> z(Supplier<Evaluable<? extends Vector>> v) {
		return (ExpressionComputation<Scalar>) new ExpressionComputation<>(List.of(
				args -> args.get(1).getValueRelative(2),
				args -> expressionForDouble(1.0)),
				(Supplier) v).setPostprocessor(Scalar.postprocessor());
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

	default ExpressionComputation<Vector> crossProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		return vector(y(a).multiply(z(b)).subtract(z(a).multiply(y(b))),
				z(a).multiply(x(b)).subtract(x(a).multiply(z(b))),
				x(a).multiply(y(b)).subtract(y(a).multiply(x(b))));
	}

	default ExpressionComputation<Vector> scalarMultiply(Producer<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default ExpressionComputation<Vector> scalarMultiply(Producer<Vector> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b));
	}

	@Deprecated
	default ExpressionComputation<Vector> scalarMultiply(Producer<Vector> a, Supplier<Evaluable<? extends Scalar>> b) {
		return vector(multiply(a, vector(b, b, b)));
	}

	@Deprecated
	default CollectionProducer<Scalar> vlength(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(c(2.0)).add(y(v).pow(c(2.0))).add(z(v).pow(c(2.0))).pow(c(0.5));
	}

	@Deprecated
	default CollectionProducer<Scalar> vlengthSq(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(c(2.0)).add(y(v).pow(c(2.0))).add(z(v).pow(c(2.0)));
	}

	@Deprecated
	default ExpressionComputation<Vector> vnormalize(Supplier<Evaluable<? extends Vector>> p) {
		Producer<Scalar> oneOverLength = pow(vlength(p), ScalarFeatures.minusOne());
		return vector(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> length(int depth, Producer<T> value) {
		return length(traverse(depth, value));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> length(Producer<T> value) {
		return sqrt(lengthSq(value));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> lengthSq(Producer<T> value) {
		return multiply(value, value).sum();
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> normalize(Producer<T> value) {
		return multiply(value, length(value).pow(-1.0));
	}

	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
