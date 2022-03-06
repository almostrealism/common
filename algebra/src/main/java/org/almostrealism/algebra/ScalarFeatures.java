/*
 * Copyright 2021 Michael Murray
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

import org.almostrealism.algebra.computations.Floor;
import org.almostrealism.algebra.computations.Max;
import org.almostrealism.algebra.computations.Min;
import org.almostrealism.algebra.computations.ScalarFromScalarBank;
import org.almostrealism.algebra.computations.StaticScalarComputation;
import org.almostrealism.algebra.computations.ScalarPow;
import org.almostrealism.algebra.computations.ScalarProduct;
import org.almostrealism.algebra.computations.ScalarSum;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Supplier;

public interface ScalarFeatures {

	static Supplier<Evaluable<? extends Scalar>> minusOne() { return of(-1.0); }

	static ScalarProducer of(double value) { return of(new Scalar(value)); }

	static ScalarProducer of(Scalar value) {
		return new StaticScalarComputation(value);
	}

	default ScalarProducer v(double value) { return value(new Scalar(value)); }

	default ScalarProducer v(Scalar value) { return value(value); }

	default ScalarProducer scalar(double value) { return value(new Scalar(value)); }

	default ScalarProducer value(Scalar value) {
		return new StaticScalarComputation(value);
	}

	default ScalarProducer scalar(Supplier<Evaluable<? extends MemoryBank<Scalar>>> bank, int index) {
		return new ScalarFromScalarBank(bank, scalar((double) index));
	}

	default ScalarProducer scalar() {
		return Scalar.blank();
	}

	default ScalarEvaluable scalarAdd(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return (ScalarEvaluable) scalarAdd(() -> a, () -> b).get();
	}

	default ScalarProducer scalarAdd(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ScalarSum(a, b);
	}

	default ScalarEvaluable scalarSubtract(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return (ScalarEvaluable) scalarSubtract(() -> a, () -> b).get();
	}

	default ScalarProducer scalarSubtract(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ScalarSum(a, scalarMinus(b));
	}

	default ScalarEvaluable scalarsMultiply(Evaluable<Scalar> a, Evaluable<Scalar> b) {
		return (ScalarEvaluable) scalarsMultiply(() -> a, () -> b).get();
	}

	default ScalarProducer scalarsMultiply(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new ScalarProduct(a, b);
	}

	default ScalarProducer scalarsDivide(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return scalarsMultiply(a, pow(b, v(-1.0)));
	}

	default ScalarEvaluable scalarMinus(Evaluable<Scalar> v) {
		return (ScalarEvaluable) scalarMinus(() -> v).get();
	}

	default ScalarProducer scalarMinus(Supplier<Evaluable<? extends Scalar>> v) {
		return new ScalarProduct(ScalarFeatures.minusOne(), v);
	}

	default ScalarEvaluable pow(Evaluable<Scalar> base, Evaluable<Scalar> exponent) {
		return (ScalarEvaluable) pow(() -> base, () -> exponent).get();
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

	default ScalarProducer floor(Supplier<Evaluable<? extends Scalar>> value) {
		return new Floor(value);
	}

	default ScalarProducer min(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new Min(a, b);
	}

	default ScalarProducer max(Supplier<Evaluable<? extends Scalar>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return new Max(a, b);
	}

	default ScalarProducer bound(Supplier<Evaluable<? extends Scalar>> a, double min, double max) {
		return min(max(a, v(min)), v(max));
	}

	static ScalarFeatures getInstance() { return new ScalarFeatures() { }; }
}
