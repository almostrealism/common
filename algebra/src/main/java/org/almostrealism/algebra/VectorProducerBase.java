/*
 * Copyright 2022 Michael Murray
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
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.algebra.computations.VectorExpressionComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicExpressionComputation;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface VectorProducerBase extends ProducerComputation<Vector>, KernelizedProducer<Vector>, VectorFeatures {

	default ScalarProducerBase x() {
		return x(this);
	}

	default ScalarProducerBase y() {
		return y(this);
	}

	default ScalarProducerBase z() {
		return z(this);
	}

	default ScalarExpressionComputation dotProduct(Evaluable<Vector> operand) {
		return dotProduct(() -> operand);
	}

	default ScalarExpressionComputation dotProduct(Supplier<Evaluable<? extends Vector>> operand) {
		return dotProduct(this, operand);
	}

	default VectorExpressionComputation crossProduct(Supplier<Evaluable<? extends Vector>> operand) {
		return crossProduct(this, operand);
	}

	default VectorProducerBase add(VectorProducerBase operand) {
		return add(this, operand);
	}

	default VectorProducerBase subtract(VectorProducerBase operand) { return subtract(this, operand); }

	default VectorExpressionComputation multiply(VectorProducerBase operand) {
		return multiply(new VectorProducerBase[] { this, operand });
	}

	default VectorExpressionComputation scalarMultiply(Supplier<Evaluable<? extends Scalar>> operand) { return scalarMultiply(this, operand); }

	default VectorExpressionComputation scalarMultiply(Scalar operand) {
		return scalarMultiply(ScalarFeatures.of(operand));
	}

	default VectorExpressionComputation scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}

	default ScalarProducerBase length() {
		return length(this);
	}

	default ScalarProducerBase lengthSq() {
		return lengthSq(this);
	}

	default VectorExpressionComputation normalize() {
		return normalize(this);
	}
}
