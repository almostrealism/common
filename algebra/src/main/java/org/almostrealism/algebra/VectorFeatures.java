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

import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.algebra.computations.StaticVectorComputation;
import org.almostrealism.algebra.computations.VectorExpressionComputation;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversalPolicy;
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
	Scalar half = new Scalar(0.5);
	Scalar two = new Scalar(2.0);

	default ExpressionComputation<Vector> v(Vector value) { return value(value); }

	default ExpressionComputation<Vector> value(Vector value) {
		return ExpressionComputation.fixed(value, Vector.postprocessor());
	}

	default ExpressionComputation<Vector> vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default ExpressionComputation<Vector> vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default ExpressionComputation<Vector> vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	default VectorExpressionComputation vector(Supplier<Evaluable<? extends Scalar>> x,
											   Supplier<Evaluable<? extends Scalar>> y,
											   Supplier<Evaluable<? extends Scalar>> z) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1 + i).getValue(0)));
		return new VectorExpressionComputation(comp, (Supplier) x, (Supplier) y, (Supplier) z);
	}

	default VectorExpressionComputation vector(Supplier<Evaluable<? extends PackedCollection<?>>> bank, int index) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValue(index * 3 + i)));
		return new VectorExpressionComputation(expression, bank);
	}

	default VectorExpressionComputation vector(DynamicCollectionProducerComputationAdapter<?, ?> value) {
		if (value instanceof ExpressionComputation) {
			if (((ExpressionComputation) value).getExpressions().size() != 3) throw new IllegalArgumentException();
			return new VectorExpressionComputation(((ExpressionComputation) value).expression(),
					value.getInputs().subList(1, value.getInputs().size()).toArray(Supplier[]::new));
		} else if (value instanceof Shape) {
			TraversalPolicy shape = ((Shape) value).getShape();

			List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
					IntStream.range(0, shape.getSize()).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
									np -> np.get(1).getValue(i))
							.collect(Collectors.toList());
			return new VectorExpressionComputation(expressions, (Supplier) value);
		} else {
			throw new UnsupportedOperationException();
		}
	}

	default Producer<Vector> vector() { return Vector.blank(); }

	default ScalarExpressionComputation x(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarExpressionComputation(List.of(args -> args.get(1).getValue(0), args -> expressionForDouble(1.0)), (Supplier) v);
	}

	default ScalarExpressionComputation y(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarExpressionComputation(List.of(args -> args.get(1).getValue(1), args -> expressionForDouble(1.0)), (Supplier) v);
	}

	default ScalarExpressionComputation z(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarExpressionComputation(List.of(args -> args.get(1).getValue(2), args -> expressionForDouble(1.0)), (Supplier) v);
	}

	default ScalarExpressionComputation dotProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Sum(
				new Product(args.get(1).getValue(0), args.get(2).getValue(0)),
				new Product(args.get(1).getValue(1), args.get(2).getValue(1)),
				new Product(args.get(1).getValue(2), args.get(2).getValue(2))
				));
		comp.add(args -> expressionForDouble(1.0));
		return new ScalarExpressionComputation(comp, (Supplier) a, (Supplier) b);
	}

	default VectorExpressionComputation crossProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		return vector(y(a).multiply(z(b)).subtract(z(a).multiply(y(b))),
				z(a).multiply(x(b)).subtract(x(a).multiply(z(b))),
				x(a).multiply(y(b)).subtract(y(a).multiply(x(b))));
	}

	default VectorExpressionComputation add(VectorProducerBase value, VectorProducerBase operand) {
		// TODO  Delegate to _add
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expressions =
				IntStream.range(0, 3).mapToObj(i -> (Function<List<MultiExpression<Double>>, Expression<Double>>)
								np -> new Sum(np.get(1).getValue(i), np.get(2).getValue(i)))
						.collect(Collectors.toList());
		return new VectorExpressionComputation(expressions, (Supplier) value, (Supplier) operand);
	}

	default VectorProducerBase subtract(VectorProducerBase value, VectorProducerBase operand) {
		return vector(add(value, minus(operand)));
	}

	default VectorExpressionComputation multiply(VectorProducerBase a, VectorProducerBase b) {
		return multiply(new VectorProducerBase[] { a, b });
	}

	default VectorExpressionComputation multiply(VectorProducerBase... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(1)).toArray(Expression[]::new)));
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(2)).toArray(Expression[]::new)));
		return new VectorExpressionComputation(comp, (Supplier[]) values);
	}

	default VectorExpressionComputation scalarMultiply(VectorProducerBase a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorExpressionComputation scalarMultiply(Producer<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorExpressionComputation scalarMultiply(Producer<Vector> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b));
	}

	@Deprecated
	default VectorExpressionComputation scalarMultiply(Producer<Vector> a, Supplier<Evaluable<? extends Scalar>> b) {
		return vector(multiply(a, vector(b, b, b)));
	}

	default ScalarProducerBase length(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two)).pow(half);
	}

	default ScalarProducerBase lengthSq(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two));
	}

	default VectorExpressionComputation normalize(Supplier<Evaluable<? extends Vector>> p) {
		ScalarProducerBase oneOverLength = length(p).pow(ScalarFeatures.minusOne());
		return vector(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
