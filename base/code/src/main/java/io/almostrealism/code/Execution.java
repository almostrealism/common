/*
 * Copyright 2024 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.concurrent.Semaphore;

/**
 * Represents a compiled, executable computation that accepts arguments and returns a semaphore.
 *
 * <p>An {@code Execution} is the result of compiling a {@link Computation} via an
 * {@link InstructionSet}. It accepts an array of pre-allocated memory arguments and
 * dispatches the computation asynchronously or synchronously, returning a {@link Semaphore}
 * that the caller can wait on.</p>
 *
 * @see InstructionSet
 * @see io.almostrealism.concurrent.Semaphore
 */
public interface Execution {
	/**
	 * Executes this computation with the given arguments, with no predecessor dependency.
	 *
	 * @param args the array of memory arguments to pass to the computation
	 * @return a semaphore that completes when the execution finishes
	 */
	default Semaphore accept(Object[] args) {
		return accept(args, null);
	}

	/**
	 * Executes this computation with the given arguments, waiting for a predecessor to complete first.
	 *
	 * @param args the array of memory arguments to pass to the computation
	 * @param dependsOn a semaphore from a preceding execution that must complete first, or {@code null}
	 * @return a semaphore that completes when this execution finishes
	 */
	Semaphore accept(Object[] args, Semaphore dependsOn);

	/**
	 * Returns whether this execution has been destroyed and is no longer usable.
	 *
	 * @return {@code true} if this execution has been destroyed, {@code false} otherwise
	 */
	default boolean isDestroyed() {
		return false;
	}
}
