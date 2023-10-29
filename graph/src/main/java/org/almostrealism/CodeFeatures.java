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
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBankFeatures;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBankFeatures;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.computations.Switch;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import io.almostrealism.collect.KernelExpression;
import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.computations.ExpressionComputation;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.geometry.GeometryFeatures;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.TransformMatrixFeatures;
import org.almostrealism.graph.mesh.TriangleFeatures;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.code.ProducerComputation;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.KernelOperation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataCopy;
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
								TriangleFeatures,
								TransformMatrixFeatures, GeometryFeatures,
								TemporalFeatures, HardwareFeatures {
	boolean enableFixedCollections = true;

	default Producer<CursorPair> v(CursorPair p) {
		throw new UnsupportedOperationException();
	}

	default <T> Producer<T> v(T v) {
		return value(v);
	}

	default <T> Producer<T> v(int memLength, int argIndex) {
		return value(memLength, argIndex);
	}

	default <T> Producer<T> v(TraversalPolicy shape, int argIndex) {
		return value(shape, argIndex);
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> cv(TraversalPolicy shape, int argIndex) {
		return c(value(shape, argIndex));
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default Producer<Scalar> value(double value) { return scalar(value); }

	default TemporalScalarProducerBase temporal(Supplier<Evaluable<? extends Scalar>> time, Supplier<Evaluable<? extends Scalar>> value) {
//		return new TemporalScalarFromScalars(time, value);

		return new TemporalScalarExpressionComputation(
				List.of(args -> args.get(1).getValueRelative(0), args -> args.get(2).getValueRelative(0)),
					(Supplier) time, (Supplier) value);
	}

	default Supplier<Evaluable<? extends Vector>> vector(int argIndex) { return value(Vector.shape(), argIndex); }

	default Producer<PackedCollection<Scalar>> scalars(PackedCollection<Scalar> s) {
		return ExpressionComputation.fixed(s, Scalar.scalarBankPostprocessor());
	}

	default Supplier<Evaluable<? extends PackedCollection<?>>> triangle(int argIndex) { return value(shape(4, 3), argIndex); }

	default Supplier<Evaluable<? extends PackedCollection<?>>> points(int argIndex) { return value(shape(3, 3), argIndex); }

	default <T> Producer<T> value(T v) {
		if (v instanceof Scalar) {
			return (ProducerComputation<T>) ScalarFeatures.of((Scalar) v);
		} else if (v instanceof Pair) {
			return (ProducerComputation<T>) PairFeatures.getInstance().value((Pair) v);
		} else if (v instanceof Vector) {
			return (ProducerComputation<T>) VectorFeatures.getInstance().value((Vector) v);
		} else if (v instanceof Ray) {
			return (ProducerComputation<T>) RayFeatures.getInstance().value((Ray) v);
		} else if (v instanceof TransformMatrix) {
			return (ProducerComputation<T>) TransformMatrixFeatures.getInstance().value((TransformMatrix) v);
		} else if (enableFixedCollections && v instanceof PackedCollection) {
			return (ProducerComputation) c((PackedCollection) v);
		} else if (v == null) {
			return null;
		} else {
			return () -> new Provider<>(v);
		}
	}

	default <T> Producer<T> value(TraversalPolicy shape, int argIndex) {
		return Input.value(shape, argIndex);
	}

	default <T> Producer<T> value(int memLength, int argIndex) {
		return Input.value(memLength, argIndex);
	}

	@Override
	default Supplier<Runnable> copy(String name, Producer<? extends MemoryData> source,
									Producer<? extends MemoryData> target, int length) {
		if (enableAssignmentCopy) {
			if (source instanceof Shape) source = new ReshapeProducer(((Shape) source).getShape().traverseEach(), source);
			if (target instanceof Shape) target = new ReshapeProducer(((Shape) target).getShape().traverseEach(), target);
			return new Assignment(1, target, source);
		} else {
			return new MemoryDataCopy(name, source.get()::evaluate, target.get()::evaluate, length);
		}
	}

	default <T> Switch choice(ProducerComputation<PackedCollection<?>> decision, Computation<T>... choices) {
		return new Switch(decision, Arrays.asList(choices));
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
		cc(() -> { r.run(); return new Void[0]; }, expectations);
	}

	default <T> T cc(Callable<T> exec, ComputeRequirement... expectations) {
		return Hardware.getLocalHardware().computeContext(exec, expectations);
	}

	default Ops o() { return Ops.ops(); }
}
