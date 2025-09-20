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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.*;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * {@link CLOperator}s are intended to be used with {@link ThreadLocal}.
 */
public class CLOperator extends HardwareOperator {

	private static long totalInvocations;

	private final CLComputeContext context;
	private final CLProgram prog;
	private final String name;

	private final Object argCache[];
	private final int argCount;

	private cl_kernel kernel;

	private BiFunction<String, CLException, HardwareException> exceptionProcessor;
	private Consumer<RunData> profile;

	public CLOperator(CLComputeContext context, CLProgram program, String name, int argCount, Consumer<RunData> profile,
					  BiFunction<String, CLException, HardwareException> exceptionProcessor) {
		this.context = context;
		this.prog = program;
		this.name = name;
		this.argCache = new Object[argCount];
		this.argCount = argCount;
		this.profile = profile;
		this.exceptionProcessor = exceptionProcessor;
	}

	@Override
	public String getName() { return name; }

	@Override
	protected String getHardwareName() { return "CL"; }

	@Override
	public OperationMetadata getMetadata() { return prog.getMetadata(); }

	@Override
	public boolean isGPU() {
		if (context.getClQueue() == context.getKernelClQueue()) {
			return !context.isCPU();
		}

		return context.getClQueue(getGlobalWorkSize() > 1) == context.getKernelClQueue();
	}

	@Override
	protected int getArgCount() { return argCount; }

	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return context.getDataContext().getMemoryProviders();
	}

	@Override
	public synchronized Semaphore accept(Object[] args, Semaphore dependsOn) {
		if (kernel == null) {
			try {
				kernel = CL.clCreateKernel(prog.getProgram(), name, null);
			} catch (CLException e) {
				throw exceptionProcessor == null ? e : exceptionProcessor.apply(name, e);
			}
		}

		long id = totalInvocations++;

		if (enableVerboseLog) {
			System.out.println("CL: " + prog.getMetadata().getDisplayName() + " (" + id + ")");
		}

		if (dependsOn != null) dependsOn.waitFor();
		MemoryData data[] = prepareArguments(argCount, args);

		recordDuration(null, () -> {
			int index = 0;
			long totalSize = 0;

			try {
				int dimMasks[] = computeDimensionMasks(data);

				for (int i = 0; i < argCount; i++) {
					if (data[i] != argCache[i]) {
						CLMemory mem = (CLMemory) data[i].getMem();
						totalSize += mem.getSize();
						CL.clSetKernelArg(kernel, index++, Sizeof.cl_mem, Pointer.to(((CLMemory) data[i].getMem()).getMem()));
					} else {
						index++;
					}
				}

				for (int i = 0; i < argCount; i++) {
					if (data[i] != argCache[i]) {
						CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
								Pointer.to(new int[]{data[i].getOffset()})); // Offset
					} else {
						index++;
					}
				}

				for (int i = 0; i < argCount; i++) {
					if (data[i] != argCache[i]) {
						CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
								Pointer.to(new int[]{data[i].getAtomicMemLength()})); // Size
					} else {
						index++;
					}
				}

				for (int i = 0; i < argCount; i++) {
					if (data[i] != argCache[i]) {
						if (ScopeSettings.enableDimensionMasking) {
							CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
									Pointer.to(new int[]{data[i].getAtomicMemLength() * dimMasks[i]})); // Dim0
						} else {
							CL.clSetKernelArg(kernel, index++, Sizeof.cl_int,
									Pointer.to(new int[]{data[i].getAtomicMemLength()})); // Dim0
						}
					} else {
						index++;
					}
				}

				for (int i = 0; i < argCount; i++) {
					argCache[i] = data[i];
				}
			} catch (CLException e) {
				// TODO  This should use the exception processor also, but theres no way to pass the message details
				throw new HardwareException(e.getMessage() + " for function " + name +
						" (index = " + index + " argCount = " + argCount + ")", e);
			}

			try {
				if (enableVerboseLog) System.out.println(id + ": clEnqueueNDRangeKernel start");

				cl_event event = new cl_event();

				if (dependsOn instanceof CLSemaphore) {
					CL.clEnqueueNDRangeKernel(context.getClQueue(getGlobalWorkSize() > 1), kernel, 1,
							new long[]{getGlobalWorkOffset()}, new long[]{getGlobalWorkSize()},
							null, 1,
							new cl_event[]{((CLSemaphore) dependsOn).getEvent()}, event);
				} else {
					// if (dependsOn != null) dependsOn.waitFor();

					CL.clEnqueueNDRangeKernel(context.getClQueue(getGlobalWorkSize() > 1), kernel, 1,
							new long[]{getGlobalWorkOffset()}, new long[]{getGlobalWorkSize()},
							null, 0, null, event);
				}

				if (!Hardware.isAsync()) context.processEvent(event, profile);

				if (enableVerboseLog) System.out.println(id + ": clEnqueueNDRangeKernel end");
				// return Hardware.isAsync() ? new CLSemaphore(context, event, profile) : null;
			} catch (CLException e) {
				// TODO  This should use the exception processor also, but theres no way to pass the message details
				throw new HardwareException(e.getMessage() + " for function " + name +
						" (total bytes = " + totalSize + ")", e);
			}
		});

		if (Hardware.isAsync()) {
			throw new UnsupportedOperationException("Temporarily unavailable due to profile implementation");
		}

		return null;
	}

	public void destroy() {
		if (kernel != null) CL.clReleaseKernel(kernel);
		kernel = null;
	}
}
