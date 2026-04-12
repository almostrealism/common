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

import io.almostrealism.lifecycle.Destroyable;

/**
 * A compiled set of executable instructions that can produce {@link Execution} instances.
 *
 * <p>An {@code InstructionSet} represents the result of compiling a computation into native
 * code (e.g., an OpenCL or Metal program). Individual {@link Execution} objects are retrieved
 * from the instruction set by function name and can be dispatched with memory arguments.</p>
 *
 * @see Execution
 * @see io.almostrealism.lifecycle.Destroyable
 */
public interface InstructionSet extends Destroyable {
	/**
	 * Returns an {@link Execution} for the default function named {@code "function"}.
	 *
	 * @return the default execution
	 */
	default Execution get() {
		return get("function");
	}

	/**
	 * Returns an {@link Execution} for the named function with no fixed argument count.
	 *
	 * @param function the function name within this instruction set
	 * @return an execution for the named function
	 */
	default Execution get(String function) {
		return get(function, 0);
	}

	/**
	 * Returns an {@link Execution} for the named function expecting the given number of arguments.
	 *
	 * @param function the function name within this instruction set
	 * @param argCount the expected number of memory arguments
	 * @return an execution for the named function
	 */
	Execution get(String function, int argCount);

	/**
	 * Returns whether this instruction set has been destroyed and is no longer usable.
	 *
	 * @return {@code true} if destroyed
	 */
	boolean isDestroyed();
}
