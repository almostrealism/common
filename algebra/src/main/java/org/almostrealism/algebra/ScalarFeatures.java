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

import org.almostrealism.algebra.computations.AcceleratedStaticScalarComputation;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface ScalarFeatures {
	Supplier<Evaluable<? extends Scalar>> minusOne = of(-1.0);

	static ScalarProducer of(double value) { return of(new Scalar(value)); }

	static ScalarProducer of(Scalar value) {
		return new AcceleratedStaticScalarComputation(value, Scalar.blank());
	}

	default ScalarProducer v(double value) { return value(new Scalar(value)); }

	default ScalarProducer v(Scalar value) { return value(value); }

	default ScalarProducer scalar(double value) { return value(new Scalar(value)); }

	default ScalarProducer value(Scalar value) {
		return new AcceleratedStaticScalarComputation(value, Scalar.blank());
	}

	default ScalarProducer scalar() {
		return Scalar.blank();
	}

	default ScalarEvaluable scalarAdd(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return new DefaultScalarEvaluable(scalarAdd(() -> a, () -> b));
	}

	default ScalarProducer scalarAdd(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ScalarSum(a, b);
	}

	default ScalarEvaluable scalarSubtract(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return new DefaultScalarEvaluable(scalarSubtract(() -> a, () -> b));
	}

	default ScalarProducer scalarSubtract(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ScalarSum(a, scalarMinus(b));
	}

	default ScalarEvaluable scalarsMultiply(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return new DefaultScalarEvaluable(scalarsMultiply(() -> a, () -> b));
	}

	default ScalarProducer scalarsMultiply(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ScalarProduct(a, b);
	}

	default ScalarEvaluable scalarMinus(Evaluable<Scalar> v) {
		return new DefaultScalarEvaluable(scalarMinus(() -> v));
	}

	default ScalarProducer scalarMinus(Supplier<Evaluable<? extends Scalar>> v) {
		return new ScalarProduct(minusOne, v);
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, Evaluable<Scalar> exponent) {
		return new DefaultScalarEvaluable(pow(() -> base, () -> exponent));
	}

	default ScalarProducer pow(Supplier<Evaluable<? extends Scalar>> base, Supplier<Evaluable<? extends Scalar>> exponent) {
		return new ScalarPow(base, exponent);
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, Scalar exp) {
		return pow(base, of(exp).get());
	}

	default ScalarProducer pow(Supplier<Evaluable<? extends Scalar>> base, Scalar exp) {
		return pow(base, of(exp));
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, double value) {
		return pow(base, new Scalar(value));
	}

	default ScalarProducer pow(Supplier<Evaluable<? extends Scalar>> base, double value) {
		return pow(base, new Scalar(value));
	}

	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
