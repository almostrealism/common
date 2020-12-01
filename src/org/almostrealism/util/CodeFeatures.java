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
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.RGBFeatures;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.RayProducer;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleDataFeatures;
import org.almostrealism.graph.mesh.TrianglePointData;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.Evaluable;
import org.almostrealism.relation.Producer;
import org.almostrealism.relation.ProducerComputation;
import org.almostrealism.time.CursorPair;

import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

public interface CodeFeatures extends ScalarFeatures, PairFeatures, TriangleDataFeatures, RayFeatures, RGBFeatures {
	default <T> Producer<T> p(T value) { return () -> new Provider<>(value); }

	default ScalarProducer v(double value) { return value(new Scalar(value)); }

	default ScalarProducer v(Scalar value) { return value(value); }

	default PairProducer v(Pair value) { return value(value); }

	default Producer<CursorPair> v(CursorPair p) {
		throw new UnsupportedOperationException();
	}

	default VectorProducer v(Vector value) { return value(value); }

	default RayProducer v(Ray value) { return value(value); }

	default RGBProducer v(RGB value) { return value(value); }

	default <T> Producer<T> v(T v) {
		return value(v);
	}

	default <T> Producer<T> v(Class<T> type, int argIndex) {
		return value(type, argIndex);
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default <T extends MemWrapper> Supplier<Runnable> a(int memLength, Evaluable<T> result, Evaluable<T> value) {
		return a(memLength, () -> result, () -> value);
	}

	default <T extends MemWrapper> Supplier<Runnable> a(int memLength, Supplier<Evaluable<? extends T>> result, Supplier<Evaluable<? extends T>> value) {
		return new AcceleratedAssignment<>(memLength, result, value);
	}

	default ScalarProducer value(double value) { return scalar(value); }

	default ScalarProducer scalar(double value) { return value(new Scalar(value)); }

	default ScalarProducer value(Scalar value) {
		return new AcceleratedStaticScalarComputation(value, Scalar.blank());
	}

	default ScalarProducer scalar() {
		return Scalar.blank();
	}

	default PairProducer pair(double x, double y) { return value(new Pair(x, y)); }

	default PairProducer value(Pair value) {
		return new AcceleratedStaticPairComputation(value, Pair.empty());
	}

	default Supplier<Evaluable<? extends Vector>> vector(int argIndex) { return value(Vector.class, argIndex); }

	default VectorProducer vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default VectorProducer vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default VectorProducer vector(IntFunction<Double> values) {
		return vector(values.apply(0), values.apply(1), values.apply(2));
	}

	default Producer<Vector> vector() { return Vector.blank(); }

	default VectorProducer value(Vector value) {
		return new AcceleratedStaticVectorComputation(value, Vector.blank());
	}

	default RayProducer ray(double x, double y, double z, double dx, double dy, double dz) {
		return value(new Ray(new Vector(x, y, z), new Vector(dx, dy, dz)));
	}

	default RayProducer ray(IntFunction<Double> values) {
		return ray(values.apply(0), values.apply(1), values.apply(2),
					values.apply(3), values.apply(4), values.apply(5));
	}

	default RayProducer value(Ray value) {
		return new AcceleratedStaticRayComputation(value, Ray.blank());
	}

	default Supplier<Evaluable<? extends TriangleData>> triangle(int argIndex) { return value(TriangleData.class, argIndex); }

	default Supplier<Evaluable<? extends TrianglePointData>> points(int argIndex) { return value(TrianglePointData.class, argIndex); }

	default RGBProducer rgb(double r, double g, double b) { return value(new RGB(r, g, b)); }

	default RGBProducer rgb(Scalar v) { return cfromScalar(v); }

	default RGBProducer rgb(double v) { return cfromScalar(v); }

	default RGBProducer value(RGB value) {
		return new AcceleratedStaticRGBComputation(value, RGB.blank());
	}

	default <T> Producer<T> value(T v) {
		if (v instanceof Scalar) {
			return (ProducerComputation<T>) new AcceleratedStaticScalarComputation((Scalar) v, Scalar.blank());
		} else if (v instanceof Pair) {
			return (ProducerComputation<T>) new AcceleratedStaticPairComputation((Pair) v, Pair.empty());
		} else if (v instanceof Vector) {
			return (ProducerComputation<T>) new AcceleratedStaticVectorComputation((Vector) v, Vector.blank());
		} else if (v instanceof Ray) {
			return (ProducerComputation<T>) new AcceleratedStaticRayComputation((Ray) v, Ray.blank());
		} else if (v instanceof TransformMatrix) {
			return (ProducerComputation<T>) new AcceleratedStaticTransformMatrixComputation((TransformMatrix) v, TransformMatrix.blank());
		} else if (v == null) {
			return null;
		} else {
			return () -> new Provider<>(v);
		}
	}

	default <T> Producer<T> value(Class<T> type, int argIndex) {
		return Input.value(type, argIndex);
	}

	default Ops o() { return Ops.ops(); }
}
