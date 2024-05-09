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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.Semaphore;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

/**
 * {@link MetalOperator}s are intended to be used with {@link ThreadLocal}.
 */
public class MetalOperator extends HardwareOperator {
	public static boolean enableDispatchThreadgroups = false;

	private static long totalInvocations;

	private final MetalComputeContext context;
	private final MetalProgram prog;
	private final String name;

	private final int argCount;

	private MTLComputePipelineState kernel;

	public MetalOperator(MetalComputeContext context, MetalProgram program, String name, int argCount) {
		this.context = context;
		this.prog = program;
		this.name = name;
		this.argCount = argCount;
	}

	@Override
	public String getName() { return name; }

	@Override
	protected String getHardwareName() { return "MTL"; }

	@Override
	public OperationMetadata getMetadata() { return prog.getMetadata(); }

	@Override
	public boolean isGPU() {
		return !context.isCPU();
	}

	@Override
	protected int getArgCount() { return argCount; }

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

	public int[] getWorkgroupDimensions() {
		if (enableDispatchThreadgroups) {
			return new int[] { getWorkgroupSize(), 1, 1 };
		} else {
			int simdWidth = kernel.threadExecutionWidth();
			return new int[]{simdWidth, getWorkgroupSize() / simdWidth, 1};
		}
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return context.getDataContext().getMemoryProviders();
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

		if (dependsOn != null) dependsOn.waitFor();

		MemoryData data[] = prepareArguments(argCount, args);
		if (data.length > MetalCommandRunner.MAX_ARGS) {
			throw new UnsupportedOperationException();
		}

		int dimMasks[] = computeDimensionMasks(data);

		Future<?> run = context.getCommandRunner().submit((offset, size, dim0, queue) -> {
			recordDuration(() -> {
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
				offset.setContents(offsetValues);

				int sizeValues[] = IntStream.range(0, argCount).map(i -> data[i].getAtomicMemLength()).toArray();
				size.setContents(sizeValues);

				if (enableDimensionMasks) {
					int dim0Values[] = IntStream.range(0, argCount).map(i -> data[i].getAtomicMemLength() * dimMasks[i]).toArray();
					dim0.setContents(dim0Values);
				} else {
					int dim0Values[] = IntStream.range(0, argCount).map(i -> data[i].getAtomicMemLength()).toArray();
					dim0.setContents(dim0Values);
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

	@Override
	public boolean isDestroyed() {
		return prog == null || prog.isDestroyed();
	}

	public void destroy() {
		if (kernel != null) kernel.release();
		kernel = null;
	}
}
