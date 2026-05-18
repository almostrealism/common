/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.relation;

import io.almostrealism.compute.Process;
import io.almostrealism.lifecycle.Destroyable;

import java.util.function.Supplier;

/**
 * A {@link Producer} represents a computational operation that can produce an {@link Evaluable}
 * which, when executed, generates a result of type {@code T}.
 *
 * <p>{@link Producer} is the core abstraction in the Almost Realism computation framework that
 * separates <b>computation description</b> from <b>computation execution</b>. This separation
 * enables:</p>
 * <ul>
 *   <li>Static analysis of computation graphs before execution</li>
 *   <li>Optimization and fusion of operations</li>
 *   <li>Compilation to GPU kernels or other accelerated backends</li>
 *   <li>Deferred evaluation until results are actually needed</li>
 * </ul>
 *
 * <h2>Two-Phase Execution Model</h2>
 * <ol>
 *   <li><b>Description Phase:</b> Build a computation graph by combining {@link Producer}s
 *       using operations like {@link Composition} and {@link Factor}</li>
 *   <li><b>Execution Phase:</b> Call {@link #get()} to obtain an {@link Evaluable},
 *       then {@link Evaluable#evaluate(Object...)} to produce results</li>
 * </ol>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Build computation graph (description phase)
 * Producer<Tensor> inputA = ...;
 * Producer<Tensor> inputB = ...;
 * Producer<Tensor> sum = operations.add(inputA, inputB);
 *
 * // Execute computation (execution phase)
 * Evaluable<Tensor> evaluable = sum.get();
 * Tensor result = evaluable.evaluate();
 * }</pre>
 *
 * <h2>Relationship to Other Types</h2>
 * <ul>
 *   <li>{@link Evaluable} - The executable form produced by {@link #get()}</li>
 *   <li>{@link Computable} - Marker interface indicating computation requirement</li>
 *   <li>{@link Node} - Enables participation in computation graphs</li>
 *   <li>{@link Factor} - Transforms one {@link Producer} into another</li>
 *   <li>{@link Composition} - Combines two {@link Producer}s into one</li>
 * </ul>
 *
 * @param <T> the type of the computation result
 *
 * @see Evaluable
 * @see Computable
 * @see Factor
 * @see Composition
 *
 * @author Michael Murray
 */
public interface Producer<T> extends Supplier<Evaluable<? extends T>>, Computable, Node, Destroyable {
	/**
	 * Creates and returns an {@link Evaluable} that can execute this computation.
	 *
	 * <p>This method compiles the computation represented by this {@link Producer}
	 * into an executable form. The returned {@link Evaluable} can be invoked
	 * multiple times with different arguments.</p>
	 *
	 * <p>Implementations may perform optimization, compilation to native code,
	 * or GPU kernel generation during this method.</p>
	 *
	 * @return an {@link Evaluable} that executes this computation
	 */
	@Override
	Evaluable<T> get();

	/**
	 * Obtains an {@link Evaluable} configured to write results directly into
	 * the specified destination.
	 *
	 * <p>This method enables in-place computation, avoiding allocation of
	 * new result objects when the destination is pre-allocated.</p>
	 *
	 * @param destination the object to receive computation results
	 * @return an {@link Evaluable} that writes to the destination
	 */
	default Evaluable<T> into(Object destination) {
		return get().into(destination);
	}

	/**
	 * Optimizes this {@link Producer} and evaluates the result in a single step.
	 *
	 * <p>This method applies optimization transformations to the computation
	 * graph before evaluation. It is useful for one-shot evaluations where
	 * the overhead of optimization is acceptable.</p>
	 *
	 * @param args arguments to pass to the evaluation
	 * @return the computed result
	 *
	 * @see Process#optimized(Supplier)
	 */
	default T evaluateOptimized(Object... args) {
		return Process.optimized(this).get().evaluate(args);
	}

	/**
	 * Evaluates this computation with the specified arguments.
	 *
	 * <p>This is a convenience method that combines {@link #get()} and
	 * {@link Evaluable#evaluate(Object...)} into a single call. For repeated
	 * evaluations, consider caching the {@link Evaluable} returned by
	 * {@link #get()}.</p>
	 *
	 * @param args arguments to pass to the evaluation
	 * @return the computed result
	 */
	default T evaluate(Object... args) {
		return get().evaluate(args);
	}
}
