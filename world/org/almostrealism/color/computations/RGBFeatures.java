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
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

public interface RGBFeatures {
	default RGBProducer cadd(Producer<RGB> value, Producer<RGB> operand) {
		return new DefaultRGBProducer(cadd(() -> value, () -> operand));
	}

	default RGBSupplier cadd(Supplier<Producer<? extends RGB>> value, Supplier<Producer<? extends RGB>> operand) {
		return new ColorSum(value, operand);
	}

	default RGBProducer csubtract(Producer<RGB> value, Producer<RGB> operand) {
		return new DefaultRGBProducer(csubtract(() -> value, () -> operand));
	}

	default RGBSupplier csubtract(Supplier<Producer<? extends RGB>> value, Supplier<Producer<? extends RGB>> operand) {
		return new ColorSum(value, cminus(operand));
	}

	default RGBProducer cmultiply(Producer<RGB> a, Producer<RGB> b) {
		return new DefaultRGBProducer(cmultiply(() -> a, () -> b));
	}

	default RGBSupplier cmultiply(Supplier<Producer<? extends RGB>> a, Supplier<Producer<? extends RGB>> b) {
		return new ColorProduct(a, b);
	}

	default RGBProducer cscalarMultiply(Producer<RGB> a, Producer<Scalar> b) {
		return new DefaultRGBProducer(cscalarMultiply(() -> a, () -> b));
	}

	default RGBSupplier cscalarMultiply(Supplier<Producer<? extends RGB>> a, Supplier<Producer<? extends Scalar>> b) {
		return cmultiply(a, cfromScalar(b));
	}

	default RGBProducer cminus(Producer<RGB> p) {
		return new DefaultRGBProducer(cminus(() -> p));
	}

	default RGBSupplier cminus(Supplier<Producer<? extends RGB>> p) {
		return cmultiply(p, cfromScalar(ScalarProducer.minusOne));
	}

	default RGBProducer cfromScalar(Producer<Scalar> value) {
		return new DefaultRGBProducer(cfromScalar(() -> value));
	}

	default RGBSupplier cfromScalar(Supplier<Producer<? extends Scalar>> value) {
		return new RGBFromScalars(value, value, value);
	}

	default RGBSupplier cfromScalar(Scalar value) {
		return cfromScalar(() -> StaticProducer.of(value));
	}

	default RGBSupplier cfromScalar(double value) {
		return cfromScalar(new Scalar(value));
	}
}
