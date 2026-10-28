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

package org.almostrealism.hardware.external;

import io.almostrealism.code.Execution;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.MemoryData;

import java.util.stream.IntStream;

/**
 * An {@link Execution} of an {@link ExternalInstructionSet}'s executable over a fixed number
 * of {@link MemoryData} arguments.
 *
 * <p>The executable is given its arguments and nothing else: no work-item index reaches it,
 * so it performs exactly one work item. A work range of any other size, or one that starts
 * past the first item, cannot be honored and is refused when it is set, by the single-item
 * range {@link io.almostrealism.kernel.KernelWork} provides by default.</p>
 *
 * <p>The executable runs as a separate process that this waits for, so
 * {@link #accept(Object[], Semaphore)} waits for its dependency on the calling thread and
 * returns once the results are in the arguments.</p>
 *
 * @see ExternalInstructionSet#apply(MemoryData...)
 */
public class ExternalExecution implements Execution {
	/** The instruction set whose executable this runs. */
	private final ExternalInstructionSet instructions;

	/** Number of {@link MemoryData} arguments passed to the executable. */
	private final int argCount;

	/**
	 * Creates an execution passing the given number of arguments to the executable of the
	 * given instruction set.
	 *
	 * @param instructions the instruction set whose executable this runs
	 * @param argCount     Number of {@link MemoryData} arguments
	 */
	protected ExternalExecution(ExternalInstructionSet instructions, int argCount) {
		this.instructions = instructions;
		this.argCount = argCount;
	}

	/**
	 * Runs the executable over the arguments once {@code dependsOn} has completed.
	 *
	 * @param args      the {@link MemoryData} arguments
	 * @param dependsOn the completion to wait for before running, or {@code null}
	 * @return {@code null}, as the executable has completed when this returns
	 */
	@Override
	public Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (dependsOn != null) dependsOn.waitFor();

		instructions.apply(IntStream.range(0, argCount)
				.mapToObj(i -> (MemoryData) args[i])
				.toArray(MemoryData[]::new));
		return null;
	}
}
