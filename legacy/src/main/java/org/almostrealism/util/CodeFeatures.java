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

import io.almostrealism.code.Computation;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.computations.AcceleratedStaticPairComputation;
import org.almostrealism.algebra.computations.AcceleratedStaticScalarComputation;
import org.almostrealism.algebra.computations.AcceleratedStaticVectorComputation;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.TransformMatrixFeatures;
import org.almostrealism.geometry.computations.AcceleratedStaticRayComputation;
import org.almostrealism.geometry.computations.AcceleratedStaticTransformMatrixComputation;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleDataFeatures;
import org.almostrealism.graph.mesh.TrianglePointData;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.code.ProducerComputation;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalarProducer;
import org.almostrealism.time.computations.TemporalScalarFromScalars;

import java.util.function.Function;
import java.util.function.Supplier;

public interface CodeFeatures extends ScalarFeatures, PairFeatures, TriangleDataFeatures, RayFeatures, TransformMatrixFeatures, RGBFeatures, HardwareFeatures {
	default <T> Producer<T> p(T value) { return () -> new Provider<>(value); }

	default Producer<CursorPair> v(CursorPair p) {
		throw new UnsupportedOperationException();
	}

	default <T> Producer<T> v(T v) {
		return value(v);
	}

	default <T> Producer<T> v(Class<T> type, int argIndex) {
		return value(type, argIndex);
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default ScalarProducer value(double value) { return scalar(value); }

	default TemporalScalarProducer temporal(Supplier<Evaluable<? extends Scalar>> time, Supplier<Evaluable<? extends Scalar>> value) {
		return new TemporalScalarFromScalars(time, value);
	}

	default Supplier<Evaluable<? extends Vector>> vector(int argIndex) { return value(Vector.class, argIndex); }

	default Supplier<Evaluable<? extends TriangleData>> triangle(int argIndex) { return value(TriangleData.class, argIndex); }

	default Supplier<Evaluable<? extends TrianglePointData>> points(int argIndex) { return value(TrianglePointData.class, argIndex); }

	default <T> Producer<T> value(T v) {
		if (v instanceof Scalar) {
			return (ProducerComputation<T>) new AcceleratedStaticScalarComputation((Scalar) v);
		} else if (v instanceof Pair) {
			return (ProducerComputation<T>) new AcceleratedStaticPairComputation((Pair) v);
		} else if (v instanceof Vector) {
			return (ProducerComputation<T>) new AcceleratedStaticVectorComputation((Vector) v);
		} else if (v instanceof Ray) {
			return (ProducerComputation<T>) new AcceleratedStaticRayComputation((Ray) v);
		} else if (v instanceof TransformMatrix) {
			return (ProducerComputation<T>) new AcceleratedStaticTransformMatrixComputation((TransformMatrix) v);
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
