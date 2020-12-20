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

package org.almostrealism.util;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayProducer;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface StaticEvaluable<T> extends Evaluable<T>, Supplier<T> {
	@Override
	default T evaluate(Object[] args) { return get(); }

	static ScalarProducer of(double value) { return of(new Scalar(value)); }

	static ScalarProducer of(Scalar value) {
		return new AcceleratedStaticScalarComputation(value, Scalar.blank());
	}

	static PairProducer of(Pair value) {
		return new AcceleratedStaticPairComputation(value, Pair.empty());
	}

	static VectorProducer of(Vector value) {
		return new AcceleratedStaticVectorComputation(value, Vector.blank());
	}

	static RayProducer of(Ray value) {
		return new AcceleratedStaticRayComputation(value, Ray.blank());
	}

	static RGBProducer of(RGB value) {
		return new AcceleratedStaticRGBComputation(value, RGB.blank());
	}
}
