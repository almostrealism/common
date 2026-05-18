/*
 * Copyright 2020 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.relation.Evaluable;

import java.util.Optional;

/**
 * Represents the top-level compute engine that compiles and executes computations.
 *
 * <p>A {@code Computer} bridges the gap between the abstract {@link Computation} model
 * and the concrete hardware execution layer. It can compile computations into executable
 * {@link Runnable} or {@link Evaluable} instances, and optionally decompile them back.</p>
 *
 * @param <B> the memory buffer type used by the backing compute context
 *
 * @see ComputeContext
 * @see Computation
 */
public interface Computer<B> {
	/**
	 * Returns the compute context responsible for executing the given computation.
	 *
	 * @param c the computation to look up a context for
	 * @return the compute context for the computation
	 */
	ComputeContext<B> getContext(Computation<?> c);

	/**
	 * Compiles a void computation into a {@link Runnable} that can be executed.
	 *
	 * @param c the void computation to compile
	 * @return a runnable representing the compiled computation
	 */
	Runnable compileRunnable(Computation<Void> c);

	/**
	 * Compiles a computation into an {@link Evaluable} that produces values of type {@code T}.
	 *
	 * @param <T> the output type of the computation
	 * @param c the computation to compile
	 * @return an evaluable representing the compiled computation
	 */
	<T extends B> Evaluable<T> compileProducer(Computation<T> c);

	/**
	 * Attempts to recover the original computation from a compiled {@link Runnable}.
	 *
	 * @param <T> the output type of the computation
	 * @param r the runnable to decompile
	 * @return an optional containing the original computation, or empty if unavailable
	 */
	<T> Optional<Computation<T>> decompile(Runnable r);

	/**
	 * Attempts to recover the original computation from a compiled {@link Evaluable}.
	 *
	 * @param <T> the output type of the computation
	 * @param p the evaluable to decompile
	 * @return an optional containing the original computation, or empty if unavailable
	 */
	<T> Optional<Computation<T>> decompile(Evaluable<T> p);
}
