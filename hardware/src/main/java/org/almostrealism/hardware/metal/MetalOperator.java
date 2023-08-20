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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Execution;
import io.almostrealism.code.Semaphore;
import io.almostrealism.relation.Factory;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.KernelWork;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Bytes;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * {@link MetalOperator}s are intended to be used with {@link ThreadLocal}.
 */
public class MetalOperator implements Execution, KernelWork, Factory<MTLComputePipelineState> {
	public static boolean enableLog;
	public static boolean enableVerboseLog;
	public static boolean enableDimensionMasks = true;
	public static boolean enableAtomicDimensionMasks = true;

	private static long totalInvocations;

	private final Supplier<MetalCommandRunner> runner;
	private final MetalProgram prog;
	private final String name;

	private final int argCount;

	private long globalWorkSize = 1;
	private long globalWorkOffset;

	private MTLComputePipelineState kernel;

	public MetalOperator(Supplier<MetalCommandRunner> runner, MetalProgram program, String name, int argCount) {
		this.runner = runner;
		this.prog = program;
		this.name = name;
		this.argCount = argCount;
	}

	// TODO  How do these kernels get released when done?
	@Override
	public MTLComputePipelineState construct() { return prog.newComputePipelineState(); }

	@Override
	public long getGlobalWorkSize() { return globalWorkSize; }
	@Override
	public void setGlobalWorkSize(long globalWorkSize) { this.globalWorkSize = globalWorkSize; }

	@Override
	public long getGlobalWorkOffset() { return globalWorkOffset; }
	@Override
	public void setGlobalWorkOffset(long globalWorkOffset) { this.globalWorkOffset = globalWorkOffset; }

	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (kernel == null) kernel = construct();

		long id = totalInvocations++;

		if (enableVerboseLog) {
			System.out.println("MTL: " + prog.getMetadata().getDisplayName() + " (" + id + ")");
		}

		int dimMasks[] = computeDimensionMasks(args);
		if (dependsOn != null) dependsOn.waitFor();

		Future<?> run = runner.get().submit((cmdBuf, encoder) -> {
			int index = 0;
			long totalSize = 0;

			encoder.setComputePipelineState(kernel);

			for (int i = 0; i < argCount; i++) {
				MetalMemory mem = (MetalMemory) ((MemoryData) args[i]).getMem();
				totalSize += mem.getSize();
				encoder.setBuffer(index++, ((MetalMemory) ((MemoryData) args[i]).getMem()).getMem()); // Buffer
			}

			/*
			for (int i = 0; i < argCount; i++) {
				encoder.setBuffer(index++, ((MemoryData) args[i]).getOffset()); // Offset
			}

			for (int i = 0; i < argCount; i++) {
				encoder.setBuffer(index++, ((MemoryData) args[i]).getAtomicMemLength()); // Size
			}

			for (int i = 0; i < argCount; i++) {
				if (enableDimensionMasks) {
					encoder.setBuffer(index++, ((MemoryData) args[i]).getAtomicMemLength() * dimMasks[i]); // Dim0
				} else {
					encoder.setBuffer(index++, ((MemoryData) args[i]).getAtomicMemLength()); // Dim0
				}
			}
			 */
		});

		if (Hardware.isAsync()) {
			// TODO  Return a Semaphore using the Future
			throw new UnsupportedOperationException();
		} else {
			try {
				run.get();
			} catch (ExecutionException | InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		return null;
	}

	protected int[] computeDimensionMasks(Object args[]) {
		int sizes[] = new int[args.length];

		for (int i = 0; i < argCount; i++) {
			if (args[i] == null) {
				throw new NullPointerException("argument " + i + " to function " + name);
			}

			if (!(args[i] instanceof MemoryData)) {
				throw new IllegalArgumentException("argument " + i + " (" +
						args[i].getClass().getSimpleName() + ") to function " +
						name + " is not a MemoryData");
			}

			if (((MemoryData) args[i]).getMem() instanceof MetalMemory == false) {
				throw new IllegalArgumentException("argument " + i + " (" +
						args[i].getClass().getSimpleName() + ") to function " +
						name + " is not associated with CLMemory");
			}

			if (args[i] instanceof MemoryBank) {
				sizes[i] = ((MemoryBank) args[i]).getCount();
			} else if (args[i] instanceof Bytes) {
				sizes[i] = ((Bytes) args[i]).getCount();
			} else {
				sizes[i] = ((MemoryData) args[i]).getMemLength();
			}
		}

		if (enableAtomicDimensionMasks && globalWorkSize == 1) {
			return IntStream.range(0, argCount).map(i -> 0).toArray();
		} else {
			if (globalWorkSize > Integer.MAX_VALUE) {
				// Is it though?
				throw new IllegalArgumentException("globalWorkSize is too large");
			}

			return IntStream.range(0, sizes.length)
					.map(i -> (sizes[i] >= globalWorkSize && sizes[i] % globalWorkSize == 0) ? 1 : 0)
					.toArray();
		}
	}

	public void destroy() {
		if (kernel != null) kernel.release();
		kernel = null;
	}

	public static void verboseLog(Runnable r) {
		boolean log = enableVerboseLog;
		enableVerboseLog = true;
		r.run();
		enableVerboseLog = log;
	}

	public static void disableDimensionMasks(Runnable r) {
		boolean masks = enableDimensionMasks;
		enableDimensionMasks = false;
		r.run();
		enableDimensionMasks = masks;
	}
}
