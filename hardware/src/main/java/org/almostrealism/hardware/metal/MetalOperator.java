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
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.concurrent.Semaphore;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * {@link HardwareOperator} that executes compiled Metal compute kernels.
 *
 * <p>Wraps {@link MTLComputePipelineState} and manages threadgroup sizing,
 * argument encoding, and kernel dispatch on Metal GPU. Thread-local to avoid synchronization.</p>
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
 * @see MetalOperatorMap
 * @see MTLComputePipelineState
 * @see HardwareOperator
 */
public class MetalOperator extends HardwareOperator {
	/**
	 * Enables {@code dispatchThreadgroups} mode instead of {@code dispatchThreads} for kernel execution.
	 */
	public static boolean enableDispatchThreadgroups = false;

	private static long totalInvocations;

	private final MetalComputeContext context;
	private final MetalProgram prog;
	private final String name;

	private final int argCount;

	private MTLComputePipelineState kernel;

	/**
	 * Creates a Metal operator for executing a compiled kernel.
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
	 * Computes optimal workgroup (threadgroup) size for the kernel.
	 *
	 * <p>Finds the largest power-of-2 divisor of global work size that doesn't
	 * exceed the kernel's {@code maxTotalThreadsPerThreadgroup}. Falls back to
	 * SIMD width if {@link #enableDispatchThreadgroups} is enabled.</p>
	 *
	 * @return Workgroup size in threads
	 */
	@Override
	public int getWorkgroupSize() {
		if (kernel == null) return super.getWorkgroupSize();

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

	/**
	 * Returns workgroup dimensions for kernel dispatch.
	 *
	 * <p>If {@link #enableDispatchThreadgroups} is enabled, returns {@code [workgroupSize, 1, 1]}.
	 * Otherwise, splits workgroup size across X and Y dimensions based on SIMD width:
	 * {@code [simdWidth, workgroupSize/simdWidth, 1]}.</p>
	 *
	 * @return 3-element array of [width, height, depth] dimensions
	 */
	public int[] getWorkgroupDimensions() {
		if (enableDispatchThreadgroups) {
			return new int[] { getWorkgroupSize(), 1, 1 };
		} else {
			int simdWidth = kernel.threadExecutionWidth();
			return new int[]{simdWidth, getWorkgroupSize() / simdWidth, 1};
		}
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
	 * Executes the Metal kernel with the specified arguments.
	 *
	 * <p>Encodes kernel arguments as Metal buffers, computes threadgroup configuration,
	 * dispatches the compute command, and blocks until completion.</p>
	 *
	 * <p><strong>Thread-safety:</strong> Synchronized to ensure only one kernel
	 * executes at a time per operator instance.</p>
	 *
	 * @param args Kernel arguments (must be {@link MemoryData})
	 * @param dependsOn Optional {@link Semaphore} to wait on before execution
	 * @return Currently always returns null (TODO: return proper Semaphore)
	 * @throws UnsupportedOperationException if argument count exceeds {@link MetalCommandRunner#MAX_ARGS}
	 * @throws UnsupportedOperationException if global work size exceeds {@link Integer#MAX_VALUE}
	 * @throws RuntimeException if kernel execution fails
	 */
	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (kernel == null) {
			kernel = prog.newComputePipelineState();
		}

		long id = totalInvocations++;

		if (dependsOn != null) dependsOn.waitFor();

		MemoryData data[] = prepareArguments(argCount, args);
		if (data.length > MetalCommandRunner.MAX_ARGS) {
			throw new UnsupportedOperationException();
		}

		Future<?> run = context.getCommandRunner().submit((offset, size, queue) -> {
			recordDuration(null, () -> {
				int index = 0;
				long totalSize = 0;

				MTLCommandBuffer cmdBuf = queue.commandBuffer();
				MTLComputeCommandEncoder encoder = cmdBuf.encoder();

				encoder.setComputePipelineState(kernel);

				for (int i = 0; i < argCount; i++) {
					MetalMemory mem = (MetalMemory) data[i].getMem();
					totalSize += mem.getSize();
					encoder.setBuffer(index++, ((MetalMemory) data[i].getMem()).getMem()); // Buffer
				}

				int offsetValues[] = IntStream.range(0, argCount).map(i -> data[i].getOffset()).toArray();
				int sizeValues[] = IntStream.range(0, argCount).map(i -> data[i].getAtomicMemLength()).toArray();

				offset.setContents(offsetValues);
				size.setContents(sizeValues);

				if (enableVerboseLog) {
					log(prog.getMetadata().getDisplayName() + " (" + id + ")");
					log("\tSizes = " + Arrays.toString(sizeValues));
					log("\tOffsets = " + Arrays.toString(offsetValues));
				}

				encoder.setBuffer(index++, offset);
				encoder.setBuffer(index++, size);

				if (getGlobalWorkSize() > Integer.MAX_VALUE) {
					throw new UnsupportedOperationException();
				}

				int groupDims[] = getWorkgroupDimensions();

				if (enableDispatchThreadgroups) {
					int groupSize = groupDims[0] * groupDims[1] * groupDims[2];
					int gridDims[] = new int[]{(int) (getGlobalWorkSize() / groupSize), 1, 1};
					encoder.dispatchThreadgroups(groupDims[0], groupDims[1], groupDims[2],
							gridDims[0], gridDims[1], gridDims[2]);
				} else {
					int gridDims[] = new int[]{(int) (getGlobalWorkSize()), 1, 1};
					encoder.dispatchThreads(groupDims[0], groupDims[1], groupDims[2],
							gridDims[0], gridDims[1], gridDims[2]);
				}

				encoder.endEncoding();

				cmdBuf.commit();
				cmdBuf.waitUntilCompleted();
			});
		});

		try {
			// TODO  This should actually return a Semaphore rather than
			// TODO  blocking until the process is over
			run.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException  e) {
			throw new RuntimeException(e);
		}

		return null;
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
