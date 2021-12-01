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

import io.almostrealism.code.Variable;
import io.almostrealism.code.expressions.Expression;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.computations.StaticPairComputation;
import org.almostrealism.algebra.computations.StaticScalarBankComputation;
import org.almostrealism.algebra.computations.StaticScalarComputation;
import org.almostrealism.algebra.computations.StaticVectorComputation;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.TransformMatrixFeatures;
import org.almostrealism.geometry.computations.StaticRayComputation;
import org.almostrealism.geometry.computations.StaticTransformMatrixComputation;
import org.almostrealism.graph.mesh.TriangleData;
import org.almostrealism.graph.mesh.TriangleDataFeatures;
import org.almostrealism.graph.mesh.TrianglePointData;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.code.ProducerComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalScalarProducer;
import org.almostrealism.time.computations.TemporalScalarFromScalars;

import java.util.concurrent.Callable;
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

	default <T> Producer<T> v(int memLength, int argIndex) {
		return value(memLength, argIndex);
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default ScalarProducer value(double value) { return scalar(value); }

	default TemporalScalarProducer temporal(Supplier<Evaluable<? extends Scalar>> time, Supplier<Evaluable<? extends Scalar>> value) {
		return new TemporalScalarFromScalars(time, value);
	}

	default Supplier<Evaluable<? extends Vector>> vector(int argIndex) { return value(Vector.class, argIndex); }

	default Supplier<Evaluable<? extends ScalarBank>> scalars(ScalarBank s) { return value(s); }

	default Supplier<Evaluable<? extends TriangleData>> triangle(int argIndex) { return value(TriangleData.class, argIndex); }

	default Supplier<Evaluable<? extends TrianglePointData>> points(int argIndex) { return value(TrianglePointData.class, argIndex); }

	default <T> Producer<T> value(T v) {
		if (v instanceof Scalar) {
			return (ProducerComputation<T>) new StaticScalarComputation((Scalar) v);
		} else if (v instanceof ScalarBank) {
			return (ProducerComputation<T>) new StaticScalarBankComputation((ScalarBank) v);
		} else if (v instanceof Pair) {
			return (ProducerComputation<T>) new StaticPairComputation((Pair) v);
		} else if (v instanceof Vector) {
			return (ProducerComputation<T>) new StaticVectorComputation((Vector) v);
		} else if (v instanceof Ray) {
			return (ProducerComputation<T>) new StaticRayComputation((Ray) v);
		} else if (v instanceof TransformMatrix) {
			return (ProducerComputation<T>) new StaticTransformMatrixComputation((TransformMatrix) v);
		} else if (v == null) {
			return null;
		} else {
			return () -> new Provider<>(v);
		}
	}

	default <T> Producer<T> value(Class<T> type, int argIndex) {
		return Input.value(type, argIndex);
	}

	default <T> Producer<T> value(int memLength, int argIndex) {
		return Input.value(memLength, argIndex);
	}

	default Expression<Double> e(double value) {
		return e(stringForDouble(value));
	}

	default Expression<Double> e(String expression, Variable<?, ?>... dependencies) {
		return new Expression<>(Double.class, expression, dependencies);
	}

	default void dc(Runnable r) {
		dc(() -> { r.run(); return null; });
	}

	default <T> T dc(Callable<T> exec) {
		return Hardware.getLocalHardware().dataContext(exec);
	}

	default void cc(Runnable r) {
		cc(() -> { r.run(); return null; });
	}

	default <T> T cc(Callable<T> exec) {
		return Hardware.getLocalHardware().getClDataContext().computeContext(exec);
	}

	default Ops o() { return Ops.ops(); }
}
