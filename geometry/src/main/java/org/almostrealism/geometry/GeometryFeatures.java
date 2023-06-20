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

package org.almostrealism.geometry;

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ArrayVariable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionProducerComputationBase;
import org.almostrealism.collect.computations.ExpressionComputation;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

public interface GeometryFeatures extends ScalarFeatures, RayFeatures {
	double PI = Math.PI;
	double TWO_PI = 2 * PI;

	default ExpressionComputation _sin(Supplier<Evaluable<? extends PackedCollection<?>>> input) {
		Function<List<ArrayVariable<Double>>, Expression<Double>> exp = args -> new io.almostrealism.expression.Sine(args.get(1).getValueRelative(0));
		return new ExpressionComputation(List.of(exp), input);
	}

	default CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> _sinw(Producer<PackedCollection<?>> input,
																							  Producer<PackedCollection<?>> wavelength,
																							  Producer<PackedCollection<?>> amp) {
		return _sin(c(TWO_PI).multiply(input).divide(wavelength)).multiply(amp);
	}

	default CollectionProducerComputationBase<PackedCollection<?>, PackedCollection<?>> _sinw(Producer<PackedCollection<?>> input,
																							  Producer<PackedCollection<?>> wavelength,
																							  Producer<PackedCollection<?>> phase,
																							  Producer<PackedCollection<?>> amp) {
		return _sin(c(TWO_PI).multiply(divide(input, wavelength).subtract(phase))).multiply(amp);
	}

	default ExpressionComputation<Vector> reflect(Producer<Vector> vector, Producer<Vector> normal) {
		Producer<Vector> newVector = minus(vector);
		Producer<Scalar> s = scalar(2).multiply(dotProduct(newVector, normal).divide(lengthSq(normal)));
		return subtract(newVector, scalarMultiply(normal, s));
	}
}
