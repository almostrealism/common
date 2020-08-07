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
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.RGBProducer;

public class StaticProducer<T> implements Producer<T> {
	private T value;

	public StaticProducer(T v) {
		value = v;
	}

	@Override
	public T evaluate(Object[] args) { return value; }

	/** Does nothing. */
	@Override
	public void compact() { }

	/**
	 * Returns true.
	 */
	public boolean isStatic() { return true; }

	public static <V> Producer<V> of(V value) {
		if (value instanceof Scalar) {
			return (Producer<V>) new AcceleratedStaticScalarProducer((Scalar) value, Scalar.blank());
		} else if (value instanceof Pair) {
			return (Producer<V>) new AcceleratedStaticPairProducer((Pair) value, Pair.empty());
		} else if (value instanceof Vector) {
			return (Producer<V>) new AcceleratedStaticVectorProducer((Vector) value, Vector.blank());
		} else if (value instanceof TransformMatrix) {
			return (Producer<V>) new AcceleratedStaticTransformMatrixProducer((TransformMatrix) value, TransformMatrix.blank());
		} else if (value == null) {
			return null;
		} else {
			return new StaticProducer<>(value);
		}
	}

	public static ScalarProducer of(double value) { return of(new Scalar(value)); }

	public static ScalarProducer of(Scalar value) {
		return new AcceleratedStaticScalarProducer(value, Scalar.blank());
	}

	public static PairProducer of(Pair value) {
		return new AcceleratedStaticPairProducer(value, Pair.empty());
	}

	public static VectorProducer of(Vector value) {
		return new AcceleratedStaticVectorProducer(value, Vector.blank());
	}

	public static RGBProducer of(RGB value) { return new AcceleratedStaticRGBProducer(value, RGB.blank()); }
}
