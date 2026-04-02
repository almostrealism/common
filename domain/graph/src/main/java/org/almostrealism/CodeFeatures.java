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
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.algebra.computations.Switch;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
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
	/** When {@code true}, constant {@link PackedCollection} instances are embedded as fixed data in the expression tree. */
	boolean enableFixedCollections = true;

	/**
	 * Returns a {@link Producer} for the given value, dispatching to the appropriate type-specific
	 * factory (Pair, Vector, Ray, TransformMatrix, PackedCollection) or wrapping the value in a
	 * {@link io.almostrealism.relation.Provider}.
	 *
	 * @param <T> the value type
	 * @param v   the value to wrap; a {@link TraversalPolicy} triggers a deprecation warning
	 * @return a producer that evaluates to {@code v}
	 */
	default <T> Producer<T> v(T v) {
		if (v instanceof TraversalPolicy) {
			warn("TraversalPolicy provided as Producer value");
			return v((TraversalPolicy) v, 0);
		}

		return value(v);
	}

	/**
	 * Returns a {@link Producer} backed by a kernel argument identified by memory-length and index.
	 *
	 * @param <T>       the value type
	 * @param memLength the number of scalars in the argument
	 * @param argIndex  the zero-based index of the kernel argument
	 * @return a producer wrapping the specified argument
	 * @deprecated prefer {@link #v(TraversalPolicy, int)}
	 */
	@Deprecated
	default <T> Producer<T> v(int memLength, int argIndex) {
		return value(memLength, argIndex);
	}

	/**
	 * Returns a {@link Producer} backed by a kernel argument with the specified shape and index.
	 *
	 * @param <T>      the value type
	 * @param shape    the traversal policy describing the argument shape
	 * @param argIndex the zero-based index of the kernel argument
	 * @return a producer wrapping the specified argument
	 */
	default <T> Producer<T> v(TraversalPolicy shape, int argIndex) {
		return value(shape, argIndex);
	}

	/**
	 * Returns a collection producer bound to the first kernel argument (the X input), optionally
	 * reshaped according to the supplied dimension sizes.
	 *
	 * @param dims optional explicit dimension sizes; when empty, defaults to a {@code (-1, 1)} shape
	 * @return a collection producer for argument 0
	 */
	default CollectionProducer x(int... dims) {
		return c(value(dims.length == 0 ? shape(-1, 1) : shape(dims), 0));
	}

	/**
	 * Returns a collection producer bound to the second kernel argument (the Y input), optionally
	 * reshaped according to the supplied dimension sizes.
	 *
	 * @param dims optional explicit dimension sizes; when empty, defaults to a {@code (-1, 1)} shape
	 * @return a collection producer for argument 1
	 */
	default CollectionProducer y(int... dims) {
		return c(value(dims.length == 0 ? shape(-1, 1) : shape(dims), 1));
	}

	/**
	 * Returns a collection producer bound to the third kernel argument (the Z input), optionally
	 * reshaped according to the supplied dimension sizes.
	 *
	 * @param dims optional explicit dimension sizes; when empty, defaults to a {@code (-1, 1)} shape
	 * @return a collection producer for argument 2
	 */
	default CollectionProducer z(int... dims) {
		return c(value(dims.length == 0 ? shape(-1, 1) : shape(dims), 2));
	}

	/**
	 * Returns a {@link CollectionProducer} backed by a shaped kernel argument.
	 *
	 * @param <T>      the concrete collection type
	 * @param shape    the traversal policy describing the argument shape
	 * @param argIndex the zero-based index of the kernel argument
	 * @return a collection producer for the specified argument
	 */
	default <T extends PackedCollection> CollectionProducer cv(TraversalPolicy shape, int argIndex) {
		return c(value(shape, argIndex));
	}

	/**
	 * Returns a {@link Producer} that evaluates a dynamic function over the kernel arguments.
	 *
	 * @param <T>      the value type
	 * @param function the function mapping kernel arguments to a value
	 * @return a producer backed by the given function
	 */
	default <T> Producer<T> v(Function<Object[], T> function) {
		return new DynamicProducer<>(function);
	}

	/**
	 * Returns a producer bound to a {@link io.almostrealism.primitives.Vector}-shaped kernel argument.
	 *
	 * @param argIndex the zero-based index of the kernel argument
	 * @return a producer for the vector argument
	 */
	default Producer<PackedCollection> vector(int argIndex) { return value(Vector.shape(), argIndex); }

	/**
	 * Returns a producer bound to a triangle-shaped (4 &times; 3) kernel argument.
	 *
	 * @param argIndex the zero-based index of the kernel argument
	 * @return a producer for the triangle argument
	 */
	default Producer<PackedCollection> triangle(int argIndex) { return value(shape(4, 3), argIndex); }

	/**
	 * Returns a producer bound to a points-shaped (3 &times; 3) kernel argument.
	 *
	 * @param argIndex the zero-based index of the kernel argument
	 * @return a producer for the points argument
	 */
	default Producer<PackedCollection> points(int argIndex) { return value(shape(3, 3), argIndex); }

	/**
	 * Returns a {@link Producer} for the given constant value, dispatching to the appropriate
	 * type-specific factory (Pair, Vector, Ray, TransformMatrix, PackedCollection) or wrapping
	 * the value in a {@link io.almostrealism.relation.Provider}.
	 *
	 * @param <T> the value type
	 * @param v   the constant value to wrap; {@code null} returns {@code null}
	 * @return a producer that evaluates to {@code v}
	 */
	default <T> Producer<T> value(T v) {
		if (v instanceof Pair) {
			return (ProducerComputation<T>) PairFeatures.getInstance().value((Pair) v);
		} else if (v instanceof Vector) {
			return (ProducerComputation<T>) VectorFeatures.getInstance().value((Vector) v);
		} else if (v instanceof Ray) {
			return (ProducerComputation<T>) RayFeatures.getInstance().value((Ray) v);
		} else if (v instanceof TransformMatrix) {
			return (ProducerComputation<T>) TransformMatrixFeatures.getInstance().value((TransformMatrix) v);
		} else if (enableFixedCollections && v instanceof PackedCollection) {
			return (Producer<T>) c((PackedCollection) v);
		} else if (v == null) {
			return null;
		} else {
			return () -> new Provider<>(v);
		}
	}

	/**
	 * Returns a {@link Producer} backed by a shaped kernel argument.
	 *
	 * @param <T>      the value type
	 * @param shape    the traversal policy describing the argument shape
	 * @param argIndex the zero-based index of the kernel argument
	 * @return a producer wrapping the specified argument
	 */
	default <T> Producer<T> value(TraversalPolicy shape, int argIndex) {
		return Input.value(shape, argIndex);
	}

	/**
	 * Returns a {@link Producer} backed by a flat memory-length kernel argument.
	 *
	 * @param <T>       the value type
	 * @param memLength the number of scalars in the argument
	 * @param argIndex  the zero-based index of the kernel argument
	 * @return a producer wrapping the specified argument
	 */
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
			if (sourceShape != null) source = traverseEach((Producer<PackedCollection>) source);
			if (targetShape != null) target = traverseEach((Producer<PackedCollection>) target);
			return new Assignment(1, target, source);
		} else {
			return new MemoryDataCopy(name, source.get()::evaluate, target.get()::evaluate, length);
		}
	}

	/**
	 * Creates a {@link Switch} computation that selects among several choices based on
	 * a scalar decision value.
	 *
	 * @param <T>      the computation result type
	 * @param decision a producer whose integer value selects the active choice
	 * @param choices  the candidate computations to select from
	 * @return a switch computation dispatching to the chosen computation at runtime
	 */
	default <T> Switch choice(Producer<PackedCollection> decision, Computation<T>... choices) {
		return new Switch(decision, Arrays.asList(choices));
	}

	/**
	 * Returns the current hardware {@link DataContext}.
	 *
	 * @return the active data context for the local hardware
	 */
	default DataContext dc() {
		return Hardware.getLocalHardware().getDataContext();
	}

	/**
	 * Executes the given {@link Runnable} inside a new {@link DataContext}, then closes it.
	 *
	 * @param r the runnable to execute within a data context
	 */
	default void dc(Runnable r) {
		dc(() -> { r.run(); return null; });
	}

	/**
	 * Executes the given {@link Callable} inside a new {@link DataContext}, returning its result.
	 *
	 * @param <T>  the return type
	 * @param exec the callable to execute within a data context
	 * @return the value returned by {@code exec}
	 */
	default <T> T dc(Callable<T> exec) {
		return Hardware.getLocalHardware().dataContext(exec);
	}

	/**
	 * Returns the current hardware {@link ComputeContext}.
	 *
	 * @return the active compute context for the local hardware
	 */
	default ComputeContext cc() {
		return Hardware.getLocalHardware().getComputeContext();
	}

	/**
	 * Executes the given {@link Runnable} inside a {@link ComputeContext} that satisfies
	 * the specified requirements.
	 *
	 * @param r            the runnable to execute within the compute context
	 * @param expectations the compute requirements the context must satisfy
	 */
	default void cc(Runnable r, ComputeRequirement... expectations) {
		cc(() -> { r.run(); return new Void[0]; }, expectations);
	}

	/**
	 * Executes the given {@link Callable} inside a {@link ComputeContext} that satisfies
	 * the specified requirements, returning its result.
	 *
	 * @param <T>          the return type
	 * @param exec         the callable to execute within the compute context
	 * @param expectations the compute requirements the context must satisfy
	 * @return the value returned by {@code exec}
	 */
	default <T> T cc(Callable<T> exec, ComputeRequirement... expectations) {
		return Hardware.getLocalHardware().computeContext(exec, expectations);
	}

	/**
	 * Assigns the given {@link OperationProfile} as the active profile, runs the runnable,
	 * then clears the profile and returns it with captured timing data.
	 *
	 * @param <T>     the profile type
	 * @param profile the operation profile to assign
	 * @param r       the runnable to profile
	 * @return the profile, populated with timing data after the runnable completes
	 */
	default <T extends OperationProfile> T profile(T profile, Runnable r) {
		try {
			Hardware.getLocalHardware().assignProfile(profile);
			r.run();
			return profile;
		} finally {
			Hardware.getLocalHardware().clearProfile();
		}
	}

	/**
	 * Profiles the given operation supplier under a named {@link OperationProfileNode},
	 * collecting timing data and returning the populated node.
	 *
	 * @param name the display name for the profile node
	 * @param op   a supplier producing the runnable to profile
	 * @return the profile node after the operation completes
	 */
	default OperationProfileNode profile(String name, Supplier<Runnable> op) {
		return profile(new OperationProfileNode(name), op);
	}

	/**
	 * Profiles the given operation supplier under the provided {@link OperationProfileNode},
	 * using the profile for timing if the supplier is an {@link OperationList}.
	 *
	 * @param profile the profile node to collect timing into; may be {@code null}
	 * @param op      a supplier producing the runnable to profile
	 * @return the profile node after the operation completes
	 */
	default OperationProfileNode profile(OperationProfileNode profile, Supplier<Runnable> op) {
		Runnable r;

		if (op instanceof OperationList && profile != null) {
			r = ((OperationList) op).get(profile);
		} else {
			r = op.get();
		}

		return profile(profile, r);
	}

	/**
	 * Profiles the given {@link Runnable} under a named {@link OperationProfileNode},
	 * collecting timing data and returning the populated node.
	 *
	 * @param name the display name for the profile node
	 * @param r    the runnable to profile
	 * @return the profile node after the runnable completes
	 */
	default OperationProfileNode profile(String name, Runnable r) {
		return profile(name, () -> r);
	}

	default Ops ops() { return Ops.o(); }
}
