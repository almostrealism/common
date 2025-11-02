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
	default int getCount() {
		return Math.toIntExact(getCountLong());
	}

	long getCountLong();

	/**
	 * Returns {@code true} if the count is fixed at construction time,
	 * {@code false} if it varies based on runtime arguments.
	 *
	 * <p>The default implementation returns {@code true}.</p>
	 *
	 * @return {@code true} if count is fixed, {@code false} if variable.
	 */
	default boolean isFixedCount() {
		return true;
	}

	static <T> int count(T c) {
		return Math.toIntExact(countLong(c));
	}

	static <T> long countLong(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).getCountLong();
		}

		return 1;
	}

	static <T> boolean isFixedCount(T c) {
		if (c instanceof Countable) {
			return ((Countable) c).isFixedCount();
		}

		return true;
	}
}
