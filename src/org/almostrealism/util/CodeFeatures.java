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
import org.almostrealism.algebra.PairSupplier;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.ScalarSupplier;
import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.algebra.VectorSupplier;
import org.almostrealism.algebra.computations.DefaultPairProducer;
import org.almostrealism.algebra.computations.DefaultScalarProducer;
import org.almostrealism.algebra.computations.DefaultVectorProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.DefaultRGBProducer;
import org.almostrealism.color.computations.RGBFeatures;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.color.computations.RGBSupplier;
import org.almostrealism.geometry.DefaultRayProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.RayProducer;
import org.almostrealism.geometry.RaySupplier;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleDataFeatures;
import org.almostrealism.graph.mesh.TrianglePointData;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.relation.Maker;
import org.almostrealism.relation.ProducerComputation;

import java.util.function.Function;
import java.util.function.Supplier;

public interface CodeFeatures extends ScalarFeatures, PairFeatures, TriangleDataFeatures, RayFeatures, RGBFeatures {
	default <T> Maker<T> p(T value) { return () -> new Provider<>(value); }

	default ScalarSupplier v(double value) { return value(new Scalar(value)); }

	default ScalarSupplier v(Scalar value) { return value(value); }

	default PairSupplier v(Pair value) { return value(value); }

	default VectorSupplier v(Vector value) { return value(value); }

	default RaySupplier v(Ray value) { return value(value); }

	default RGBSupplier v(RGB value) { return value(value); }

	default <T> Maker<T> v(T v) {
		return value(v);
	}

	default <T> Supplier<Producer<? extends T>> v(Class<T> type, int argIndex) {
		return value(type, argIndex);
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default <T extends MemWrapper> Runnable a(int memLength, Producer<T> result, Producer<T> value) {
		return a(memLength, () -> result, () -> value);
	}

	default <T extends MemWrapper> Runnable a(int memLength, Supplier<Producer<T>> result, Supplier<Producer<T>> value) {
		return Hardware.getLocalHardware().getComputer().compileRunnable(new AcceleratedAssignment<>(memLength, result, value));
	}

	default ScalarSupplier value(double value) { return scalar(value); }

	default ScalarSupplier scalar(double value) { return value(new Scalar(value)); }

	default ScalarSupplier value(Scalar value) {
		return new AcceleratedStaticScalarComputation(value, () -> Scalar.blank());
	}

	default ScalarProducer scalar() {
		return Scalar.blank();
	}

	default PairSupplier pair(double x, double y) { return value(new Pair(x, y)); }

	default PairSupplier value(Pair value) {
		return new AcceleratedStaticPairComputation(value, () -> Pair.empty());
	}

	default Supplier<Producer<? extends Vector>> vector(int argIndex) { return value(Vector.class, argIndex); }

	default VectorSupplier vector(double x, double y, double z) { return value(new Vector(x, y, z)); }

	default VectorSupplier vector(double v[]) { return vector(v[0], v[1], v[2]); }

	default Maker<Vector> vector() { return () -> Vector.blank(); }

	default VectorSupplier value(Vector value) {
		return new AcceleratedStaticVectorComputation(value, () -> Vector.blank());
	}

	default RaySupplier ray(double x, double y, double z, double dx, double dy, double dz) {
		return value(new Ray(new Vector(x, y, z), new Vector(dx, dy, dz)));
	}

	default RaySupplier value(Ray value) {
		return new AcceleratedStaticRayComputation(value, () -> Ray.blank());
	}

	default Supplier<Producer<? extends TriangleData>> triangle(int argIndex) { return value(TriangleData.class, argIndex); }

	default Supplier<Producer<? extends TrianglePointData>> points(int argIndex) { return value(TrianglePointData.class, argIndex); }

	default RGBSupplier rgb(double r, double g, double b) { return value(new RGB(r, g, b)); }

	default RGBSupplier rgb(Scalar v) { return cfromScalar(v); }

	default RGBSupplier rgb(double v) { return cfromScalar(v); }

	default RGBSupplier value(RGB value) {
		return new AcceleratedStaticRGBComputation(value, () -> RGB.blank());
	}

	default <T> Maker<T> value(T v) {
		if (v instanceof Scalar) {
			return (ProducerComputation<T>) new AcceleratedStaticScalarComputation((Scalar) v, () -> Scalar.blank());
		} else if (v instanceof Pair) {
			return (ProducerComputation<T>) new AcceleratedStaticPairComputation((Pair) v, () -> Pair.empty());
		} else if (v instanceof Vector) {
			return (ProducerComputation<T>) new AcceleratedStaticVectorComputation((Vector) v, () -> Vector.blank());
		} else if (v instanceof Ray) {
			return (ProducerComputation<T>) new AcceleratedStaticRayComputation((Ray) v, () -> Ray.blank());
		} else if (v instanceof TransformMatrix) {
			return (ProducerComputation<T>) new AcceleratedStaticTransformMatrixComputation((TransformMatrix) v, () -> TransformMatrix.blank());
		} else if (v == null) {
			return null;
		} else {
			return () -> new Provider<>(v);
		}
	}

	default <T> Supplier<Producer<? extends T>> value(Class<T> type, int argIndex) {
		return Input.value(type, argIndex);
	}

	default Ops o() { return Ops.ops(); }
}
