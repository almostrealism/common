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

package org.almostrealism.color.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarEvaluable;
import org.almostrealism.color.RGB;
import org.almostrealism.util.Evaluable;
import org.almostrealism.util.StaticEvaluable;

import java.util.function.Supplier;

public interface RGBFeatures {
	default RGBEvaluable cadd(Evaluable<RGB> value, Evaluable<RGB> operand) {
		return new DefaultRGBEvaluable(cadd(() -> value, () -> operand));
	}

	default RGBSupplier cadd(Supplier<Evaluable<? extends RGB>> value, Supplier<Evaluable<? extends RGB>> operand) {
		return new ColorSum(value, operand);
	}

	default RGBEvaluable csubtract(Evaluable<RGB> value, Evaluable<RGB> operand) {
		return new DefaultRGBEvaluable(csubtract(() -> value, () -> operand));
	}

	default RGBSupplier csubtract(Supplier<Evaluable<? extends RGB>> value, Supplier<Evaluable<? extends RGB>> operand) {
		return new ColorSum(value, cminus(operand));
	}

	default RGBEvaluable cmultiply(Evaluable<RGB> a, Evaluable<RGB> b) {
		return new DefaultRGBEvaluable(cmultiply(() -> a, () -> b));
	}

	default RGBSupplier cmultiply(Supplier<Evaluable<? extends RGB>> a, Supplier<Evaluable<? extends RGB>> b) {
		return new ColorProduct(a, b);
	}

	default RGBEvaluable cscalarMultiply(Evaluable<RGB> a, Evaluable<Scalar> b) {
		return new DefaultRGBEvaluable(cscalarMultiply(() -> a, () -> b));
	}

	default RGBSupplier cscalarMultiply(Supplier<Evaluable<? extends RGB>> a, Supplier<Evaluable<? extends Scalar>> b) {
		return cmultiply(a, cfromScalar(b));
	}

	default RGBEvaluable cminus(Evaluable<RGB> p) {
		return new DefaultRGBEvaluable(cminus(() -> p));
	}

	default RGBSupplier cminus(Supplier<Evaluable<? extends RGB>> p) {
		return cmultiply(p, cfromScalar(ScalarEvaluable.minusOne));
	}

	default RGBEvaluable cfromScalar(Evaluable<Scalar> value) {
		return new DefaultRGBEvaluable(cfromScalar(() -> value));
	}

	default RGBSupplier cfromScalar(Supplier<Evaluable<? extends Scalar>> value) {
		return new RGBFromScalars(value, value, value);
	}

	default RGBSupplier cfromScalar(Scalar value) {
		return cfromScalar(() -> StaticEvaluable.of(value));
	}

	default RGBSupplier cfromScalar(double value) {
		return cfromScalar(new Scalar(value));
	}
}
