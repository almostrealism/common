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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.concurrent.Semaphore;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.CL;
import org.jocl.CLException;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_event;
import org.jocl.cl_kernel;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * {@link HardwareOperator} that executes compiled OpenCL kernels.
 *
 * <p>{@link CLOperator} wraps a {@link cl_kernel} and manages kernel argument setup and
 * execution via {@link CL#clEnqueueNDRangeKernel}. Instances are thread-local to avoid
 * synchronization overhead.</p>
 *
 * <h2>Kernel Execution</h2>
 *
 * <pre>{@code
 * CLOperator op = operatorMap.get("matmul", 3);  // 3 arguments
 *
 * // Execute kernel with arguments
 * op.accept(new Object[] { destMemory, inputA, inputB }, null);
 * // -> Sets kernel args (memory, offset, size for each arg)
 * // -> Executes clEnqueueNDRangeKernel
 * // -> Waits for completion
 * // -> Records profiling data (if enabled)
 * }</pre>
 *
 * <h2>Argument Caching</h2>
 *
 * <p>Caches kernel arguments to avoid redundant {@link CL#clSetKernelArg} calls:</p>
 *
 * <pre>{@code
 * // First execution sets all arguments
 * op.accept(args1);  // clSetKernelArg called for all arguments
 *
 * // Subsequent execution with same arguments skips clSetKernelArg
 * op.accept(args1);  // No clSetKernelArg calls
 *
 * // Different arguments update only changed positions
 * op.accept(args2);  // clSetKernelArg only for changed arguments
 * }</pre>
 *
 * <h2>Kernel Argument Layout</h2>
 *
 * <p>Arguments are passed to kernels in groups:</p>
 *
 * <pre>{@code
 * // For each argument:
 * // 1. cl_mem pointer
 * // 2. int offset
 * // 3. int size
 *
 * // Example with 2 arguments:
 * __kernel void matmul(
 *     __global float* arg0, int arg0_offset, int arg0_size,  // Arg 0
 *     __global float* arg1, int arg1_offset, int arg1_size   // Arg 1
 * ) { ... }
 * }</pre>
 *
 * <h2>Queue Selection</h2>
 *
 * <p>Dynamically selects command queue based on work size:</p>
 *
 * <pre>{@code
 * // Small work size (globalWorkSize <= 1): Use main queue
 * cl_command_queue queue = context.getClQueue(false);
 *
 * // Large work size (globalWorkSize > 1): Use kernel queue (GPU if available)
 * cl_command_queue queue = context.getClQueue(true);
 * }</pre>
 *
 * @see CLOperatorMap
 * @see CLComputeContext
 * @see HardwareOperator
 */
public class CLOperator extends HardwareOperator {

	/** Total number of kernel invocations across all CLOperator instances. */
	private static long totalInvocations;

	/** The compute context providing command queues and profiling support. */
	private final CLComputeContext context;

	/** The compiled OpenCL program containing this operator's kernel. */
	private final CLProgram prog;

	/** The kernel function name within the program. */
	private final String name;

	/** Cache of previously set kernel arguments to avoid redundant clSetKernelArg calls. */
	private final Object argCache[];

	/** The number of arguments this kernel expects. */
	private final int argCount;

	/** The OpenCL kernel handle, lazily created on first execution. */
	private cl_kernel kernel;

	/** Processor for converting CLException to HardwareException with context. */
	private BiFunction<String, CLException, HardwareException> exceptionProcessor;

	/** Consumer for recording execution timing data when profiling is enabled. */
	private Consumer<RunData> profile;

	/**
	 * Creates a new CLOperator for executing an OpenCL kernel.
	 *
	 * @param context            the compute context providing command queues
	 * @param program            the compiled OpenCL program containing the kernel
	 * @param name               the kernel function name
	 * @param argCount           the number of arguments the kernel expects
	 * @param profile            consumer for recording execution timing, or null to skip profiling
	 * @param exceptionProcessor processor for converting OpenCL exceptions to HardwareException
	 */
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

	/** Returns the kernel function name. */
	@Override
	public String getName() { return name; }

	/** Returns the hardware identifier "CL" for OpenCL backend. */
	@Override
	protected String getHardwareName() { return "CL"; }

	/** Returns the operation metadata from the compiled program. */
	@Override
	public OperationMetadata getMetadata() { return prog.getMetadata(); }

	/**
	 * Returns whether this operator executes on a GPU device.
	 *
	 * @return true if the kernel will execute on a GPU, false for CPU
	 */
	@Override
	public boolean isGPU() {
		if (context.getClQueue() == context.getKernelClQueue()) {
			return !context.isCPU();
		}

		return context.getClQueue(getGlobalWorkSize() > 1) == context.getKernelClQueue();
	}

	/** Returns the number of arguments this kernel expects. */
	@Override
	protected int getArgCount() { return argCount; }

	/** Returns the list of memory providers supported by this operator's compute context. */
	@Override
	public List<MemoryProvider<? extends Memory>> getSupportedMemory() {
		return context.getDataContext().getMemoryProviders();
	}

	/**
	 * Executes the OpenCL kernel with the provided arguments.
	 *
	 * <p>Lazily creates the kernel on first invocation, sets kernel arguments (using caching
	 * to skip unchanged arguments), and enqueues the kernel for execution. Waits for completion
	 * and records profiling data if enabled.</p>
	 *
	 * @param args      the arguments to pass to the kernel (MemoryData objects)
	 * @param dependsOn optional semaphore to wait on before execution, or null
	 * @return null (future versions may return a semaphore for async execution)
	 */
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
					argCache[i] = data[i];
				}
			} catch (CLException e) {
				// TODO  This should use the exception processor also, but theres no way to pass the message details
				throw new HardwareException(e.getMessage() + " for function " + name +
						" (index = " + index + " argCount = " + argCount + ")", e);
			}

			try {
				if (enableVerboseLog) log(id + " - clEnqueueNDRangeKernel start");

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

				context.processEvent(event, profile);

				if (enableVerboseLog) log(id + " - clEnqueueNDRangeKernel end");

				// TODO  This should return a semaphore
				// return new CLSemaphore(context, event, profile);
			} catch (CLException e) {
				// TODO  This should use the exception processor also,
				// TODO  but theres no way to pass the message details
				throw new HardwareException(e.getMessage() + " for function " + name +
						" (total bytes = " + totalSize + ")", e);
			}
		});

		return null;
	}

	/**
	 * Releases the OpenCL kernel resource.
	 * After calling this method, the operator can no longer be used for execution.
	 */
	public void destroy() {
		if (kernel != null) CL.clReleaseKernel(kernel);
		kernel = null;
	}
}
