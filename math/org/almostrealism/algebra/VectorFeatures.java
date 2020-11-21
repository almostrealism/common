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

import org.almostrealism.algebra.computations.CrossProduct;
import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.algebra.computations.DotProduct;
import org.almostrealism.algebra.computations.ScalarFromVector;
import org.almostrealism.algebra.computations.VectorFromScalars;
import org.almostrealism.algebra.computations.VectorProduct;
import org.almostrealism.algebra.computations.VectorSum;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Producer;
import org.almostrealism.util.Provider;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

/**
 * {@link VectorProducer} is implemented by any class that can produce an {@link Vector} object
 * given some array of input objects.
 *
 * @author  Michael Murray
 */
public interface VectorFeatures {
	default ScalarProducer x(Producer<Vector> v) {
		return new DefaultScalarProducer(x(() -> v));
	}

	default ScalarSupplier x(Supplier<Producer<? extends Vector>> v) {
		return new ScalarFromVector(v, ScalarFromVector.X);
	}

	default ScalarProducer y(Producer<Vector> v) {
		return new DefaultScalarProducer(y(() -> v));
	}

	default ScalarSupplier y(Supplier<Producer<? extends Vector>> v) {
		return new ScalarFromVector(v, ScalarFromVector.Y);
	}

	default ScalarProducer z(Producer<Vector> v) {
		return new DefaultScalarProducer(z(() -> v));
	}

	default ScalarSupplier z(Supplier<Producer<? extends Vector>> v) {
		return new ScalarFromVector(v, ScalarFromVector.Z);
	}

	default ScalarProducer dotProduct(Producer<Vector> a, Producer<Vector> b) {
		return new DefaultScalarProducer(dotProduct(() -> a, () -> b));
	}

	default ScalarSupplier dotProduct(Supplier<Producer<? extends Vector>> a, Supplier<Producer<? extends Vector>> b) {
		return new DotProduct(a, b);
	}

	default VectorProducer crossProduct(Producer<Vector> a, Producer<Vector> b) {
		return new DefaultVectorProducer(crossProduct(() -> a, () -> b));
	}

	default VectorSupplier crossProduct(Supplier<Producer<? extends Vector>> a, Supplier<Producer<? extends Vector>> b) {
		return new CrossProduct(a, b);
	}

	default VectorProducer add(Producer<Vector> value, Producer<Vector> operand) {
		return new DefaultVectorProducer(add(() -> value, () -> operand));
	}

	default VectorSupplier add(Supplier<Producer<? extends Vector>> value, Supplier<Producer<? extends Vector>> operand) {
		return new VectorSum(value, operand);
	}

	default VectorProducer subtract(Producer<Vector> value, Producer<Vector> operand) {
		return new DefaultVectorProducer(subtract(() -> value, () -> operand));
	}

	default VectorSupplier subtract(Supplier<Producer<? extends Vector>> value, Supplier<Producer<? extends Vector>> operand) {
		return new VectorSum(value, minus(operand));
	}

	default VectorProducer multiply(Producer<Vector> a, Producer<Vector> b) { return new DefaultVectorProducer(multiply(() -> a, () -> b)); }

	default VectorSupplier multiply(Supplier<Producer<? extends Vector>> a, Supplier<Producer<? extends Vector>> b) {
		return new VectorProduct(a, b);
	}

	default VectorProducer scalarMultiply(Producer<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorProducer scalarMultiply(Producer<Vector> a, Scalar b) {
		return scalarMultiply(a, StaticProducer.of(b));
	}

	default VectorProducer scalarMultiply(Producer<Vector> a, Producer<Scalar> b) {
		return new DefaultVectorProducer(scalarMultiply(() -> a, () -> b));
	}

	default VectorSupplier scalarMultiply(Supplier<Producer<? extends Vector>> a, Supplier<Producer<? extends Scalar>> b) {
		return multiply(a, fromScalars(b, b, b));
	}

	default VectorProducer minus(Producer<Vector> p) {
		return new DefaultVectorProducer(minus(() -> p));
	}

	default VectorSupplier minus(Supplier<Producer<? extends Vector>> p) {
		return multiply(p, fromScalars(ScalarProducer.minusOne,
				ScalarProducer.minusOne,
				ScalarProducer.minusOne));
	}

	default ScalarProducer length(Producer<Vector> v) {
		return new DefaultScalarProducer(length(() -> v));
	}

	default ScalarSupplier length(Supplier<Producer<? extends Vector>> v) {
		return x(v).pow(2.0).add(y(v).pow(2.0)).add(z(v).pow(2.0)).pow(0.5);
	}

	default ScalarProducer lengthSq(Producer<Vector> v) {
		return x(v).pow(2.0).add(y(v).pow(2.0)).add(z(v).pow(2.0));
	}

	default ScalarSupplier lengthSq(Supplier<Producer<? extends Vector>> v) {
		return x(v).pow(2.0).add(y(v).pow(2.0)).add(z(v).pow(2.0));
	}

	default VectorProducer normalize(Producer<Vector> p) {
		return new DefaultVectorProducer(normalize(() -> p));
	}

	default VectorSupplier normalize(Supplier<Producer<? extends Vector>> p) {
		ScalarSupplier oneOverLength = length(p).pow(-1.0);
		return fromScalars(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	default VectorProducer fromScalars(Producer<Scalar> x, Producer<Scalar> y, Producer<Scalar> z) {
		return new DefaultVectorProducer(fromScalars(() -> x, () -> y, () -> z));
	}

	default VectorSupplier fromScalars(Supplier<Producer<? extends Scalar>> x, Supplier<Producer<? extends Scalar>> y, Supplier<Producer<? extends Scalar>> z) {
		return new VectorFromScalars(x, y, z);
	}
}
