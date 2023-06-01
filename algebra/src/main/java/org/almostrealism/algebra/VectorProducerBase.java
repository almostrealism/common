/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface VectorProducerBase extends ProducerComputation<Vector>, KernelizedProducer<Vector>, VectorFeatures {

	default ExpressionComputation<Scalar> x() {
		return x(this);
	}

	default ExpressionComputation<Scalar> y() {
		return y(this);
	}

	default ExpressionComputation<Scalar> z() {
		return z(this);
	}

	default ExpressionComputation<Scalar> dotProduct(Evaluable<Vector> operand) {
		return dotProduct(() -> operand);
	}

	default ExpressionComputation<Scalar> dotProduct(Supplier<Evaluable<? extends Vector>> operand) {
		return dotProduct(this, operand);
	}

	default ExpressionComputation<Vector> crossProduct(Supplier<Evaluable<? extends Vector>> operand) {
		return crossProduct(this, operand);
	}

	default ExpressionComputation<Vector> add(VectorProducerBase operand) {
		return add(this, operand);
	}

	default ExpressionComputation<Vector> subtract(VectorProducerBase operand) { return subtract(this, operand); }

	default ExpressionComputation<Vector> multiply(VectorProducerBase operand) {
		return multiply(new VectorProducerBase[] { this, operand });
	}

	default ExpressionComputation<Vector> scalarMultiply(Supplier<Evaluable<? extends Scalar>> operand) { return scalarMultiply(this, operand); }

	default ExpressionComputation<Vector> scalarMultiply(Scalar operand) {
		return scalarMultiply(ScalarFeatures.of(operand));
	}

	default ExpressionComputation<Vector> scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}

	default ExpressionComputation<Scalar> length() {
		return length(this);
	}

	default ExpressionComputation<Scalar> lengthSq() {
		return lengthSq(this);
	}

	default ExpressionComputation<Vector> normalize() {
		return normalize(this);
	}
}
