/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Execution;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.OperationMetadata;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.cl.CLComputeContext;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.jocl.cl_command_queue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public interface NativeInstructionSet extends InstructionSet, ConsoleFeatures {
	default String getFunctionName() {
		return "Java_" +
				getClass().getName().replaceAll("\\.", "_") +
				"_apply";
	}

	ComputeContext<MemoryData> getComputeContext();

	void setComputeContext(ComputeContext<MemoryData> context);

	OperationMetadata getMetadata();

	void setMetadata(OperationMetadata metadata);

	int getParallelism();

	void setParallelism(int parallelism);

	@Override
	default Execution get(String function, int argCount) {
		return new NativeExecution(this, argCount);
	}

	@Override
	default boolean isDestroyed() {
		return false;
	}

	@Override
	default void destroy() { }

	default void apply(long idx, long kernelSize, int[] dim0, MemoryData... args) {
		long id = NativeComputeContext.totalInvocations++;

		if (NativeComputeContext.enableVerbose && (id + 1) % 100000 == 0) {
			System.out.println("NativeInstructionSet: " + id);
		}

		if (idx > Integer.MAX_VALUE) {
			throw new UnsupportedOperationException();
		}

		int i = (int) idx;

		apply(Stream.of(args).map(MemoryData::getMem).toArray(RAM[]::new),
					Stream.of(args).mapToInt(MemoryData::getOffset).toArray(),
					Stream.of(args).mapToInt(MemoryData::getAtomicMemLength).toArray(),
					dim0, i, kernelSize);
	}

	default void apply(RAM[] args, int[] offsets, int[] sizes, int[] dim0, int globalId, long kernelSize) {
		int bytes = getComputeContext().getDataContext().getPrecision().bytes();

		for (int i = 0; i < args.length; i++) {
			if (args[i] == null) {
				throw new NullPointerException("Argument " + i + " is null");
			}

//			TODO  This is useful validation, but it can prevent the execution of operations
//			TODO  which depart from expected sizing via the use of TraversalOrdering
//			if (bytes * (globalId * dim0[i] + offsets[i]) > args[i].getSize()) {
//				throw new IllegalArgumentException("Positions in argument " + i + " will run beyond its size");
//			}
		}

		apply(Optional.ofNullable(getComputeContext())
					.map(ComputeContext::getDataContext)
					.map(DataContext::getComputeContexts)
					.stream()
					.flatMap(List::stream)
					.filter(CLComputeContext.class::isInstance)
					.map(CLComputeContext.class::cast)
					.map(CLComputeContext::getClQueue)
					.map(cl_command_queue::getNativePointer).findFirst().orElse(-1L),
				args, offsets, sizes, dim0, globalId, kernelSize);
	}

	default void apply(long commandQueue, RAM[] args, int[] offsets, int[] sizes, int[] dim0, int globalId, long kernelSize) {
		apply(commandQueue,
				Stream.of(args).mapToLong(RAM::getContentPointer).toArray(),
				offsets, sizes, dim0, args.length, globalId, kernelSize);
	}

	void apply(long commandQueue, long[] arg, int[] offset, int[] size, int[] dim0, int count, int globalId, long kernelSize);

	@Override
	default Console console() { return Hardware.console; }
}
