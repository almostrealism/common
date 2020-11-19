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
import org.almostrealism.color.RGB;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

public interface RGBSupplier extends ProducerComputation<RGB>, RGBFeatures {
	default RGBSupplier add(Supplier<Producer<? extends RGB>> operand) {
		return cadd(this, operand);
	}

	default RGBSupplier subtract(Supplier<Producer<? extends RGB>> operand) { return csubtract(this, operand); }

	default RGBSupplier multiply(Supplier<Producer<? extends RGB>> operand) {
		return cmultiply(this, operand);
	}

	default RGBSupplier scalarMultiply(Supplier<Producer<? extends Scalar>> operand) { return cscalarMultiply(this, operand); }

	default RGBSupplier scalarMultiply(Scalar operand) {
		return scalarMultiply(() -> StaticProducer.of(operand));
	}

	default RGBSupplier scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}

	default RGBSupplier minus() {
		return cminus(this);
	}
}
