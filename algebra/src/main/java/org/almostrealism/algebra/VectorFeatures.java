/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;

import org.almostrealism.algebra.computations.StaticVectorComputation;
import org.almostrealism.algebra.computations.CrossProduct;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import org.almostrealism.algebra.computations.DotProduct;
import org.almostrealism.algebra.computations.ScalarFromVector;
import org.almostrealism.algebra.computations.VectorFromScalars;
import org.almostrealism.algebra.computations.VectorProduct;
import org.almostrealism.algebra.computations.VectorSum;

import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * {@link VectorEvaluable} is implemented by any class that can produce an {@link Vector} object
 * given some array of input objects.
 *
 * @author  Michael Murray
 */
public interface VectorFeatures {
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

	default Producer<Vector> vector() { return Vector.blank(); }

	default ScalarEvaluable x(Evaluable<Vector> v) {
		return new DefaultScalarEvaluable(x(() -> v));
	}

	default ScalarProducer x(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarFromVector(v, ScalarFromVector.X);
	}

	default ScalarEvaluable y(Evaluable<Vector> v) {
		return new DefaultScalarEvaluable(y(() -> v));
	}

	default ScalarProducer y(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarFromVector(v, ScalarFromVector.Y);
	}

	default ScalarEvaluable z(Evaluable<Vector> v) {
		return new DefaultScalarEvaluable(z(() -> v));
	}

	default ScalarProducer z(Supplier<Evaluable<? extends Vector>> v) {
		return new ScalarFromVector(v, ScalarFromVector.Z);
	}

	default ScalarEvaluable dotProduct(Evaluable<Vector> a, Evaluable<Vector> b) {
		return new DefaultScalarEvaluable(dotProduct(() -> a, () -> b));
	}

	default ScalarProducer dotProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		return new DotProduct(a, b);
	}

	default VectorEvaluable crossProduct(Evaluable<Vector> a, Evaluable<Vector> b) {
		return new DefaultVectorEvaluable(crossProduct(() -> a, () -> b));
	}

	default VectorProducer crossProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		return new CrossProduct(a, b);
	}

	default VectorEvaluable add(Evaluable<Vector> value, Evaluable<Vector> operand) {
		return new DefaultVectorEvaluable(add(() -> value, () -> operand));
	}

	default VectorProducer add(Supplier<Evaluable<? extends Vector>> value, Supplier<Evaluable<? extends Vector>> operand) {
		return new VectorSum(value, operand);
	}

	default VectorEvaluable subtract(Evaluable<Vector> value, Evaluable<Vector> operand) {
		return new DefaultVectorEvaluable(subtract(() -> value, () -> operand));
	}

	default VectorProducer subtract(Supplier<Evaluable<? extends Vector>> value, Supplier<Evaluable<? extends Vector>> operand) {
		return new VectorSum(value, minus(operand));
	}

	default VectorEvaluable multiply(Evaluable<Vector> a, Evaluable<Vector> b) { return new DefaultVectorEvaluable(multiply(() -> a, () -> b)); }

	default VectorProducer multiply(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		return new VectorProduct(a, b);
	}

	default VectorEvaluable scalarMultiply(Evaluable<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorProducer scalarMultiply(Supplier<Evaluable<? extends Vector>> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorEvaluable scalarMultiply(Evaluable<Vector> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b).get());
	}

	default VectorProducer scalarMultiply(Supplier<Evaluable<? extends Vector>> a, Scalar b) {
		return scalarMultiply(a, ScalarFeatures.of(b));
	}

	default VectorEvaluable scalarMultiply(Evaluable<Vector> a, Evaluable<Scalar> b) {
		return new DefaultVectorEvaluable(scalarMultiply(() -> a, () -> b));
	}

	default VectorProducer scalarMultiply(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return multiply(a, fromScalars(b, b, b));
	}

	default VectorEvaluable minus(Evaluable<Vector> p) {
		return new DefaultVectorEvaluable(minus(() -> p));
	}

	default VectorProducer minus(Supplier<Evaluable<? extends Vector>> p) {
		return multiply(p, fromScalars(ScalarEvaluable.minusOne,
				ScalarEvaluable.minusOne,
				ScalarEvaluable.minusOne));
	}

	default ScalarEvaluable length(Evaluable<Vector> v) {
		return new DefaultScalarEvaluable(length(() -> v));
	}

	default ScalarProducer length(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two)).pow(half);
	}

	default ScalarEvaluable lengthSq(Evaluable<Vector> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two));
	}

	default ScalarProducer lengthSq(Supplier<Evaluable<? extends Vector>> v) {
		return x(v).pow(two).add(y(v).pow(two)).add(z(v).pow(two));
	}

	default VectorEvaluable normalize(Evaluable<Vector> p) {
		return new DefaultVectorEvaluable(normalize(() -> p));
	}

	default VectorProducer normalize(Supplier<Evaluable<? extends Vector>> p) {
		ScalarProducer oneOverLength = length(p).pow(ScalarFeatures.minusOne);
		return fromScalars(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	default VectorEvaluable fromScalars(Evaluable<Scalar> x, Evaluable<Scalar> y, Evaluable<Scalar> z) {
		return new DefaultVectorEvaluable(fromScalars(() -> x, () -> y, () -> z));
	}

	default VectorProducer fromScalars(Supplier<Evaluable<? extends Scalar>> x, Supplier<Evaluable<? extends Scalar>> y, Supplier<Evaluable<? extends Scalar>> z) {
		return new VectorFromScalars(x, y, z);
	}

	static VectorFeatures getInstance() {
		return new VectorFeatures() { };
	}
}
