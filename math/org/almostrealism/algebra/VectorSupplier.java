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

import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

import java.util.function.Supplier;

public interface VectorSupplier extends ProducerComputation<Vector>, VectorFeatures {

	@Override
	default Producer<Vector> get() { return new DefaultVectorProducer(this); }

	default ScalarSupplier x() {
		return x(this);
	}

	default ScalarSupplier y() {
		return y(this);
	}

	default ScalarSupplier z() {
		return z(this);
	}

	default ScalarSupplier dotProduct(Producer<Vector> operand) {
		return dotProduct(() -> operand);
	}

	default ScalarSupplier dotProduct(Supplier<Producer<? extends Vector>> operand) {
		return dotProduct(this, operand);
	}

	default VectorSupplier crossProduct(Supplier<Producer<? extends Vector>> operand) {
		return crossProduct(this, operand);
	}

	default VectorSupplier add(Supplier<Producer<? extends Vector>> operand) {
		return add(this, operand);
	}

	default VectorSupplier subtract(Supplier<Producer<? extends Vector>> operand) { return subtract(this, operand); }

	default VectorSupplier multiply(Supplier<Producer<? extends Vector>> operand) {
		return multiply(this, operand);
	}

	default VectorSupplier scalarMultiply(Supplier<Producer<? extends Scalar>> operand) { return scalarMultiply(this, operand); }

	default VectorSupplier scalarMultiply(Scalar operand) {
		return scalarMultiply(() -> StaticProducer.of(operand));
	}

	default VectorSupplier scalarMultiply(double operand) {
		return scalarMultiply(new Scalar(operand));
	}

	default VectorSupplier minus() {
		return minus(this);
	}

	default ScalarSupplier length() {
		return length(this);
	}

	default ScalarSupplier lengthSq() {
		return lengthSq(this);
	}

	default VectorSupplier normalize() {
		return normalize(this);
	}
}
