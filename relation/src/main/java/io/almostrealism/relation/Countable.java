/*
 * Copyright 2023 Michael Murray
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

/**
 * {@link Countable} is implemented by data structures and computations that have (or operate on)
 * multiple elements. The {@link #getCount() count} property specifies the number of groups of
 * elements that can be operated on independently or in parallel, and can either be fixed or
 * variable based on possible runtime conditions.
 *
 * <p><b>Fixed vs Variable Count:</b></p>
 * <ul>
 *   <li><b>Fixed count:</b> The number of such groups is known at construction time and
 *       cannot change. For example, a {@link io.almostrealism.uml.Plural} with a specific
 *       total size, or a {@link Computable} that would always produce or consume the
 *       same fixed number of results.</li>
 *   <li><b>Variable count:</b> The number or grouping of elements depends on runtime
 *       input, usually the properties of any input arguments. For example, a computation
 *       that processes each element of an input collection (no matter its total size)
 *       would have a variable count ({@link #isFixedCount()} would return false).</li>
 * </ul>
 *
 * <p><b>Kernel Execution:</b> The distinction between fixed and variable count is
 * critical for GPU kernel execution. Fixed-count operations compile kernels with
 * a predetermined size, while variable-count operations compile kernels that can
 * adapt to different input sizes.</p>
 *
 * @see io.almostrealism.compute.ParallelProcess
 */
public interface Countable {
	/**
	 * Returns the number of element groups as an integer.
	 *
	 * <p>This is a convenience method that delegates to {@link #getCountLong()}
	 * and converts the result to an {@code int}. Use {@link #getCountLong()}
	 * directly when dealing with very large counts that might overflow.</p>
	 *
	 * @return the count as an integer
	 * @throws ArithmeticException if the count overflows an {@code int}
	 */
	default int getCount() {
		return Math.toIntExact(getCountLong());
	}

	/**
	 * Returns the number of element groups that can be operated on independently.
	 *
	 * <p>This value determines the parallelism potential of the computation.
	 * For GPU execution, this typically maps to the kernel grid size.</p>
	 *
	 * @return the number of independent element groups
	 */
	long getCountLong();

	/**
	 * Returns {@code true} if the count is fixed at construction time,
	 * {@code false} if it varies based on runtime arguments.
	 *
	 * <p>The default implementation returns {@code true}.</p>
	 *
	 * @return {@code true} if count is fixed, {@code false} if variable
	 */
	default boolean isFixedCount() {
		return true;
	}

	/**
	 * Static utility to get the count of any object.
	 *
	 * <p>If the object implements {@link Countable}, returns its count.
	 * Otherwise, returns 1 (treating the object as a single element).</p>
	 *
	 * @param <T> the type of the object
	 * @param c the object to get the count of
	 * @return the count as an integer
	 */
	static <T> int count(T c) {
		return Math.toIntExact(countLong(c));
	}

	/**
	 * Static utility to get the count of any object as a long.
	 *
	 * <p>If the object implements {@link Countable}, returns its count.
	 * Otherwise, returns 1 (treating the object as a single element).</p>
	 *
	 * @param <T> the type of the object
	 * @param c the object to get the count of
	 * @return the count as a long
	 */
	static <T> long countLong(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).getCountLong();
		}

		return 1;
	}

	/**
	 * Static utility to determine if an object has a fixed count.
	 *
	 * <p>If the object implements {@link Countable}, delegates to its
	 * {@link #isFixedCount()} method. Otherwise, returns {@code true}
	 * (non-countable objects are considered to have a fixed count of 1).</p>
	 *
	 * @param <T> the type of the object
	 * @param c the object to check
	 * @return {@code true} if the object has a fixed count
	 */
	static <T> boolean isFixedCount(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).isFixedCount();
		}

		return true;
	}
}
