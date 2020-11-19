/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.algebra;

import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

public interface ScalarFeatures {
	ScalarProducer minusOne = StaticProducer.of(-1.0);

	default ScalarProducer scalarAdd(Producer<Scalar> a, Producer<Scalar> b) {
		return new DefaultScalarProducer(scalarAdd(() -> a, () -> b));
	}

	default ScalarSupplier scalarAdd(Supplier<Producer<? extends Scalar>> a, Supplier<Producer<? extends Scalar>> b) {
		return new ScalarSum(a, b);
	}

	default ScalarProducer scalarSubtract(Producer<Scalar> a, Producer<Scalar> b) {
		return new DefaultScalarProducer(scalarSubtract(() -> a, () -> b));
	}

	default ScalarSupplier scalarSubtract(Supplier<Producer<? extends Scalar>> a, Supplier<Producer<? extends Scalar>> b) {
		return new ScalarSum(a, scalarMinus(b));
	}

	default ScalarProducer scalarsMultiply(Producer<Scalar> a, Producer<Scalar> b) {
		return new DefaultScalarProducer(scalarsMultiply(() -> a, () -> b));
	}

	default ScalarSupplier scalarsMultiply(Supplier<Producer<? extends Scalar>> a, Supplier<Producer<? extends Scalar>> b) {
		return new ScalarProduct(a, b);
	}

	default ScalarProducer scalarMinus(Producer<Scalar> v) {
		return new DefaultScalarProducer(scalarMinus(() -> v));
	}

	default ScalarSupplier scalarMinus(Supplier<Producer<? extends Scalar>> v) {
		return new ScalarProduct(() -> minusOne, v);
	}

	default ScalarProducer pow(Producer<Scalar> base, Producer<Scalar> exponent) {
		return new DefaultScalarProducer(pow(() -> base, () -> exponent));
	}

	default ScalarSupplier pow(Supplier<Producer<? extends Scalar>> base, Supplier<Producer<? extends Scalar>> exponent) {
		return new ScalarPow(base, exponent);
	}

	default ScalarProducer pow(Producer<Scalar> base, Scalar exp) {
		return pow(base, StaticProducer.of(exp));
	}

	default ScalarSupplier pow(Supplier<Producer<? extends Scalar>> base, Scalar exp) {
		return pow(base, () -> StaticProducer.of(exp));
	}

	default ScalarProducer pow(Producer<Scalar> base, double value) {
		return pow(base, new Scalar(value));
	}

	default ScalarSupplier pow(Supplier<Producer<? extends Scalar>> base, double value) {
		return pow(base, new Scalar(value));
	}
}
