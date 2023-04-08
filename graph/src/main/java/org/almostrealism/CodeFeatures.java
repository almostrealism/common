/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.ComputeRequirement;
import io.almostrealism.code.DataContext;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBankFeatures;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankFeatures;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.algebra.computations.Switch;
import org.almostrealism.algebra.computations.StaticPairComputation;
import org.almostrealism.algebra.computations.StaticScalarBankComputation;
import org.almostrealism.algebra.computations.StaticVectorComputation;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.Shape;
import org.almostrealism.collect.TraversableKernelExpression;
import org.almostrealism.collect.TraversalPolicy;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
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
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelOperation;
import org.almostrealism.hardware.KernelizedProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.time.CursorPair;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.TemporalScalarProducerBase;
import org.almostrealism.time.computations.TemporalScalarExpressionComputation;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

public interface CodeFeatures extends LayerFeatures, ScalarBankFeatures,
								PairFeatures, PairBankFeatures,
								TriangleDataFeatures, RayFeatures,
								TransformMatrixFeatures, GeometryFeatures,
								TemporalFeatures, HardwareFeatures {

	default Producer<CursorPair> v(CursorPair p) {
		throw new UnsupportedOperationException();
	}

	default <T> Producer<T> v(T v) {
		return value(v);
	}

	default <T> Producer<T> v(Class<T> type, int argIndex) {
		return value(type, argIndex);
	}

	default <T> Producer<T> v(Class<T> type, int argIndex, int kernelDimension) {
		return value(type, argIndex, kernelDimension);
	}

	default <T> Producer<T> v(int memLength, int argIndex) {
		return value(memLength, argIndex);
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default ScalarProducerBase value(double value) { return scalar(value); }

	default TemporalScalarProducerBase temporal(Supplier<Evaluable<? extends Scalar>> time, Supplier<Evaluable<? extends Scalar>> value) {
//		return new TemporalScalarFromScalars(time, value);

		return new TemporalScalarExpressionComputation(
				List.of(args -> args.get(1).getValue(0), args -> args.get(2).getValue(0)),
					(Supplier) time, (Supplier) value);
	}

	default Supplier<Evaluable<? extends Vector>> vector(int argIndex) { return value(Vector.class, argIndex); }

	default Supplier<Evaluable<? extends ScalarBank>> scalars(ScalarBank s) { return value(s); }

	default Supplier<Evaluable<? extends TriangleData>> triangle(int argIndex) { return value(TriangleData.class, argIndex); }

	default Supplier<Evaluable<? extends PackedCollection<?>>> points(int argIndex) { return value(shape(3, 3), argIndex); }

	default <T> Producer<T> value(T v) {
		if (v instanceof Scalar) {
			return (ProducerComputation<T>) ScalarFeatures.of((Scalar) v);
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

	default <T> Producer<T> value(Class<T> type, int argIndex, int kernelDimension) {
		return Input.value(type, argIndex, kernelDimension);
	}

	default <T> Producer<T> value(TraversalPolicy shape, int argIndex) {
		return Input.value(shape.getTotalSize(), argIndex);
	}

	default <T> Producer<T> value(int memLength, int argIndex) {
		return Input.value(memLength, argIndex);
	}

	default <T> Switch choice(ProducerComputation<PackedCollection<?>> decision, Computation<T>... choices) {
		return new Switch(decision, Arrays.asList(choices));
	}

	default Expression<Double> e(double value) {
		return e(stringForDouble(value));
	}

	default CollectionProducerComputation<PackedCollection<?>> identity(Producer<PackedCollection<?>> argument) {
		if (!(argument instanceof Shape)) {
			throw new IllegalArgumentException("Argument to identity kernel must be traversable");
		}

		return kernel(((Shape) argument).getShape(), (args, pos) -> args[1].get(pos), argument);
	}

	default <T extends MemoryData> Supplier<Runnable> run(KernelizedProducer<T> kernel, MemoryBank destination, MemoryData... arguments) {
		return new KernelOperation<>(kernel, destination, arguments);
	}

	default CollectionProducerComputation<PackedCollection<?>> kernel(TraversableKernelExpression kernel,
																	  Producer... arguments) {
		return kernel(kernel.getShape(), kernel, arguments);
	}

	default CollectionProducerComputation<PackedCollection<?>> kernel(TraversalPolicy shape,
																	  KernelExpression kernel,
																	  Producer... arguments) {
		return kernel(kernelIndex(), shape, kernel, arguments);
	}

	default DataContext dc() {
		return Hardware.getLocalHardware().getDataContext();
	}

	default void dc(Runnable r) {
		dc(() -> { r.run(); return null; });
	}

	default <T> T dc(Callable<T> exec) {
		return Hardware.getLocalHardware().dataContext(exec);
	}

	default ComputeContext cc() {
		return Hardware.getLocalHardware().getComputeContext();
	}

	default void cc(Runnable r, ComputeRequirement... expectations) {
		cc(() -> { r.run(); return null; }, expectations);
	}

	default <T> T cc(Callable<T> exec, ComputeRequirement... expectations) {
		return Hardware.getLocalHardware().getDataContext().computeContext(exec, expectations);
	}

	default Ops o() { return Ops.ops(); }
}
