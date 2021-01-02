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

import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface VectorProducer extends ProducerComputation<Vector>, KernelizedProducer<Vector>, VectorFeatures {

	@Override
	default KernelizedEvaluable<Vector> get() { return new DefaultVectorEvaluable(this); }

	default ScalarProducer x() {
		return x(this);
	}

	default ScalarProducer y() {
		return y(this);
	}

	default ScalarProducer z() {
		return z(this);
	}

	default ScalarProducer dotProduct(Evaluable<Vector> operand) {
		return dotProduct(() -> operand);
	}

	default ScalarProducer dotProduct(Supplier<Evaluable<? extends Vector>> operand) {
		return dotProduct(this, operand);
	}

	default VectorProducer crossProduct(Supplier<Evaluable<? extends Vector>> operand) {
		return crossProduct(this, operand);
	}

	default VectorProducer add(Supplier<Evaluable<? extends Vector>> operand) {
		return add(this, operand);
	}

	default VectorProducer subtract(Supplier<Evaluable<? extends Vector>> operand) { return subtract(this, operand); }

	default VectorProducer multiply(Supplier<Evaluable<? extends Vector>> operand) {
		return multiply(this, operand);
	}

	default VectorProducer scalarMultiply(Supplier<Evaluable<? extends Scalar>> operand) { return scalarMultiply(this, operand); }

	default VectorProducer scalarMultiply(Scalar operand) {
		return scalarMultiply(ScalarFeatures.of(operand));
	}

	default VectorProducer scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}

	default VectorProducer minus() {
		return minus(this);
	}

	default ScalarProducer length() {
		return length(this);
	}

	default ScalarProducer lengthSq() {
		return lengthSq(this);
	}

	default VectorProducer normalize() {
		return normalize(this);
	}
}
