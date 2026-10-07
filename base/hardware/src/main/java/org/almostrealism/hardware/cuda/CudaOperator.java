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
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.KernelMemoryGuard;

import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
		return workgroupSize(getGlobalWorkSize());
	}

	/**
	 * Returns the block size for the given global work size: the largest the kernel allows,
	 * capped at {@link #maxBlockSize} and at the work size.
	 *
	 * @param globalWorkSize the number of work items
	 * @return the block size
	 */
	private int workgroupSize(long globalWorkSize) {
		long work = Math.max(1, globalWorkSize);
		int max = Math.min(maxBlockSize, prog.getFunction().getMaxThreadsPerBlock());
		return (int) Math.min(max, work);
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return context.getDataContext().getMemoryProviders();
	}

	/**
	 * Dispatches the kernel with the provided arguments and returns its completion without
	 * waiting for the kernel to finish, or for {@code dependsOn}.
	 *
	 * <p>A {@code dependsOn} that the context's {@link CudaStreamRunner} already orders after (the
	 * completion of earlier work on the same stream) needs nothing more, so the kernel is prepared
	 * and submitted now. Any other completion may still be writing an input that argument
	 * preparation would copy, so the whole dispatch is deferred until it completes, through
	 * {@link #dispatchAfter}. The global work size and offset are read here, when the dispatch is
	 * requested, because this operator is reused and they are set again for the next request,
	 * which may come before a deferred dispatch has run.</p>
	 *
	 * @param args      the arguments to pass to the kernel
	 * @param dependsOn the completion this dispatch must be ordered after, or {@code null}
	 * @return the dispatch's completion
	 */
	@Override
	public Semaphore accept(Object[] args, Semaphore dependsOn) {
		long count = getGlobalWorkSize();
		long offset = getGlobalWorkOffset();

		if (dependsOn != null && !context.getStreamRunner().ordersAfter(dependsOn)) {
			return dispatchAfter(dependsOn, args, resolved -> dispatch(resolved, null, count, offset));
		}

		return dispatch(args, dependsOn, count, offset);
	}

	/**
	 * Prepares the arguments and submits the kernel to the context's {@link CudaStreamRunner},
	 * ordered after {@code dependsOn}, which is {@code null} or a completion the runner already
	 * orders after.
	 *
	 * <p>The argument memory is protected by a {@link KernelMemoryGuard} execution reservation
	 * until the kernel has completed. The runner may still hold the submission behind earlier
	 * work that waits for a dependency of its own, for longer than that reservation's
	 * deferred-release backstop, so if it does, a scheduling lease, which never expires, is also
	 * taken over the same memory and kept until the kernel has completed. The program counts the
	 * launch as in flight for the same period (see {@link CudaProgram#beginLaunch()}), so its
	 * module is not unloaded under the kernel.</p>
	 *
	 * @param args      the arguments to pass to the kernel
	 * @param dependsOn {@code null}, or a completion the runner already orders after
	 * @param count     the global work size requested
	 * @param offset    the global work offset requested
	 * @return the dispatch's completion
	 */
	private synchronized Semaphore dispatch(Object[] args, Semaphore dependsOn, long count, long offset) {
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

		int block = workgroupSize(count);
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

		CUFunction function = prog.beginLaunch();
		KernelMemoryGuard memoryGuard = Hardware.getLocalHardware().getKernelMemoryGuard();
		KernelMemoryGuard.Reservation guard;

		try {
			guard = memoryGuard.acquire(data);
		} catch (RuntimeException | Error e) {
			prog.endLaunch();
			throw e;
		}

		AtomicReference<KernelMemoryGuard.Reservation> lease = new AtomicReference<>();

		return context.getStreamRunner().submit(getMetadata(), stream -> recordDuration(null, () -> {
			if (count > 0) {
				function.launch(stream, (int) grid, block, buffers, offsets, sizes, elementBytes, count, offset);
			}
		}), dependsOn, () -> lease.set(memoryGuard.acquireScheduled(data)), () -> {
			Destroyable.releaseAll(List.of(guard::release, () -> {
				KernelMemoryGuard.Reservation held = lease.getAndSet(null);
				if (held != null) held.release();
			}), prog::endLaunch);
			Reference.reachabilityFence(data);
			Reference.reachabilityFence(args);
		});
	}

	@Override
	public boolean isDestroyed() {
		return prog.isDestroyed();
	}
}
