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

import io.almostrealism.kernel.KernelWork;
import io.almostrealism.streams.Semaphore;

/**
 * Represents a compiled, executable computation that accepts arguments and returns a semaphore.
 *
 * <p>An {@code Execution} is the result of compiling a {@link Computation} via an
 * {@link InstructionSet}. It accepts an array of pre-allocated memory arguments and
 * dispatches the computation asynchronously or synchronously, returning a {@link Semaphore}
 * that the caller can wait on.</p>
 *
 * <p>The computation is dispatched over the work range of the {@link KernelWork} this
 * extends, which the caller sets before {@link #accept(Object[], Semaphore) accepting}
 * arguments. A range the backend cannot dispatch over is refused, when it is set or when
 * the execution is dispatched, and is never replaced by a different range.</p>
 *
 * @see InstructionSet
 * @see KernelWork
 * @see io.almostrealism.streams.Semaphore
 */
public interface Execution extends KernelWork {
	/**
	 * Executes this computation with the given arguments, with no predecessor dependency.
	 *
	 * @param args the array of memory arguments to pass to the computation
	 * @return a semaphore that completes when the execution finishes, or {@code null} if it
	 *         has already finished when this returns
	 */
	default Semaphore accept(Object[] args) {
		return accept(args, null);
	}

	/**
	 * Executes this computation with the given arguments, waiting for a predecessor to complete first.
	 *
	 * @param args the array of memory arguments to pass to the computation
	 * @param dependsOn a semaphore from a preceding execution that must complete first, or {@code null}
	 * @return a semaphore that completes when this execution finishes, or {@code null} if it
	 *         has already finished when this returns
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
