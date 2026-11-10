/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.streams.Semaphore;
import io.almostrealism.profile.OperationMetadata;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.KernelMemoryGuard;

import java.lang.ref.Reference;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * {@link HardwareOperator} that executes compiled Metal compute kernels.
 *
 * <p>Wraps {@link MTLComputePipelineState} and manages threadgroup sizing,
 * argument encoding, and kernel dispatch on Metal GPU. An operator may be shared across
 * threads (its {@link MetalComputeContext} is shared per data context), so
 * {@link #accept(Object[], io.almostrealism.streams.Semaphore)} is synchronized and
 * the actual encoding is serialized by the context's {@link MetalCommandRunner}.</p>
 *
 * <h2>Threadgroup Sizing</h2>
 *
 * <pre>{@code
 * // Automatic sizing based on kernel limits
 * int workgroup = operator.getWorkgroupSize();
 * // Uses kernel.maxTotalThreadsPerThreadgroup() and threadExecutionWidth()
 *
 * int[] dimensions = operator.getWorkgroupDimensions();
 * // Returns [simdWidth, workgroupSize/simdWidth, 1]
 * }</pre>
 *
 * <h2>Lanes per Work Item</h2>
 *
 * <p>A kernel whose root scope assigns several lanes to each work item (see
 * {@link io.almostrealism.scope.Scope#getLaneCount()}) is dispatched with that many threads for
 * every work item, one SIMD group each, in one-dimensional threadgroups of whole work items (see
 * {@link #LANED_THREADGROUP_THREADS}). The lane count is the one the {@link MetalProgram} was
 * rendered for ({@link MetalProgram#getLaneCount()}), the SIMD width of the device
 * ({@link MetalComputeContext#getLaneCount()}); the program refuses a pipeline whose execution
 * width differs, since its SIMD groups would not match the work items.</p>
 *
 * @see MetalOperatorMap
 * @see MTLComputePipelineState
 * @see HardwareOperator
 */
public class MetalOperator extends HardwareOperator {
	/**
	 * Enables {@code dispatchThreadgroups} mode instead of {@code dispatchThreads} for kernel execution.
	 */
	public static boolean enableDispatchThreadgroups = false;

	/**
	 * Threads in each threadgroup of a kernel that assigns several lanes to each work item,
	 * when the kernel allows that many: eight work items of 32 lanes. A laned kernel launches a
	 * grid whose threads are the lanes of its work items, so whole work items are grouped
	 * together; for the dot products of a matrix-vector product with some thousands of rows this
	 * size ran faster than any narrower or wider threadgroup.
	 */
	public static final int LANED_THREADGROUP_THREADS = 256;

	/** Cumulative count of all Metal kernel invocations across all operator instances. */
	private static long totalInvocations;

	/** The compute context that owns this operator and provides the command runner. */
	private final MetalComputeContext context;
	/** The compiled Metal program containing the kernel function for this operator. */
	private final MetalProgram prog;
	/** Display name for this operator, used in logging and profiling output. */
	private final String name;

	/** Number of {@link MTLBuffer} arguments expected by the kernel function. */
	private final int argCount;

	/** The Metal compute pipeline state wrapping the compiled kernel function. */
	private MTLComputePipelineState kernel;

	/**
	 * Creates a Metal operator for executing a compiled kernel, with as many threads for each
	 * work item as the program's kernel assigns lanes to it (see {@link MetalProgram#getLaneCount()}).
	 *
	 * @param context The {@link MetalComputeContext} providing execution environment
	 * @param program The compiled {@link MetalProgram} containing the kernel function
	 * @param name Display name for this operator
	 * @param argCount Number of buffer arguments expected by the kernel
	 */
	public MetalOperator(MetalComputeContext context, MetalProgram program, String name, int argCount) {
		this.context = context;
		this.prog = program;
		this.name = name;
		this.argCount = argCount;
	}

	/**
	 * Returns the number of threads a dispatch launches: one for each lane of every work item.
	 *
	 * @return the global work size times the lanes the program's kernel assigns to each work item
	 */
	protected long getThreadCount() {
		return getGlobalWorkSize() * prog.getLaneCount();
	}

	/**
	 * Returns a display name combining the operator name and execution ID.
	 *
	 * @return Name in format "name(execution N)"
	 */
	@Override
	public String getName() { return name +  "(execution " + getId() + ")"; }

	/**
	 * Returns the hardware backend identifier.
	 *
	 * @return "MTL" for Metal backend
	 */
	@Override
	protected String getHardwareName() { return "MTL"; }

	/**
	 * Returns metadata about this operation.
	 *
	 * @return {@link OperationMetadata} from the underlying program
	 */
	@Override
	public OperationMetadata getMetadata() { return prog.getMetadata(); }

	/**
	 * Checks if this operator executes on GPU hardware.
	 *
	 * @return True unless context is explicitly CPU-only
	 */
	@Override
	public boolean isGPU() {
		return !context.isCPU();
	}

	/**
	 * Returns the number of buffer arguments required by the kernel.
	 *
	 * @return Kernel argument count
	 */
	@Override
	protected int getArgCount() { return argCount; }

	/**
	 * Refuses every offset but zero. A Metal grid always starts at the first thread, and the
	 * kernel indexes its work by {@code thread_position_in_grid} alone, so a range that starts
	 * anywhere else cannot be dispatched; it is refused here rather than dispatched from zero
	 * (see {@link io.almostrealism.kernel.KernelWork}).
	 *
	 * @param globalWorkOffset the starting index of the work range
	 * @throws UnsupportedOperationException if {@code globalWorkOffset} is not zero
	 */
	@Override
	public void setGlobalWorkOffset(long globalWorkOffset) {
		if (globalWorkOffset != 0) {
			throw new UnsupportedOperationException(
					"A Metal grid starts at the first work item, not item " + globalWorkOffset);
		}

		super.setGlobalWorkOffset(globalWorkOffset);
	}

	/**
	 * Computes optimal workgroup (threadgroup) size for the kernel.
	 *
	 * <p>Finds the largest power-of-2 divisor of the {@link #getThreadCount() thread count} that
	 * doesn't exceed the kernel's {@code maxTotalThreadsPerThreadgroup}. Falls back to
	 * SIMD width if {@link #enableDispatchThreadgroups} is enabled. The lanes of a work item are
	 * a power of two, the SIMD width, so a threadgroup always holds whole work items.</p>
	 *
	 * @return Workgroup size in threads
	 */
	@Override
	public int getWorkgroupSize() {
		if (kernel == null) return super.getWorkgroupSize();

		int simdWidth = kernel.threadExecutionWidth();
		if (enableDispatchThreadgroups && getThreadCount() % simdWidth == 0) {
			return simdWidth;
		}

		int max = kernel.maxTotalThreadsPerThreadgroup();

		while (max > 1) {
			if (getThreadCount() % max == 0) {
				return max;
			}

			max = max / 2;
		}

		return 1;
	}

	/**
	 * Returns workgroup dimensions for kernel dispatch.
	 *
	 * <p>If {@link #enableDispatchThreadgroups} is enabled, returns {@code [workgroupSize, 1, 1]}.
	 * Otherwise, splits workgroup size across X and Y dimensions based on SIMD width:
	 * {@code [simdWidth, workgroupSize/simdWidth, 1]}. A workgroup narrower than one SIMD group
	 * (which {@link #getWorkgroupSize()} produces for small work sizes that are not multiples of
	 * the SIMD width, such as 144 or 216) cannot be split this way: its height would be zero,
	 * which is not a valid threadgroup, so it is dispatched one-dimensional as
	 * {@code [workgroupSize, 1, 1]} instead.</p>
	 *
	 * <p>A kernel that assigns several lanes to each work item is dispatched with a
	 * one-dimensional threadgroup of whole work items, {@link #LANED_THREADGROUP_THREADS} threads
	 * when the kernel allows that many and the grid is that large; the grid's last threadgroup may
	 * hold fewer work items, but never part of one, since the grid is a whole number of work
	 * items.</p>
	 *
	 * @return 3-element array of [width, height, depth] dimensions
	 */
	public int[] getWorkgroupDimensions() {
		int lanes = prog.getLaneCount();

		if (lanes > 1 && !enableDispatchThreadgroups) {
			int wholeWorkItems = kernel.maxTotalThreadsPerThreadgroup() / lanes * lanes;
			if (wholeWorkItems == 0) {
				throw new HardwareException(getName() + " assigns " + lanes +
						" lanes to each work item, more than a threadgroup of it can hold");
			}

			int width = Math.max(lanes,
					Math.min(LANED_THREADGROUP_THREADS, wholeWorkItems) / lanes * lanes);
			return new int[] { (int) Math.min(getThreadCount(), width), 1, 1 };
		}

		int workgroupSize = getWorkgroupSize();

		if (enableDispatchThreadgroups) {
			return new int[] { workgroupSize, 1, 1 };
		}

		return splitWorkgroupDimensions(workgroupSize, kernel.threadExecutionWidth());
	}

	/**
	 * Splits a threadgroup of {@code workgroupSize} threads into {@code [width, height, 1]}
	 * dimensions for a {@code dispatchThreads} launch, using the kernel's SIMD group width.
	 *
	 * <p>The intent is a threadgroup one SIMD group wide and {@code workgroupSize / simdWidth}
	 * high. That split is only valid when the workgroup spans at least one full SIMD group; for a
	 * smaller workgroup &mdash; which {@link #getWorkgroupSize()} produces for small work sizes that
	 * are not multiples of the SIMD width, such as a global work size of 144 or 216 &mdash; the
	 * height would round to zero, which is not a valid threadgroup and on Metal leaves the
	 * dispatched region partly unwritten (read later as uninitialised memory). Such a workgroup is
	 * therefore dispatched one-dimensional as {@code [workgroupSize, 1, 1]} instead. The returned
	 * dimensions always have a non-zero product equal to {@code workgroupSize} when
	 * {@code simdWidth} divides it, and never contain a zero.</p>
	 *
	 * @param workgroupSize total threads per threadgroup (from {@link #getWorkgroupSize()})
	 * @param simdWidth     the kernel's SIMD group width ({@code threadExecutionWidth})
	 * @return 3-element array of {@code [width, height, depth]} threadgroup dimensions
	 */
	public static int[] splitWorkgroupDimensions(int workgroupSize, int simdWidth) {
		if (workgroupSize < simdWidth) {
			return new int[] { workgroupSize, 1, 1 };
		}

		return new int[] { simdWidth, workgroupSize / simdWidth, 1 };
	}

	/**
	 * Returns the memory providers supported by this operator.
	 *
	 * @return List of {@link MemoryProvider}s from the data context
	 */
	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return context.getDataContext().getMemoryProviders();
	}

	/**
	 * Encodes this kernel into the {@link MetalCommandRunner} of its {@link MetalComputeContext}
	 * and returns the dispatch's completion without waiting for the kernel to run.
	 *
	 * <p>The runner orders the dispatch after {@code dependsOn}: after a completion of the same
	 * runner on the GPU, and after any other completion through a bridge that does not block the
	 * calling thread (see {@link MetalCommandRunner#submit}). The memory the arguments refer to is
	 * reserved until the command buffer the dispatch is encoded into has finished on the GPU.</p>
	 *
	 * <p>{@link #prepareArguments} moves every argument into {@link #getSupportedMemory()}, which
	 * for a {@link MetalDataContext} is its {@link MetalMemoryProvider} alone, so every argument's
	 * memory is a {@link MetalMemory}. The buffer of each argument is bound at the argument's index,
	 * and the offsets and sizes of the arguments are bound inline at the two indices after the
	 * last argument, the layout the generated kernel declares. Metal's buffer argument table has
	 * 31 entries, so a kernel takes at most 29 arguments; one that declares more fails when its
	 * {@link MetalProgram} is compiled, before an operator exists for it.</p>
	 *
	 * <p>A global work size this operator cannot dispatch is refused before anything is reserved
	 * or encoded, so the runner's open command buffer is left as it was. A range that does not
	 * start at the first work item never reaches a dispatch: it is refused when it is set (see
	 * {@link #setGlobalWorkOffset(long)}).</p>
	 *
	 * <p><strong>Thread-safety:</strong> synchronized, so dispatches through one operator are
	 * encoded one at a time; the kernels themselves run on the GPU after this returns.</p>
	 *
	 * @param args      the kernel arguments, each a {@link MemoryData}
	 * @param dependsOn the completion this dispatch must be ordered after, or {@code null}
	 * @return the dispatch's completion
	 * @throws UnsupportedOperationException if the threads of the global work size, one for each
	 *         lane of every work item, exceed the largest grid a Metal dispatch can describe
	 */
	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		// Compared by division so that a work size whose thread count overflows a long is refused too
		if (getGlobalWorkSize() > Integer.MAX_VALUE / prog.getLaneCount()) {
			throw new UnsupportedOperationException("Global work size " + getGlobalWorkSize() +
					(prog.getLaneCount() > 1 ? " of " + prog.getLaneCount() + " lanes each" : "") +
					" for " + getName() + " exceeds the largest grid a Metal dispatch can describe");
		}

		if (kernel == null) {
			kernel = prog.newComputePipelineState();
		}

		long id = totalInvocations++;

		MemoryData data[] = prepareArguments(argCount, args);
		MetalCommandRunner runner = context.getCommandRunner();

		KernelMemoryGuard.Reservation guard = Hardware.getLocalHardware().getKernelMemoryGuard().acquire(data);

		// Encode this kernel into the runner's command buffer, ordered after dependsOn (the
		// runner handles both same-runner and foreign dependencies without blocking here). The
		// runner returns this dispatch's completion semaphore; the onComplete callback releases
		// the memory the kernel referenced only after the GPU has finished that buffer.
		return runner.submit(getMetadata(), cmdBuf -> {
			recordDuration(null, () -> {
				int index = 0;

				MTLComputeCommandEncoder encoder = cmdBuf.encoder();

				encoder.setComputePipelineState(kernel);

				for (int i = 0; i < argCount; i++) {
					encoder.setBuffer(index++, ((MetalMemory) data[i].getMem()).getMem());
				}

				int offsetValues[] = IntStream.range(0, argCount).map(i -> data[i].getOffset()).toArray();
				int sizeValues[] = IntStream.range(0, argCount).map(i -> data[i].getAtomicMemLength()).toArray();

				if (enableVerboseLog) {
					log(prog.getMetadata().getDisplayName() + " (" + id + ")");
					log("\tSizes = " + Arrays.toString(sizeValues));
					log("\tOffsets = " + Arrays.toString(offsetValues));

					StringBuilder desc = new StringBuilder();
					for (int i = 0; i < argCount; i++) {
						if (desc.length() > 0) desc.append(",");
						desc.append(data[i].getMem().getClass().getSimpleName())
								.append("@").append(System.identityHashCode(data[i].getMem()))
								.append("+").append(data[i].getOffset())
								.append("x").append(data[i].getMemLength())
								.append("=").append(data[i].toDouble(0));
					}

					log("\tArgs = " + desc);
				}

				// Inline the per-dispatch offset/size arrays into the command (Metal copies them at
				// encode time) so batched commands do not share mutable argument buffers.
				encoder.setBytes(index++, offsetValues);
				encoder.setBytes(index, sizeValues);

				int groupDims[] = getWorkgroupDimensions();

				if (enableDispatchThreadgroups) {
					int groupSize = groupDims[0] * groupDims[1] * groupDims[2];
					int gridDims[] = new int[]{(int) (getThreadCount() / groupSize), 1, 1};
					encoder.dispatchThreadgroups(groupDims[0], groupDims[1], groupDims[2],
							gridDims[0], gridDims[1], gridDims[2]);
				} else {
					int gridDims[] = new int[]{(int) (getThreadCount()), 1, 1};
					encoder.dispatchThreads(groupDims[0], groupDims[1], groupDims[2],
							gridDims[0], gridDims[1], gridDims[2]);
				}

				encoder.endEncoding();
			});
		}, dependsOn, () -> {
			guard.release();
			Reference.reachabilityFence(data);
			Reference.reachabilityFence(args);
		});
	}

	/**
	 * Checks if this operator has been destroyed.
	 *
	 * @return True if the underlying program is null or destroyed
	 */
	@Override
	public boolean isDestroyed() {
		return prog == null || prog.isDestroyed();
	}

	/**
	 * Destroys this operator and releases the compute pipeline state.
	 *
	 * <p>Frees Metal resources associated with the kernel. After calling destroy,
	 * this operator cannot be used for execution.</p>
	 */
	public void destroy() {
		if (kernel != null) kernel.release();
		kernel = null;
	}
}
