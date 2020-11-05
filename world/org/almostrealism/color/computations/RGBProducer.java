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

/**
 * {@link RGBProducer} is implemented by any class that can produce an {@link RGB} object
 * given some array of input objects.
 *
 * @author Michael Murray
 */
public interface RGBProducer extends Producer<RGB> {
	default RGBProducer add(Producer<RGB> operand) {
		return add(this, operand);
	}

	static RGBProducer add(Producer<RGB> value, Producer<RGB> operand) {
		return new DefaultRGBProducer(new ColorSum(value, operand));
	}

	default RGBProducer subtract(Producer<RGB> operand) { return subtract(this, operand); }

	static RGBProducer subtract(Producer<RGB> value, Producer<RGB> operand) {
		return new DefaultRGBProducer(new ColorSum(value, minus(operand)));
	}

	default RGBProducer multiply(Producer<RGB> operand) {
		return multiply(this, operand);
	}

	static RGBProducer multiply(Producer<RGB> a, Producer<RGB> b) { return new DefaultRGBProducer(new ColorProduct(a, b)); }

	default RGBProducer scalarMultiply(Producer<Scalar> operand) { return scalarMultiply(this, operand); }

	static RGBProducer scalarMultiply(Producer<RGB> a, Producer<Scalar> b) {
		return multiply(a, fromScalar(b));
	}

	default RGBProducer scalarMultiply(Scalar operand) {
		return scalarMultiply(StaticProducer.of(operand));
	}

	default RGBProducer scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}
	
	default RGBProducer minus() {
		return minus(this);
	}

	static RGBProducer minus(Producer<RGB> p) {
		return multiply(p, fromScalar(ScalarProducer.minusOne));
	}

	static RGBProducer fromScalar(Producer<Scalar> value) {
		return new DefaultRGBProducer(new RGBFromScalars(value, value, value));
	}

	static RGBProducer fromScalar(Scalar value) {
		return fromScalar(StaticProducer.of(value));
	}

	static RGBProducer fromScalar(double value) {
		return StaticProducer.of(new RGB(value, value, value));
	}
}
