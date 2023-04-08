/*
 * Copyright 2022 Michael Murray
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
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.HardwareFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * {@link VectorEvaluable} is implemented by any class that can produce an {@link Vector} object
 * given some array of input objects.
 *
 * @author  Michael Murray
 */
public interface VectorFeatures extends CollectionFeatures, HardwareFeatures {
	Scalar half = new Scalar(0.5);
	Scalar two = new Scalar(2.0);

	static VectorProducer of(Vector value) {
		return new StaticVectorComputation(value);
	}

	default VectorProducer v(Vector value) { return value(value); }

	default VectorProducer value(Vector value) {
		return new StaticVectorComputation(value);
	}

	default VectorProducer vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default VectorProducer vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default VectorProducer vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	default VectorExpressionComputation vector(Supplier<Evaluable<? extends Scalar>> x,
											   Supplier<Evaluable<? extends Scalar>> y,
											   Supplier<Evaluable<? extends Scalar>> z) {
		return fromScalars(x, y, z);
	}

	default VectorExpressionComputation vector(Supplier<Evaluable<? extends PackedCollection<?>>> bank, int index) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> expression = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> expression.add(args -> args.get(1).getValue(index * 3 + i)));
		return new VectorExpressionComputation(expression, bank);
	}

	default Producer<Vector> vector() { return Vector.blank(); }

	default ScalarEvaluable x(Evaluable<Vector> v) {
		return (ScalarEvaluable) x(() -> v).get();
	}

	default ScalarExpressionComputation x(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarExpressionComputation(List.of(args -> args.get(1).getValue(0), args -> expressionForDouble(1.0)), (Supplier) v);
	}

	default ScalarEvaluable y(Evaluable<Vector> v) {
		return (ScalarEvaluable) y(() -> v).get();
	}

	default ScalarExpressionComputation y(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarExpressionComputation(List.of(args -> args.get(1).getValue(1), args -> expressionForDouble(1.0)), (Supplier) v);
	}

	default ScalarEvaluable z(Evaluable<Vector> v) {
		return (ScalarEvaluable) z(() -> v).get();
	}

	default ScalarExpressionComputation z(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarExpressionComputation(List.of(args -> args.get(1).getValue(2), args -> expressionForDouble(1.0)), (Supplier) v);
	}

	default ScalarEvaluable dotProduct(Evaluable<Vector> a, Evaluable<Vector> b) {
		return (ScalarEvaluable) dotProduct(() -> a, () -> b).get();
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

	default VectorEvaluable crossProduct(Evaluable<Vector> a, Evaluable<Vector> b) {
		return (VectorEvaluable) crossProduct(() -> a, () -> b).get();
	}

	default VectorExpressionComputation crossProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		return fromScalars(y(a).multiply(z(b)).subtract(z(a).multiply(y(b))),
				z(a).multiply(x(b)).subtract(x(a).multiply(z(b))),
				x(a).multiply(y(b)).subtract(y(a).multiply(x(b))));
	}

	@Deprecated
	default VectorEvaluable add(Evaluable<Vector> value, Evaluable<Vector> operand) {
		return (VectorEvaluable) add(() -> value, () -> operand).get();
	}

	@Deprecated
	default VectorProducer add(Supplier<Evaluable<? extends Vector>> value, Supplier<Evaluable<? extends Vector>> operand) {
		return new VectorSum(value, operand);
	}

	@Deprecated
	default VectorEvaluable subtract(Evaluable<Vector> value, Evaluable<Vector> operand) {
		return (VectorEvaluable) subtract(() -> value, () -> operand).get();
	}

	@Deprecated
	default VectorProducer subtract(Supplier<Evaluable<? extends Vector>> value, Supplier<Evaluable<? extends Vector>> operand) {
		return new VectorSum(value, minus(operand));
	}

	@Deprecated
	default VectorEvaluable multiply(Evaluable<Vector> a, Evaluable<Vector> b) { return (VectorEvaluable) multiply(() -> a, () -> b).get(); }

	@Deprecated
	default VectorExpressionComputation multiply(Supplier<Evaluable<? extends Vector>>... values) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(0)).toArray(Expression[]::new)));
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(1)).toArray(Expression[]::new)));
		comp.add(args -> new Product(IntStream.range(0, values.length).mapToObj(i -> args.get(i + 1).getValue(2)).toArray(Expression[]::new)));
		return new VectorExpressionComputation(comp, (Supplier[]) values);
	}

	default VectorEvaluable scalarMultiply(Evaluable<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorExpressionComputation scalarMultiply(Supplier<Evaluable<? extends Vector>> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorEvaluable scalarMultiply(Evaluable<Vector> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b).get());
	}

	default VectorExpressionComputation scalarMultiply(Supplier<Evaluable<? extends Vector>> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b));
	}

	default VectorEvaluable scalarMultiply(Evaluable<Vector> a, Evaluable<Scalar> b) {
		return (VectorEvaluable) scalarMultiply(() -> a, () -> b).get();
	}

	default VectorExpressionComputation scalarMultiply(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return multiply(a, fromScalars(b, b, b));
	}

	default VectorEvaluable minus(Evaluable<Vector> p) {
		return (VectorEvaluable) minus(() -> p).get();
	}

	default VectorExpressionComputation minus(Supplier<Evaluable<? extends Vector>> p) {
		return multiply(p, fromScalars(ScalarFeatures.minusOne(),
				ScalarFeatures.minusOne(),
				ScalarFeatures.minusOne()));
	}

	default ScalarEvaluable length(Evaluable<Vector> v) {
		return (ScalarEvaluable) length(() -> v).get();
	}

	default ScalarProducerBase length(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two)).pow(half);
	}

	default ScalarEvaluable lengthSq(Evaluable<Vector> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two));
	}

	default ScalarProducerBase lengthSq(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two));
	}

	default VectorEvaluable normalize(Evaluable<Vector> p) {
		return (VectorEvaluable) normalize(() -> p).get();
	}

	default VectorExpressionComputation normalize(Supplier<Evaluable<? extends Vector>> p) {
		ScalarProducerBase oneOverLength = length(p).pow(ScalarFeatures.minusOne());
		return fromScalars(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	default VectorEvaluable fromScalars(Evaluable<Scalar> x, Evaluable<Scalar> y, Evaluable<Scalar> z) {
		return (VectorEvaluable) fromScalars(() -> x, () -> y, () -> z).get();
	}

	default VectorExpressionComputation fromScalars(Supplier<Evaluable<? extends Scalar>> x,
													Supplier<Evaluable<? extends Scalar>> y,
													Supplier<Evaluable<? extends Scalar>> z) {
		List<Function<List<MultiExpression<Double>>, Expression<Double>>> comp = new ArrayList<>();
		IntStream.range(0, 3).forEach(i -> comp.add(args -> args.get(1 + i).getValue(0)));
		return new VectorExpressionComputation(comp, (Supplier) x, (Supplier) y, (Supplier) z);
	}

	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
