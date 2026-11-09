/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware;

/**
 * Thrown when the instruction cache matched two distinct kernels to one signature: an operation
 * reusing a kernel compiled for another operation with the same signature and compute context
 * found that the kernel does not fit it.
 *
 * <p>A collision is never absorbed. It is a {@link HardwareException} like any other failure of
 * the operation, and its own type exists only so that it can be told apart from those failures
 * without reading its message, which is what lets a measurement of the cache count collisions
 * without counting a broken kernel or a driver error as one.</p>
 *
 * @see org.almostrealism.hardware.arguments.ProcessArgumentMap#verifySubstitutions(String)
 * @see AcceleratedComputationOperation#load()
 */
public class InstructionCacheCollisionException extends HardwareException {
	/** What about the reusing computation failed to match the compiled kernel. */
	public enum Kind {
		/**
		 * The reusing computation's process tree does not supply a producer for every argument
		 * position of the compiled scope.
		 */
		STRUCTURE,

		/**
		 * Replaying argument aggregation over the reusing computation folds its inputs at
		 * aggregate positions other than the ones the compiled kernel addresses.
		 */
		AGGREGATE_LAYOUT
	}

	/** What failed to match. */
	private final Kind kind;

	/**
	 * Creates a collision of the given kind.
	 *
	 * @param kind    what about the reusing computation failed to match the compiled kernel
	 * @param message a description of both parties of the collision
	 */
	public InstructionCacheCollisionException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	/**
	 * Returns what about the reusing computation failed to match the compiled kernel.
	 *
	 * @return the kind of collision
	 */
	public Kind getKind() { return kind; }

	/**
	 * Returns the collision in a failure's cause chain, so that a caller can tell a collision apart
	 * from any other failure of the operation however deeply it was wrapped.
	 *
	 * @param failure the failure to search, beginning with the failure itself
	 * @return the first collision in the cause chain, or null when the chain contains none
	 */
	public static InstructionCacheCollisionException findIn(Throwable failure) {
		for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
			if (t instanceof InstructionCacheCollisionException) return (InstructionCacheCollisionException) t;
		}

		return null;
	}
}
