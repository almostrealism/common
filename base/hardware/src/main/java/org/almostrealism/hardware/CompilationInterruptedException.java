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
 * Thrown when a kernel compilation is not started because the calling thread
 * has been interrupted.
 *
 * <p>An interrupted thread does not start a new compilation. The check happens at
 * safe points before any build begins (before {@code ComputeContext.deliver} in
 * {@link org.almostrealism.hardware.instructions.ScopeInstructionsManager}, and before
 * an OpenCL program is created or built), so no native work is left outstanding when
 * this exception is thrown. A build that has already started is never abandoned; it
 * completes, and the next compilation on the same thread is the one that fails.</p>
 *
 * <p>The interrupt status of the thread is left set, so callers further up the stack
 * still observe the interrupt. This is a subtype of {@link HardwareException} so that
 * code which already handles the dispatch-side interrupt failure (reported by
 * {@code AcceleratedOperation.awaitCompletion}) handles a cancelled compilation the
 * same way, while callers and tests that need to distinguish a cancellation from a
 * genuine compile error can do so by type.</p>
 */
public class CompilationInterruptedException extends HardwareException {

	/**
	 * Creates an exception describing the cancelled compilation of the named operation.
	 *
	 * @param operation the name of the operation whose compilation was not started
	 */
	public CompilationInterruptedException(String operation) {
		super("Compilation of " + operation + " cancelled: the calling thread was interrupted");
	}

	/**
	 * Throws a {@link CompilationInterruptedException} if the current thread has been
	 * interrupted. The interrupt status is only read, never cleared.
	 *
	 * @param operation the name of the operation about to be compiled, used in the message
	 * @throws CompilationInterruptedException if the current thread's interrupt status is set
	 */
	public static void throwIfInterrupted(String operation) {
		if (Thread.currentThread().isInterrupted()) {
			throw new CompilationInterruptedException(operation);
		}
	}
}
