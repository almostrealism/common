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
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Producer;
import org.almostrealism.util.Provider;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

/**
 * {@link RGBProducer} is implemented by any class that can produce an {@link RGB} object
 * given some array of input objects.
 *
 * @author Michael Murray
 */
public interface RGBProducer extends Producer<RGB>, RGBFeatures {
	default RGBProducer add(Producer<RGB> operand) {
		return cadd(this, operand);
	}

	default RGBProducer subtract(Producer<RGB> operand) { return csubtract(this, operand); }

	default RGBProducer multiply(Producer<RGB> operand) {
		return cmultiply(this, operand);
	}

	default RGBProducer scalarMultiply(Producer<Scalar> operand) { return cscalarMultiply(this, operand); }

	default RGBProducer scalarMultiply(Scalar operand) {
		return scalarMultiply(StaticProducer.of(operand));
	}

	default RGBProducer scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}
	
	default RGBProducer minus() {
		return cminus(this);
	}
}
