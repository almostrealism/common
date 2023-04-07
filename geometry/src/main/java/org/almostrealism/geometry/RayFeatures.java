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

package org.almostrealism.geometry;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.VectorProducerBase;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.algebra.computations.VectorExpressionComputation;
import org.almostrealism.geometry.computations.StaticRayComputation;
import io.almostrealism.relation.Evaluable;

import java.util.List;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public interface RayFeatures extends VectorFeatures {

	default RayProducer v(Ray value) { return value(value); }

	default RayProducer value(Ray value) {
		return new StaticRayComputation(value);
	}

	default RayProducer ray(double x, double y, double z, double dx, double dy, double dz) {
		return value(new Ray(new Vector(x, y, z), new Vector(dx, dy, dz)));
	}

	default RayProducer ray(IntFunction<Double> values) {
		return ray(values.apply(0), values.apply(1), values.apply(2),
				values.apply(3), values.apply(4), values.apply(5));
	}

	default VectorEvaluable origin(Evaluable<Ray> r) {
		return (VectorEvaluable) origin(() -> r).get();
	}

	default VectorProducerBase origin(Supplier<Evaluable<? extends Ray>> r) {
		return new VectorExpressionComputation(List.of(
				args -> args.get(1).getValue(0),
				args -> args.get(1).getValue(1),
				args -> args.get(1).getValue(2)),
				(Supplier) r);
	}

	default VectorEvaluable direction(Evaluable<Ray> r) {
		return (VectorEvaluable) direction(() -> r).get();
	}

	default VectorProducerBase direction(Supplier<Evaluable<? extends Ray>> r) {
		return new VectorExpressionComputation(List.of(
				args -> args.get(1).getValue(3),
				args -> args.get(1).getValue(4),
				args -> args.get(1).getValue(5)),
				(Supplier) r);
	}

	default ScalarExpressionComputation oDoto(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(origin(r), origin(r)); }

	default ScalarExpressionComputation dDotd(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(direction(r), direction(r)); }

	default ScalarExpressionComputation oDotd(Supplier<Evaluable<? extends Ray>> r) { return dotProduct(origin(r), direction(r)); }

	static RayFeatures getInstance() {
		return new RayFeatures() { };
	}
}
