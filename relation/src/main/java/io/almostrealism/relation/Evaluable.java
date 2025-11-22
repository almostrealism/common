/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.streams.EvaluableStreamingAdapter;
import io.almostrealism.streams.StreamingEvaluable;
import io.almostrealism.uml.Function;
import io.almostrealism.uml.Multiple;

import java.util.concurrent.Executor;

/**
 * An {@link Evaluable} represents a compiled, executable computation that produces
 * a result of type {@code T} when evaluated.
 *
 * <p>{@link Evaluable} is the executable counterpart to {@link Producer}. While a
 * {@link Producer} describes what computation should be performed, an {@link Evaluable}
 * is the compiled form that actually performs the computation. This interface is
 * typically obtained by calling {@link Producer#get()}.</p>
 *
 * <h2>Core Operation</h2>
 * <p>The primary method is {@link #evaluate(Object...)}, which executes the computation
 * with the provided arguments and returns the result. This method can be called
 * multiple times with different arguments.</p>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * // Obtain Evaluable from Producer
 * Evaluable<Tensor> evaluable = producer.get();
 *
 * // Execute computation (can be called multiple times)
 * Tensor result1 = evaluable.evaluate(input1);
 * Tensor result2 = evaluable.evaluate(input2);
 * }</pre>
 *
 * <h2>Advanced Features</h2>
 * <ul>
 *   <li><b>In-place computation:</b> Use {@link #into(Object)} to write results
 *       directly to a pre-allocated destination</li>
 *   <li><b>Async evaluation:</b> Use {@link #async()} or {@link #async(Executor)}
 *       to execute computation asynchronously</li>
 *   <li><b>Batch operations:</b> Use {@link #createDestination(int)} to allocate
 *       space for multiple results</li>
 * </ul>
 *
 * <h2>Relationship to Producer</h2>
 * <p>An {@link Evaluable} is created by a {@link Producer} and represents the
 * "executable" form of a computation description. The separation allows:</p>
 * <ul>
 *   <li>One-time compilation costs amortized over many evaluations</li>
 *   <li>Optimized native code or GPU kernels to be reused</li>
 *   <li>Clear distinction between setup and execution phases</li>
 * </ul>
 *
 * @param <T> the type of the computation result
 *
 * @see Producer
 * @see FixedEvaluable
 * @see Computable
 *
 * @author Michael Murray
 */
@Function
@FunctionalInterface
public interface Evaluable<T> extends Computable {
	/**
	 * Creates a container capable of holding multiple results of this computation.
	 *
	 * <p>This method is used for batch operations where multiple results need
	 * to be stored together. The returned {@link Multiple} can hold the specified
	 * number of result elements.</p>
	 *
	 * @param size the number of results to accommodate
	 * @return a container for multiple results
	 * @throws UnsupportedOperationException if batch destinations are not supported
	 */
	default Multiple<T> createDestination(int size) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Evaluates this computation with no arguments.
	 *
	 * <p>This is a convenience method equivalent to calling
	 * {@link #evaluate(Object...)} with an empty array.</p>
	 *
	 * @return the computed result
	 */
	default T evaluate() {
		return evaluate(new Object[0]);
	}

	/**
	 * Evaluates this computation with the specified arguments.
	 *
	 * <p>This is the core method of the {@link Evaluable} interface. It executes
	 * the compiled computation using the provided arguments and returns the result.</p>
	 *
	 * <p>The interpretation of arguments depends on the specific computation.
	 * Common argument types include input data, parameters, and configuration values.</p>
	 *
	 * @param args the arguments to the computation
	 * @return the computed result
	 */
	T evaluate(Object... args);

	/**
	 * Returns an {@link Evaluable} that writes results directly into the
	 * specified destination object.
	 *
	 * <p>This method enables in-place computation, which can improve performance
	 * by avoiding allocation of new result objects. The destination must be
	 * compatible with the result type of this computation.</p>
	 *
	 * @param destination the pre-allocated object to receive results
	 * @return an {@link Evaluable} configured for in-place computation
	 * @throws UnsupportedOperationException if in-place computation is not supported
	 */
	default Evaluable<T> into(Object destination) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns a streaming evaluable for asynchronous computation.
	 *
	 * <p>This method wraps this {@link Evaluable} to support asynchronous
	 * execution using a default executor that creates new threads.</p>
	 *
	 * @return a streaming evaluable for async execution
	 */
	default StreamingEvaluable<T> async() {
		return async(r -> new Thread(r, "Async Evaluable").start());
	}

	/**
	 * Returns a streaming evaluable for asynchronous computation using
	 * the specified executor.
	 *
	 * <p>This method wraps this {@link Evaluable} to support asynchronous
	 * execution. Results are delivered through the streaming interface
	 * as they become available.</p>
	 *
	 * @param executor the executor to use for async execution
	 * @return a streaming evaluable for async execution
	 */
	default StreamingEvaluable<T> async(Executor executor) {
		return new EvaluableStreamingAdapter<>(this, executor);
	}
}
