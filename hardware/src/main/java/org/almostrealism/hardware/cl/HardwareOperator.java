/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Execution;
import io.almostrealism.code.Semaphore;
import io.almostrealism.relation.Factory;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.*;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * {@link HardwareOperator}s are intended to be used with {@link ThreadLocal}.
 *
 * @param <T> Return type
 */
public class HardwareOperator<T extends MemoryData> implements Execution, Factory<cl_kernel> {
	public static boolean enableLog;
	public static boolean enableVerboseLog;
	public static boolean enableDimensionMasks = true;
	public static boolean enableAtomicDimensionMasks = true;

	private static long totalInvocations;

	private final CLProgram prog;
	private final String name;

	private final Object argCache[];
	private final int argCount;

	private long globalWorkSize = 1;
	private long globalWorkOffset;

	private cl_kernel kernel;

	private BiFunction<String, CLException, HardwareException> exceptionProcessor;
	private Consumer<RunData> profile;

	public HardwareOperator(CLProgram program, String name, int argCount, Consumer<RunData> profile,
							BiFunction<String, CLException, HardwareException> exceptionProcessor) {
		this.prog = program;
		this.name = name;
		this.argCache = new Object[argCount];
		this.argCount = argCount;
		this.profile = profile;
		this.exceptionProcessor = exceptionProcessor;
	}

	// TODO  How do these kernels get released when done?
	@Override
	public cl_kernel construct() {
		try {
			return CL.clCreateKernel(prog.getProgram(), name, null);
		} catch (CLException e) {
			throw exceptionProcessor == null ? e : exceptionProcessor.apply(name, e);
		}
	}

	public long getGlobalWorkSize() { return globalWorkSize; }

	public void setGlobalWorkSize(long globalWorkSize) { this.globalWorkSize = globalWorkSize; }

	public long getGlobalWorkOffset() { return globalWorkOffset; }

	public void setGlobalWorkOffset(long globalWorkOffset) { this.globalWorkOffset = globalWorkOffset; }

	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (kernel == null) kernel = construct();

		int index = 0;
		long id = totalInvocations++;

		if (enableVerboseLog) {
			System.out.println("CL: " + prog.getMetadata().getDisplayName() + " (" + id + ")");
		}

		long totalSize = 0;

		try {
			int dimMasks[] = computeDimensionMasks(args);

			for (int i = 0; i < argCount; i++) {
				if (args[i] != argCache[i]) {
					CLMemory mem = (CLMemory) ((MemoryData) args[i]).getMem();
					totalSize += mem.getSize();
					CL.clSetKernelArg(kernel, index++, Sizeof.cl_mem, Pointer.to(((CLMemory) ((MemoryData) args[i]).getMem()).getMem()));
				} else {
					index++;
				}
			}

			for (int i = 0; i < argCount; i++) {
				if (args[i] != argCache[i]) {
					CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
							Pointer.to(new int[]{((MemoryData) args[i]).getOffset()})); // Offset
				} else {
					index++;
				}
			}

			for (int i = 0; i < argCount; i++) {
				if (args[i] != argCache[i]) {
					CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
							Pointer.to(new int[]{((MemoryData) args[i]).getAtomicMemLength()})); // Size
				} else {
					index++;
				}
			}

			for (int i = 0; i < argCount; i++) {
				if (args[i] != argCache[i]) {
					if (enableDimensionMasks) {
						CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
								Pointer.to(new int[]{((MemoryData) args[i]).getAtomicMemLength() * dimMasks[i]})); // Dim0
					} else {
						CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
								Pointer.to(new int[]{((MemoryData) args[i]).getAtomicMemLength()})); // Dim0
					}
				} else {
					index++;
				}
			}

			for (int i = 0; i < argCount; i++) {
				argCache[i] = args[i];
			}
		} catch (CLException e) {
			// TODO  This should use the exception processor also, but theres no way to pass the message details
			throw new HardwareException(e.getMessage() + " for function " + name +
							" (index = " + index + " argCount = " + argCount + ")", e);
		}

		try {
			CLComputeContext context = Hardware.getLocalHardware().getClComputeContext();
			if (enableVerboseLog) System.out.println(id + ": clEnqueueNDRangeKernel start");

			cl_event event = new cl_event();

			if (dependsOn instanceof CLSemaphore) {
				CL.clEnqueueNDRangeKernel(context.getClQueue(globalWorkSize > 1), kernel, 1,
						new long[] { globalWorkOffset }, new long[] { globalWorkSize },
						null, 1,
						new cl_event[] { ((CLSemaphore) dependsOn).getEvent() }, event);

			} else {
				if (dependsOn != null) dependsOn.waitFor();

				CL.clEnqueueNDRangeKernel(context.getClQueue(globalWorkSize > 1), kernel, 1,
						new long[]{globalWorkOffset}, new long[]{globalWorkSize},
						null, 0, null, event);
			}

			if (!Hardware.isAsync()) context.processEvent(event, profile);

			if (enableVerboseLog) System.out.println(id + ": clEnqueueNDRangeKernel end");
			return Hardware.isAsync() ? new CLSemaphore(context, event, profile) : null;
		} catch (CLException e) {
			// TODO  This should use the exception processor also, but theres no way to pass the message details
			throw new HardwareException(e.getMessage() + " for function " + name +
					" (total bytes = " + totalSize + ")", e);
		}
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

			if (((MemoryData) args[i]).getMem() instanceof CLMemory == false) {
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
		if (kernel != null) CL.clReleaseKernel(kernel);
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
