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
 * {@link Countable} represents a data structure or computation that has a countable
 * number of elements. The count can be either fixed at construction time or variable
 * based on runtime arguments.
 *
 * <p><b>Fixed vs Variable Count:</b></p>
 * <ul>
 *   <li><b>Fixed count:</b> The number of elements is known at construction time and
 *       cannot change. For example, a {@link org.almostrealism.collect.PackedCollection}
 *       with a specific size, or a computation that always produces exactly N outputs.</li>
 *   <li><b>Variable count:</b> The number of elements depends on runtime inputs,
 *       particularly the size of input arguments. For example, a computation that
 *       processes each element of an input collection would have a variable count
 *       that matches the input size.</li>
 * </ul>
 *
 * <p><b>Kernel Execution:</b> The distinction between fixed and variable count is
 * critical for GPU kernel execution. Fixed-count operations compile kernels with
 * a predetermined size, while variable-count operations compile kernels that can
 * adapt to different input sizes. See {@link org.almostrealism.hardware.ProcessDetailsFactory}
 * for kernel size determination logic.</p>
 *
 * @see org.almostrealism.hardware.PassThroughProducer
 * @see io.almostrealism.collect.TraversalPolicy
 */
public interface Countable {
	default int getCount() {
		return Math.toIntExact(getCountLong());
	}

	long getCountLong();

	/**
	 * Returns {@code true} if the count is fixed at construction time,
	 * {@code false} if it varies based on runtime arguments (particularly
	 * the size of input arguments).
	 *
	 * <p>Default implementation returns {@code true} for backward compatibility.
	 * Classes that support variable counts (like {@link org.almostrealism.hardware.PassThroughProducer}
	 * with variable {@link io.almostrealism.collect.TraversalPolicy}) should override
	 * this to return {@code false}.</p>
	 *
	 * @return {@code true} if count is fixed, {@code false} if variable
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
