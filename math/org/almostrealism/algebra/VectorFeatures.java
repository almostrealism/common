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
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

/**
 * {@link VectorProducer} is implemented by any class that can produce an {@link Vector} object
 * given some array of input objects.
 *
 * @author  Michael Murray
 */
public interface VectorFeatures {

	default ScalarProducer x(Producer<Vector> v) {
		return new DefaultScalarProducer(new ScalarFromVector(v, ScalarFromVector.X));
	}

	default ScalarProducer y(Producer<Vector> v) {
		return new DefaultScalarProducer(new ScalarFromVector(v, ScalarFromVector.Y));
	}

	default ScalarProducer z(Producer<Vector> v) {
		return new DefaultScalarProducer(new ScalarFromVector(v, ScalarFromVector.Z));
	}

	default ScalarProducer dotProduct(Producer<Vector> a, Producer<Vector> b) { return new DefaultScalarProducer(new DotProduct(a, b)); }

	default VectorProducer crossProduct(Producer<Vector> a, Producer<Vector> b) {
		return new DefaultVectorProducer(new CrossProduct(a, b));
	}

	default VectorProducer add(Producer<Vector> value, Producer<Vector> operand) {
		return new DefaultVectorProducer(new VectorSum(value, operand));
	}

	default VectorProducer subtract(Producer<Vector> value, Producer<Vector> operand) {
		return new DefaultVectorProducer(new VectorSum(value, minus(operand)));
	}

	default VectorProducer multiply(Producer<Vector> a, Producer<Vector> b) { return new DefaultVectorProducer(new VectorProduct(a, b)); }

	default VectorProducer scalarMultiply(Producer<Vector> a, double b) {
		return scalarMultiply(a, new Scalar(b));
	}

	default VectorProducer scalarMultiply(Producer<Vector> a, Scalar b) {
		return scalarMultiply(a, StaticProducer.of(b));
	}

	default VectorProducer scalarMultiply(Producer<Vector> a, Producer<Scalar> b) {
		return multiply(a, fromScalars(b, b, b));
	}

	default VectorProducer minus(Producer<Vector> p) {
		return multiply(p, fromScalars(ScalarProducer.minusOne,
				ScalarProducer.minusOne,
				ScalarProducer.minusOne));
	}

	default ScalarProducer length(Producer<Vector> v) {
		return x(v).pow(2.0).add(y(v).pow(2.0)).add(z(v).pow(2.0)).pow(0.5);
	}

	default ScalarProducer lengthSq(Producer<Vector> v) {
		return x(v).pow(2.0).add(y(v).pow(2.0)).add(z(v).pow(2.0));
	}

	default VectorProducer normalize(Producer<Vector> p) {
		ScalarProducer oneOverLength = length(p).pow(-1.0);
		return fromScalars(x(p).multiply(oneOverLength),
				y(p).multiply(oneOverLength),
				z(p).multiply(oneOverLength));
	}

	default VectorProducer fromScalars(Producer<Scalar> x, Producer<Scalar> y, Producer<Scalar> z) {
		return new DefaultVectorProducer(new VectorFromScalars(x, y, z));
	}
}
