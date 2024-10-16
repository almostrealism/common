/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.Execution;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.OperationWithInfo;
import io.almostrealism.profile.OperationTimingListener;
import io.almostrealism.uml.Named;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.kernel.KernelWork;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.util.List;
import java.util.stream.IntStream;

public abstract class HardwareOperator implements Execution, KernelWork, OperationInfo, Named {
	public static boolean enableLog;
	public static boolean enableVerboseLog;
	public static boolean enableKernelLog = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_LOG").orElse(false);
	public static boolean enableInstructionSetMonitoring = false;
	public static boolean enableLargeInstructionSetMonitoring = false;

	public static boolean enableDimensionMasks = true;
	public static boolean enableAtomicDimensionMasks = true;

	public static TimingMetric prepareArgumentsMetric = Hardware.console.timing("prepareArguments");
	public static TimingMetric computeDimMasksMetric = Hardware.console.timing("computeDimMasks");

	public static OperationTimingListener timingListener;
	public static long cpuCompileCount, gpuCompileCount;
	public static long cpuOpCount, gpuOpCount;
	public static long cpuOpTime, gpuOpTime;

	private long globalWorkSize = 1;
	private long globalWorkOffset;

	@Override
	public long getGlobalWorkSize() { return globalWorkSize; }
	@Override
	public void setGlobalWorkSize(long globalWorkSize) { this.globalWorkSize = globalWorkSize; }

	@Override
	public long getGlobalWorkOffset() { return globalWorkOffset; }
	@Override
	public void setGlobalWorkOffset(long globalWorkOffset) { this.globalWorkOffset = globalWorkOffset; }

	public abstract boolean isGPU();

	public abstract List<MemoryProvider<? extends Memory>> getSupportedMemory();

	protected abstract String getHardwareName();

	protected abstract int getArgCount();

	protected MemoryData[] prepareArguments(int argCount, Object[] args) {
		long start = System.nanoTime();

		MemoryData data[] = new MemoryData[argCount];

		for (int i = 0; i < argCount; i++) {
			if (args[i] == null) {
				throw new NullPointerException("argument " + i + " to function " + getName());
			}

			if (!(args[i] instanceof MemoryData)) {
				throw new IllegalArgumentException("argument " + i + " (" +
						args[i].getClass().getSimpleName() + ") to function " +
						getName() + " is not a MemoryData");
			}

			data[i] = (MemoryData) args[i];
			reassignMemory(data[i]);
		}

		prepareArgumentsMetric.addEntry(System.nanoTime() - start);
		return data;
	}

	private void reassignMemory(MemoryData data) {
		List<MemoryProvider<? extends Memory>> supported = getSupportedMemory();
		if (supported.isEmpty())
			throw new RuntimeException("No memory providers are supported by " + getName());

		MemoryProvider<Memory> provider = data.getMem().getProvider();

		if (supported.contains(provider)) {
			// Memory is supported by the operation,
			// and will not have to be moved
			return;
		}

		String from = provider.getName();
		String to = supported.get(0).getName();
		OperationMetadata metadata =
				new OperationMetadata("reassignMemory_" + from + "_" + to,
				"Reassign Memory " + from + " -> " + to);

		recordDuration(new OperationWithInfo.RunnableWithInfo(metadata,
				() -> {
					// Memory is not supported by the operation,
					// and the entire reservation that it is part
					// of will have to be reallocated
					MemoryData root = data.getRootDelegate();
					int size = root.getMemLength() * provider.getNumberSize();

					if (enableVerboseLog)
						System.out.println("Hardware[" + getHardwareName() + "]: Reallocating " + size + " bytes");

					root.reallocate(supported.get(0));
				}), false);

	}

	protected int[] computeDimensionMasks(Object args[]) {
		long start = System.nanoTime();

		try {
			long sizes[] = new long[args.length];

			for (int i = 0; i < getArgCount(); i++) {
				if (args[i] == null) {
					throw new NullPointerException("argument " + i + " to function " + getName());
				}

				if (!(args[i] instanceof MemoryData)) {
					throw new IllegalArgumentException("argument " + i + " (" +
							args[i].getClass().getSimpleName() + ") to function " +
							getName() + " is not a MemoryData");
				}

				if (args[i] instanceof MemoryBank) {
					sizes[i] = ((MemoryBank) args[i]).getCountLong();
				} else if (args[i] instanceof Bytes) {
					sizes[i] = ((Bytes) args[i]).getCountLong();
				} else {
					sizes[i] = ((MemoryData) args[i]).getMemLength();
				}
			}

			if (enableAtomicDimensionMasks && getGlobalWorkSize() == 1) {
				return IntStream.range(0, getArgCount()).map(i -> 0).toArray();
			} else {
				if (getGlobalWorkSize() > Integer.MAX_VALUE) {
					// Is it though?
					throw new IllegalArgumentException("globalWorkSize is too large");
				}

				return IntStream.range(0, sizes.length)
						.map(i -> (sizes[i] >= getGlobalWorkSize() && sizes[i] % getGlobalWorkSize() == 0) ? 1 : 0)
						.toArray();
			}
		} finally {
			computeDimMasksMetric.addEntry(System.nanoTime() - start);
		}
	}

	protected void recordDuration(Runnable r) {
		recordDuration(r, true);
	}

	protected void recordDuration(Runnable r, boolean countOp) {
		long duration = -1;

		if (timingListener == null) {
			r.run();
		} else if (r instanceof OperationInfo) {
			duration = timingListener.recordDuration(r);
		} else {
			duration = timingListener.recordDuration(OperationWithInfo.RunnableWithInfo.of(getMetadata(), r));
		}

		if (countOp && duration > 0) {
			if (isGPU()) {
				gpuOpCount++;
				gpuOpTime += duration;
			} else {
				cpuOpCount++;
				cpuOpTime += duration;
			}
		}
	}

	@Override
	public String describe() {
		return getMetadata().getDisplayName() + " (" + getGlobalWorkSize() + "x)";
	}

	public static void recordCompilation(boolean gpu) {
		if (gpu) {
			gpuCompileCount++;
		} else {
			cpuCompileCount++;
		}
	}

	public static void verboseLog(Runnable r) {
		boolean log = enableVerboseLog;
		boolean compilerLog = NativeCompiler.enableVerbose;

		try {
			enableVerboseLog = true;
			NativeCompiler.enableVerbose = true;
			r.run();
		} finally {
			enableVerboseLog = log;
			NativeCompiler.enableVerbose = compilerLog;
		}
	}

	public static void disableDimensionMasks(Runnable r) {
		boolean masks = enableDimensionMasks;

		try {
			enableDimensionMasks = false;
			r.run();
		} finally {
			enableDimensionMasks = masks;
		}
	}
}
