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

package org.almostrealism.geometry;

import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import org.almostrealism.geometry.computations.StaticRayComputation;
import org.almostrealism.geometry.computations.DirectionDotDirection;
import org.almostrealism.geometry.computations.OriginDotDirection;
import org.almostrealism.geometry.computations.OriginDotOrigin;
import org.almostrealism.geometry.computations.RayDirection;
import org.almostrealism.geometry.computations.RayOrigin;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public interface RayFeatures {

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

	default VectorProducer origin(Supplier<Evaluable<? extends Ray>> r) {
		return new RayOrigin(r);
	}

	default VectorEvaluable direction(Evaluable<Ray> r) {
		return (VectorEvaluable) direction(() -> r).get();
	}

	default VectorProducer direction(Supplier<Evaluable<? extends Ray>> r) {
		return new RayDirection(r);
	}

	default ScalarProducer oDoto(Supplier<Evaluable<? extends Ray>> r) { return new OriginDotOrigin(r); }

	default ScalarProducer dDotd(Supplier<Evaluable<? extends Ray>> r) { return new DirectionDotDirection(r); }

	default ScalarProducer oDotd(Supplier<Evaluable<? extends Ray>> r) { return new OriginDotDirection(r); }

	static RayFeatures getInstance() {
		return new RayFeatures() { };
	}
}
