/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.jni;

import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Semaphore;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelSupport;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.hardware.cl.CLDataContext;
import org.jocl.cl_command_queue;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

public interface NativeInstructionSet extends InstructionSet, KernelSupport {
	default String getFunctionName() {
		return "Java_" +
				getClass().getName().replaceAll("\\.", "_") +
				"_apply";
	}

	@Override
	default boolean isKernelEnabled() { return false; }

	@Override
	default Execution get(String function, int argCount) {
		return (args, dependsOn) -> {
			if (dependsOn != null) dependsOn.waitFor();
			return apply(Stream.of(args).map(arg -> (MemoryData) arg).toArray(MemoryData[]::new));
		};
	}

	@Override
	default boolean isDestroyed() {
		return false;
	}

	@Override
	default void destroy() { }


	default Semaphore apply(MemoryData... args) {
		long id = NativeComputeContext.totalInvocations++;

		if (NativeComputeContext.enableVerbose && (id + 1) % 100000 == 0) {
			System.out.println("NativeInstructionSet: " + id);
		}

		return apply(Stream.of(args).map(MemoryData::getMem).toArray(RAM[]::new),
					Stream.of(args).mapToInt(MemoryData::getOffset).toArray(),
					Stream.of(args).mapToInt(MemoryData::getMemLength).toArray());
	}

	default Semaphore apply(RAM args[], int offsets[], int sizes[]) {
		return apply(Optional.ofNullable(Hardware.getLocalHardware().getClComputeContext())
				.map(CLComputeContext::getClQueue)
				.map(cl_command_queue::getNativePointer).orElse(-1L), args, offsets, sizes);
	}

	default Semaphore apply(long commandQueue, RAM args[], int offsets[], int sizes[]) {
		apply(commandQueue,
				Stream.of(args).mapToLong(RAM::getNativePointer).toArray(),
				offsets, sizes, args.length);
		return null;
	}

	void apply(long commandQueue, long arg[], int offset[], int size[], int count);
}
