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
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.computations.DefaultPairProducer;
import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.DefaultRGBProducer;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.geometry.DefaultRayProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayProducer;

import java.util.function.Supplier;

public interface StaticProducer<T> extends Producer<T>, Supplier<T> {
	@Override
	default T evaluate(Object[] args) { return get(); }

	/** Does nothing. */
	@Override
	default void compact() { }

	/**
	 * Returns true.
	 */
	@Override
	default boolean isStatic() { return true; }

	static <V> Producer<V> of(V value) {
		if (value instanceof Scalar) {
			return (Producer<V>) new DefaultScalarProducer(new AcceleratedStaticScalarComputation((Scalar) value, Scalar.blank()));
		} else if (value instanceof Pair) {
			return (Producer<V>) new DefaultPairProducer(new AcceleratedStaticPairComputation((Pair) value, Pair.empty()));
		} else if (value instanceof Vector) {
			return (Producer<V>) new DefaultVectorProducer(new AcceleratedStaticVectorComputation((Vector) value, Vector.blank()));
		} else if (value instanceof Ray) {
			return (Producer<V>) new DefaultRayProducer(new AcceleratedStaticRayComputation((Ray) value, Ray.blank()));
		} else if (value instanceof TransformMatrix) {
			return (Producer<V>) new DefaultTransformMatrixProducer(new AcceleratedStaticTransformMatrixComputation((TransformMatrix) value, TransformMatrix.blank()));
		} else if (value == null) {
			return null;
		} else {
			return new Provider<>(value);
		}
	}

	static ScalarProducer of(double value) { return of(new Scalar(value)); }

	static ScalarProducer of(Scalar value) {
		return new DefaultScalarProducer(new AcceleratedStaticScalarComputation(value, Scalar.blank()));
	}

	static PairProducer of(Pair value) {
		return new DefaultPairProducer(new AcceleratedStaticPairComputation(value, Pair.empty()));
	}

	static VectorProducer of(Vector value) {
		return new DefaultVectorProducer(new AcceleratedStaticVectorComputation(value, Vector.blank()));
	}

	static RayProducer of(Ray value) {
		return new DefaultRayProducer(new AcceleratedStaticRayComputation(value, Ray.blank()));
	}

	static RGBProducer of(RGB value) {
		return new DefaultRGBProducer(new AcceleratedStaticRGBComputation(value, RGB.blank()));
	}
}
