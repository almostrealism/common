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
import org.almostrealism.algebra.PairEvaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarEvaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.algebra.computations.DefaultPairEvaluable;
import org.almostrealism.algebra.computations.DefaultScalarEvaluable;
import org.almostrealism.algebra.computations.DefaultVectorEvaluable;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.DefaultRGBEvaluable;
import org.almostrealism.color.computations.RGBEvaluable;
import org.almostrealism.geometry.DefaultRayEvaluable;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayEvaluable;
import org.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

public interface StaticEvaluable<T> extends Evaluable<T>, Supplier<T> {
	@Override
	default T evaluate(Object[] args) { return get(); }

	static ScalarEvaluable of(double value) { return of(new Scalar(value)); }

	static ScalarEvaluable of(Scalar value) {
		return new DefaultScalarEvaluable(new AcceleratedStaticScalarComputation(value, Scalar.blank()));
	}

	static PairEvaluable of(Pair value) {
		return new DefaultPairEvaluable(new AcceleratedStaticPairComputation(value, Pair.empty()));
	}

	static VectorEvaluable of(Vector value) {
		return new DefaultVectorEvaluable(new AcceleratedStaticVectorComputation(value, Vector.blank()));
	}

	static RayEvaluable of(Ray value) {
		return new DefaultRayEvaluable(new AcceleratedStaticRayComputation(value, Ray.blank()));
	}

	static RGBEvaluable of(RGB value) {
		return new DefaultRGBEvaluable(new AcceleratedStaticRGBComputation(value, RGB.blank()));
	}
}
