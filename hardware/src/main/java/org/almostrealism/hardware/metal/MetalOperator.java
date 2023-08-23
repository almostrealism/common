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
public class MetalOperator implements Execution, KernelWork {
	public static boolean enableLog;
	public static boolean enableVerboseLog;
	public static boolean enableDimensionMasks = true;
	public static boolean enableAtomicDimensionMasks = true;
	public static boolean enableDispatchThreadgroups = false;

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

	@Override
	public long getGlobalWorkSize() { return globalWorkSize; }
	@Override
	public void setGlobalWorkSize(long globalWorkSize) { this.globalWorkSize = globalWorkSize; }

	@Override
	public long getGlobalWorkOffset() { return globalWorkOffset; }
	@Override
	public void setGlobalWorkOffset(long globalWorkOffset) { this.globalWorkOffset = globalWorkOffset; }

	@Override
	public int getWorkgroupSize() {
		if (kernel == null) return KernelWork.super.getWorkgroupSize();

		int simdWidth = kernel.threadExecutionWidth();
		if (enableDispatchThreadgroups && getGlobalWorkSize() % simdWidth == 0) {
			return simdWidth;
		}

		int max = kernel.maxTotalThreadsPerThreadgroup();

		while (max > 1) {
			if (getGlobalWorkSize() % max == 0) {
				return max;
			}

			max = max / 2;
		}

		return 1;
	}

	public int[] getWorkgroupDimensions() {
		if (enableDispatchThreadgroups) {
			return new int[] { getWorkgroupSize(), 1, 1 };
		} else {
			int simdWidth = kernel.threadExecutionWidth();
			return new int[]{simdWidth, getWorkgroupSize() / simdWidth, 1};
		}
	}

	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (kernel == null) {
			kernel = prog.newComputePipelineState();
		}

		long id = totalInvocations++;

		if (enableVerboseLog) {
			System.out.println("MTL: " + prog.getMetadata().getDisplayName() + " (" + id + ")");
		}

		int dimMasks[] = computeDimensionMasks(args);
		if (dependsOn != null) dependsOn.waitFor();

		Future<?> run = runner.get().submit((queue) -> {
			int index = 0;
			long totalSize = 0;

			MTLCommandBuffer cmdBuf = queue.commandBuffer();
			MTLComputeCommandEncoder encoder = cmdBuf.encoder();

			encoder.setComputePipelineState(kernel);

			for (int i = 0; i < argCount; i++) {
				MetalMemory mem = (MetalMemory) ((MemoryData) args[i]).getMem();
				totalSize += mem.getSize();
				encoder.setBuffer(index++, ((MetalMemory) ((MemoryData) args[i]).getMem()).getMem()); // Buffer
			}

			// TODO  Set offset, size, and dim0 buffers

			int offsetValues[] = IntStream.range(0, argCount).map(i -> ((MemoryData) args[i]).getOffset()).toArray();
			MTLBuffer offset = prog.getDevice().newIntBuffer32(offsetValues);

			int sizeValues[] = IntStream.range(0, argCount).map(i -> ((MemoryData) args[i]).getAtomicMemLength()).toArray();
			MTLBuffer size = prog.getDevice().newIntBuffer32(sizeValues);

			MTLBuffer dim0;

			if (enableDimensionMasks) {
				int dim0Values[] = IntStream.range(0, argCount).map(i -> ((MemoryData) args[i]).getAtomicMemLength() * dimMasks[i]).toArray();
				dim0 = prog.getDevice().newIntBuffer32(dim0Values);
			} else {
				int dim0Values[] = IntStream.range(0, argCount).map(i -> ((MemoryData) args[i]).getAtomicMemLength()).toArray();
				dim0 = prog.getDevice().newIntBuffer32(dim0Values);
			}

			encoder.setBuffer(index++, offset);
			encoder.setBuffer(index++, size);
			encoder.setBuffer(index++, dim0);

			if (getGlobalWorkSize() > Integer.MAX_VALUE) {
				throw new UnsupportedOperationException();
			}

			int groupDims[] = getWorkgroupDimensions();

			if (enableDispatchThreadgroups) {
				int groupSize = groupDims[0] * groupDims[1] * groupDims[2];
				int gridDims[] = new int[] { (int) (getGlobalWorkSize() / groupSize), 1, 1 };
				encoder.dispatchThreadgroups(groupDims[0], groupDims[1], groupDims[2],
						gridDims[0], gridDims[1], gridDims[2]);
			} else {
				int gridDims[] = new int[] { (int) (getGlobalWorkSize()), 1, 1 };
				encoder.dispatchThreads(groupDims[0], groupDims[1], groupDims[2],
										gridDims[0], gridDims[1], gridDims[2]);
			}

			encoder.endEncoding();

			cmdBuf.commit();
			cmdBuf.waitUntilCompleted();

			offset.release();
			size.release();
			dim0.release();
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
