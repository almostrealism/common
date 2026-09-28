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

package org.almostrealism.hardware.cuda;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.KernelMemoryGuard;

import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.List;

/**
 * Dispatches the kernel of a {@link CudaProgram} with a particular set of arguments.
 *
 * <p>Each argument is passed as the {@link CUDeviceBuffer} backing it together with its
 * element offset and size, in the order {@link CudaLanguageOperations} declares them. The
 * kernel is launched with a one-dimensional grid covering the global work size; the kernel
 * itself discards the threads of the last block that fall beyond it.</p>
 */
public class CudaOperator extends HardwareOperator {
	/** The largest block size used, regardless of what the kernel would allow. */
	public static int maxBlockSize = 256;

	/** The number of dispatches made by all CUDA operators. */
	private static long totalInvocations;

	/** The context the kernel belongs to. */
	private final CudaComputeContext context;

	/** The program containing the kernel. */
	private final CudaProgram prog;

	/** The operator name. */
	private final String name;

	/** The number of arguments the kernel takes. */
	private final int argCount;

	/**
	 * Creates an operator.
	 *
	 * @param context  the context the kernel belongs to
	 * @param program  the program containing the kernel
	 * @param name     the operator name
	 * @param argCount the number of arguments the kernel takes
	 */
	public CudaOperator(CudaComputeContext context, CudaProgram program, String name, int argCount) {
		this.context = context;
		this.prog = program;
		this.name = name;
		this.argCount = argCount;
	}

	@Override
	public String getName() { return name + "(execution " + getId() + ")"; }

	@Override
	protected String getHardwareName() { return "CUDA"; }

	@Override
	public OperationMetadata getMetadata() { return prog.getMetadata(); }

	@Override
	public boolean isGPU() { return true; }

	@Override
	protected int getArgCount() { return argCount; }

	/**
	 * Returns the block size: the largest the kernel allows, capped at {@link #maxBlockSize}
	 * and at the global work size.
	 */
	@Override
	public int getWorkgroupSize() {
		long work = Math.max(1, getGlobalWorkSize());
		int max = Math.min(maxBlockSize, prog.getFunction().getMaxThreadsPerBlock());
		return (int) Math.min(max, work);
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return context.getDataContext().getMemoryProviders();
	}

	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		CUFunction function = prog.getFunction();
		long id = totalInvocations++;

		MemoryData[] data = prepareArguments(argCount, args);

		CUDeviceBuffer[] buffers = new CUDeviceBuffer[argCount];
		int[] offsets = new int[argCount];
		int[] sizes = new int[argCount];

		for (int i = 0; i < argCount; i++) {
			buffers[i] = ((CudaMemory) data[i].getMem()).getBuffer();
			offsets[i] = data[i].getOffset();
			sizes[i] = data[i].getAtomicMemLength();
		}

		long count = getGlobalWorkSize();
		long offset = getGlobalWorkOffset();
		int block = getWorkgroupSize();
		int elementBytes = context.getDataContext().getPrecision().bytes();
		long grid = CUFunction.gridSize(count, block);

		if (grid > context.getMaxGridSize()) {
			throw new HardwareException("Global work size " + count + " for " + getName() +
					" exceeds the maximum grid size of the device");
		}

		if (enableVerboseLog) {
			log(prog.getMetadata().getDisplayName() + " (" + id + ")");
			log("\tSizes = " + Arrays.toString(sizes));
			log("\tOffsets = " + Arrays.toString(offsets));
			log("\tGrid = " + grid + " x " + block + " for " + count + " work items");
		}

		KernelMemoryGuard.Reservation guard = KernelMemoryGuard.acquireFor(data);

		return context.getStreamRunner().submit(getMetadata(), stream -> recordDuration(null, () -> {
			if (count > 0) {
				function.launch(stream, (int) grid, block, buffers, offsets, sizes, elementBytes, count, offset);
			}
		}), dependsOn, () -> {
			KernelMemoryGuard.releaseFor(guard);
			Reference.reachabilityFence(data);
			Reference.reachabilityFence(args);
		});
	}

	@Override
	public boolean isDestroyed() {
		return prog.isDestroyed();
	}
}
