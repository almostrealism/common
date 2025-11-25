/*
 * Copyright 2025 Michael Murray
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
import io.almostrealism.code.DataContext;
import io.almostrealism.code.ProducerComputation;
import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.DynamicProducer;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.computations.Switch;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayFeatures;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.geometry.TransformMatrixFeatures;
import org.almostrealism.graph.mesh.TriangleFeatures;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.Input;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataCopy;
import org.almostrealism.layers.LayerFeatures;
import org.almostrealism.time.TemporalFeatures;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The master interface that aggregates all feature interfaces in the Almost Realism framework.
 * CodeFeatures provides a comprehensive API for building computations, creating neural networks,
 * performing mathematical operations, and managing hardware execution contexts.
 *
 * <p>This interface combines capabilities from multiple specialized feature interfaces:</p>
 * <ul>
 *   <li>{@link LayerFeatures} - Neural network layer creation (dense, conv2d, norm, etc.)</li>
 *   <li>{@link TriangleFeatures} - 3D geometry and mesh operations</li>
 *   <li>{@link TransformMatrixFeatures} - Transformation matrix operations</li>
 *   <li>{@link TemporalFeatures} - Time-based processing</li>
 *   <li>{@link ComputerFeatures} - Hardware execution and memory operations</li>
 * </ul>
 *
 * <h2>Producer Creation</h2>
 * <p>CodeFeatures provides several methods for creating data producers:</p>
 * <ul>
 *   <li>{@link #v(Object)} - Create a producer from a value</li>
 *   <li>{@link #v(TraversalPolicy, int)} - Create an input producer by index</li>
 *   <li>{@link #x(int...)}, {@link #y(int...)}, {@link #z(int...)} - Shorthand for indexed inputs</li>
 *   <li>{@link #value(Object)} - Flexible value wrapping</li>
 * </ul>
 *
 * <h2>Context Management</h2>
 * <p>Methods for managing hardware and compute contexts:</p>
 * <ul>
 *   <li>{@link #dc()} - Get the current data context</li>
 *   <li>{@link #dc(Runnable)} - Execute within a data context</li>
 *   <li>{@link #cc()} - Get the current compute context</li>
 *   <li>{@link #cc(Runnable, ComputeRequirement...)} - Execute with compute requirements</li>
 * </ul>
 *
 * <h2>Profiling</h2>
 * <p>Performance profiling support:</p>
 * <ul>
 *   <li>{@link #profile(String, Supplier)} - Profile an operation supplier</li>
 *   <li>{@link #profile(OperationProfileNode, Runnable)} - Profile a runnable</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <p>Classes implementing CodeFeatures gain access to all operations:</p>
 * <pre>{@code
 * public class MyModel implements CodeFeatures {
 *     public void build() {
 *         // All operations available directly
 *         Model model = new Model(shape(784));
 *         model.add(dense(256));
 *         model.add(silu());
 *         model.add(dense(10));
 *         model.add(softmax());
 *     }
 * }
 * }</pre>
 *
 * <p>Alternatively, access through the {@link Ops} singleton:</p>
 * <pre>{@code
 * import static org.almostrealism.Ops.o;
 *
 * CollectionProducer<?> result = o().multiply(a, b);
 * }</pre>
 *
 * @see Ops
 * @see LayerFeatures
 * @see ComputerFeatures
 * @author Michael Murray
 */
public interface CodeFeatures extends LayerFeatures,
								TriangleFeatures, TransformMatrixFeatures,
								TemporalFeatures, ComputerFeatures {
	boolean enableFixedCollections = true;

	default <T> Producer<T> v(T v) {
		if (v instanceof TraversalPolicy) {
			warn("TraversalPolicy provided as Producer value");
			return v((TraversalPolicy) v, 0);
		}

		return value(v);
	}

	@Deprecated
	default <T> Producer<T> v(int memLength, int argIndex) {
		return value(memLength, argIndex);
	}

	default <T> Producer<T> v(TraversalPolicy shape, int argIndex) {
		return value(shape, argIndex);
	}

	default CollectionProducer<PackedCollection<?>> x(int... dims) {
		return c(value(dims.length == 0 ? shape(-1, 1) : shape(dims), 0));
	}

	default CollectionProducer<PackedCollection<?>> y(int... dims) {
		return c(value(dims.length == 0 ? shape(-1, 1) : shape(dims), 1));
	}

	default CollectionProducer<PackedCollection<?>> z(int... dims) {
		return c(value(dims.length == 0 ? shape(-1, 1) : shape(dims), 2));
	}

	default <T extends PackedCollection<?>> CollectionProducer<T> cv(TraversalPolicy shape, int argIndex) {
		return c(value(shape, argIndex));
	}

	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	default Producer<PackedCollection<?>> vector(int argIndex) { return value(Vector.shape(), argIndex); }

	default Producer<PackedCollection<?>> triangle(int argIndex) { return value(shape(4, 3), argIndex); }

	default Producer<PackedCollection<?>> points(int argIndex) { return value(shape(3, 3), argIndex); }

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
			return (Producer) c((PackedCollection) v);
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
		TraversalPolicy sourceShape = source instanceof Shape ? ((Shape) source).getShape() : null;
		TraversalPolicy targetShape = target instanceof Shape ? ((Shape) target).getShape() : null;

		if (sourceShape != null && sourceShape.getTotalSizeLong() < length) {
			throw new IllegalArgumentException();
		} else if (targetShape != null && targetShape.getTotalSizeLong() < length) {
			throw new IllegalArgumentException();
		}

		if (enableAssignmentCopy) {
			if (sourceShape != null) source = new ReshapeProducer(sourceShape.traverseEach(), source);
			if (targetShape != null) target = new ReshapeProducer(targetShape.traverseEach(), target);
			return new Assignment(1, target, source);
		} else {
			return new MemoryDataCopy(name, source.get()::evaluate, target.get()::evaluate, length);
		}
	}

	default <T> Switch choice(Producer<PackedCollection<?>> decision, Computation<T>... choices) {
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

	default <T extends OperationProfile> T profile(T profile, Runnable r) {
		try {
			Hardware.getLocalHardware().assignProfile(profile);
			r.run();
			return profile;
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}
	}

	default OperationProfileNode profile(String name, Supplier<Runnable> op) {
		return profile(new OperationProfileNode(name), op);
	}

	default OperationProfileNode profile(OperationProfileNode profile, Supplier<Runnable> op) {
		Runnable r;

		if (op instanceof OperationList && profile != null) {
			r = ((OperationList) op).get(profile);
		} else {
			r = op.get();
		}

		return profile(profile, r);
	}

	default OperationProfileNode profile(String name, Runnable r) {
		return profile(name, () -> r);
	}

	default Ops ops() { return Ops.o(); }
}
