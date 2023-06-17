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
import io.almostrealism.expression.MultiExpression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.DynamicCollectionProducerComputationAdapter;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public interface VectorFeatures extends CollectionFeatures, HardwareFeatures {
	Producer half = CollectionFeatures.getInstance().c(0.5);
	Producer two = CollectionFeatures.getInstance().c(2.0);

	default ExpressionComputation<Vector> v(Vector value) { return value(value); }

	default ExpressionComputation<Vector> value(Vector value) {
		return ExpressionComputation.fixed(value, Vector.postprocessor());
	}

	default ExpressionComputation<Vector> vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default ExpressionComputation<Vector> vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default ExpressionComputation<Vector> vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	default ExpressionComputation<Vector> vector(Supplier<Evaluable<? extends Scalar>> x,
											   Supplier<Evaluable<? extends Scalar>> y,
											   Supplier<Evaluable<? extends Scalar>> z) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1 + i).getValue(0)));
		return new ExpressionComputation<Vector>(comp, (Supplier) x, (Supplier) y, (Supplier) z)
				.setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> vector(Supplier<Evaluable<? extends PackedCollection<?>>> bank, int index) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValue(index * 3 + i)));
		return new ExpressionComputation<Vector>(expression, bank).setPostprocessor(Vector.postprocessor());
	}

	default ExpressionComputation<Vector> vector(DynamicCollectionProducerComputationAdapter<?, ?> value) {
		if (value instanceof ExpressionComputation) {
			if (((ExpressionComputation) value).expression().size() != 3)
				throw new IllegalArgumentException();

			return new ExpressionComputation<Vector>(((ExpressionComputation) value).expression(),
						value.getInputs().subList(1, value.getInputs().size()).toArray(Supplier[]::new))
					.setPostprocessor(Vector.postprocessor());
		} else if (value instanceof Shape) {
			TraversalPolicy shape = ((Shape) value).getShape();

			List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
					IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
									np -> np.get(1).getValue(i))
							.collect(Collectors.toList());
			return new ExpressionComputation<>(expressions, (Supplier) value)
					.setPostprocessor(Vector.postprocessor());
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default Producer<Vector> vector() { return Vector.blank(); }

	default ExpressionComputation<Scalar> x(Supplier<Evaluable<? extends Vector>> v) {
		return new ExpressionComputation<>(List.of(
				args -> args.get(1).getValue(0),
				args -> expressionForDouble(1.0)),
				(Supplier) v).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> y(Supplier<Evaluable<? extends Vector>> v) {
		return new ExpressionComputation<>(List.of(
				args -> args.get(1).getValue(1),
				args -> expressionForDouble(1.0)),
				(Supplier) v).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> z(Supplier<Evaluable<? extends Vector>> v) {
		return new ExpressionComputation<>(List.of(
				args -> args.get(1).getValue(2),
				args -> expressionForDouble(1.0)),
				(Supplier) v).setPostprocessor(Scalar.postprocessor());
	}

	default ExpressionComputation<Scalar> dotProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Sum(
				new Product(args.get(1).getValue(0), args.get(2).getValue(0)),
				new Product(args.get(1).getValue(1), args.get(2).getValue(1)),
				new Product(args.get(1).getValue(2), args.get(2).getValue(2))
				));
		comp.add(args -> expressionForDouble(1.0));
		return new ExpressionComputation<>(comp, (Supplier) a, (Supplier) b).setPostprocessor(Scalar.postprocessor());
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

	default ExpressionComputation<Scalar> length(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two)).pow(half);
	}

	default ExpressionComputation<Scalar> lengthSq(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two));
	}

	default ExpressionComputation<Vector> normalize(Supplier<Evaluable<? extends Vector>> p) {
		ExpressionComputation<Scalar> oneOverLength = length(p).pow(ScalarFeatures.minusOne());
		return vector(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
