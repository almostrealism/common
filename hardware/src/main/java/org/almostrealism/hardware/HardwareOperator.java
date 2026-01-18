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

package org.almostrealism.hardware;

import io.almostrealism.code.Execution;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.concurrent.Semaphore;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.profile.OperationTimingListener;
import io.almostrealism.profile.OperationWithInfo;
import io.almostrealism.uml.Named;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.kernel.KernelWork;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.almostrealism.io.TimingMetric;

import java.util.List;

/**
 * Abstract base class for compiled hardware operators (kernels/native functions) that execute on accelerators.
 *
 * <p>{@link HardwareOperator} represents a compiled executable unit (OpenCL kernel, Metal shader, or JNI native
 * function) that can be invoked with {@link MemoryData} arguments. It manages argument preparation, automatic
 * memory migration between providers, work size configuration, and execution timing.</p>
 *
 * <h2>Core Responsibilities</h2>
 *
 * <ul>
 *   <li><b>Argument Preparation:</b> Validates and converts arguments to {@link MemoryData}</li>
 *   <li><b>Memory Migration:</b> Automatically moves data between memory providers (CPU&lt;-&gt;GPU) as needed</li>
 *   <li><b>Work Size Management:</b> Configures parallel execution size via {@link #setGlobalWorkSize}</li>
 *   <li><b>Execution Timing:</b> Records metrics for profiling and performance analysis</li>
 *   <li><b>Compilation Tracking:</b> Maintains global CPU/GPU compilation and execution statistics</li>
 * </ul>
 *
 * <h2>Execution Flow</h2>
 *
 * <pre>
 * 1. accept(args) called with Object[] arguments
 * 2. prepareArguments() validates and converts to MemoryData[]
 * 3. For each argument:
 *    - Check if memory provider is supported
 *    - If not, reallocate to supported provider (CPU->GPU or vice versa)
 * 4. Execute kernel/native function with prepared arguments
 * 5. Record timing metrics
 * </pre>
 *
 * <h2>Memory Migration</h2>
 *
 * <p>When an argument's memory provider is not supported by this operator, the entire root
 * delegate is automatically reallocated to a supported provider:</p>
 * <pre>{@code
 * // CPU memory -> GPU kernel
 * PackedCollection cpuData = PackedCollection.create(1000);  // JVM heap
 * GPUKernel kernel = ...;
 *
 * // Automatic migration during accept():
 * kernel.accept(new Object[] { cpuData }, null);
 * // 1. Detects JVM heap not supported by GPU
 * // 2. Reallocates root delegate to GPU memory
 * // 3. Copies data CPU -> GPU
 * // 4. Executes kernel on GPU
 * }</pre>
 *
 * <h2>Work Size Configuration</h2>
 *
 * <p>For parallel kernels, work size controls how many iterations execute in parallel:</p>
 * <pre>{@code
 * HardwareOperator operator = ...;
 *
 * // Process 1000 elements in parallel
 * operator.setGlobalWorkSize(1000);
 * operator.accept(args, null);
 *
 * // Kernel executes with 1000 parallel work items
 * // Each work item processes one element
 * }</pre>
 *
 * <h2>Profiling and Metrics</h2>
 *
 * <p>All operators automatically record timing and execution statistics:</p>
 * <pre>{@code
 * // Enable timing listener
 * HardwareOperator.timingListener = new OperationTimingListener() {
 *     @Override
 *     public long recordDuration(OperationMetadata metadata, Runnable r) {
 *         long start = System.nanoTime();
 *         r.run();
 *         long duration = System.nanoTime() - start;
 *         System.out.println(metadata.getDisplayName() + ": " + (duration / 1_000_000) + "ms");
 *         return duration;
 *     }
 * };
 *
 * // Executions now logged
 * operator.accept(args, null);  // Prints: "MyKernel: 5ms"
 * }</pre>
 *
 * <p>Global statistics are available for monitoring:</p>
 * <pre>{@code
 * System.out.println("GPU compilations: " + HardwareOperator.gpuCompileCount);
 * System.out.println("GPU operations: " + HardwareOperator.gpuOpCount);
 * System.out.println("GPU time: " + (HardwareOperator.gpuOpTime / 1_000_000) + "ms");
 *
 * System.out.println("CPU compilations: " + HardwareOperator.cpuCompileCount);
 * System.out.println("CPU operations: " + HardwareOperator.cpuOpCount);
 * System.out.println("CPU time: " + (HardwareOperator.cpuOpTime / 1_000_000) + "ms");
 * }</pre>
 *
 * <h2>Instruction Set Monitoring</h2>
 *
 * <p>Controlled via environment variables:</p>
 * <ul>
 *   <li><b>AR_INSTRUCTION_SET_MONITORING=always:</b> Monitor all instruction sets</li>
 *   <li><b>AR_INSTRUCTION_SET_MONITORING=enabled:</b> Monitor large instruction sets only</li>
 *   <li><b>AR_INSTRUCTION_SET_MONITORING=failed:</b> Monitor only failed operations</li>
 *   <li><b>AR_HARDWARE_KERNEL_LOG=true:</b> Enable verbose kernel logging</li>
 * </ul>
 *
 * <h2>Subclass Requirements</h2>
 *
 * <p>Concrete implementations must provide:</p>
 * <ul>
 *   <li>{@link #isGPU()} - Whether this operator runs on GPU or CPU</li>
 *   <li>{@link #getSupportedMemory()} - List of supported memory providers</li>
 *   <li>{@link #getHardwareName()} - Human-readable hardware name (e.g., "OpenCL", "Metal", "JNI")</li>
 *   <li>{@link #getArgCount()} - Number of arguments this operator expects</li>
 *   <li>{@link #accept(Object[], Semaphore)} - Execute the kernel/native function</li>
 * </ul>
 *
 * <h2>Backend Implementations</h2>
 *
 * <ul>
 *   <li><b>CLOperator:</b> OpenCL kernels for GPU/CPU execution</li>
 *   <li><b>MetalOperator:</b> Metal shaders for Apple Silicon GPUs</li>
 *   <li><b>NativeExecution:</b> JNI-compiled C functions</li>
 * </ul>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Simple Execution</h3>
 * <pre>{@code
 * HardwareOperator kernel = compileKernel(...);
 * kernel.setGlobalWorkSize(1000);
 * kernel.accept(new Object[] { input, output }, null);
 * }</pre>
 *
 * <h3>Async Execution with Semaphore</h3>
 * <pre>{@code
 * Semaphore sem = kernel.accept(args, null);
 * // ... do other work ...
 * if (sem != null) sem.waitFor();  // Wait for completion
 * }</pre>
 *
 * <h3>Verbose Debugging</h3>
 * <pre>{@code
 * HardwareOperator.verboseLog(() -> {
 *     kernel.accept(args, null);  // Logs detailed execution info
 * });
 * }</pre>
 *
 * @see AcceleratedOperation
 * @see KernelWork
 * @see Execution
 */
public abstract class HardwareOperator implements Execution, KernelWork, OperationInfo, Named, ConsoleFeatures {
	public static boolean enableLog;
	public static boolean enableVerboseLog = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_LOG").orElse(false);
	public static boolean enableInstructionSetMonitoring =
			SystemUtils.getProperty("AR_INSTRUCTION_SET_MONITORING", "disabled").equals("always");
	public static boolean enableLargeInstructionSetMonitoring =
			SystemUtils.getProperty("AR_INSTRUCTION_SET_MONITORING", "disabled").equals("enabled");
	public static boolean enableFailedInstructionSetMonitoring =
			enableLargeInstructionSetMonitoring || enableInstructionSetMonitoring ||
					SystemUtils.getProperty("AR_INSTRUCTION_SET_MONITORING", "disabled").equals("failed");

	public static TimingMetric prepareArgumentsMetric = Hardware.console.timing("prepareArguments");
	public static TimingMetric computeDimMasksMetric = Hardware.console.timing("computeDimMasks");

	public static OperationTimingListener timingListener;
	public static long cpuCompileCount, gpuCompileCount;
	public static long cpuOpCount, gpuOpCount;
	public static long cpuOpTime, gpuOpTime;

	protected static long idCount;

	private volatile long globalWorkSize = 1;
	private volatile long globalWorkOffset;
	private long id;

	public HardwareOperator() {
		this.id = idCount++;
	}

	/**
	 * Returns the global work size (number of parallel executions).
	 *
	 * <p>For GPU kernels, this is the number of work items (threads) to execute in parallel.
	 * For CPU operations, this is typically 1.</p>
	 *
	 * @return The number of parallel executions
	 */
	@Override
	public long getGlobalWorkSize() { return globalWorkSize; }

	/**
	 * Sets the global work size (number of parallel executions).
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * HardwareOperator kernel = ...;
	 * kernel.setGlobalWorkSize(1024);  // Process 1024 elements in parallel
	 * kernel.accept(args, null);
	 * }</pre>
	 *
	 * @param globalWorkSize The number of parallel executions
	 */
	@Override
	public void setGlobalWorkSize(long globalWorkSize) { this.globalWorkSize = globalWorkSize; }

	/**
	 * Returns the global work offset (starting index for parallel execution).
	 *
	 * <p>Allows processing a subset of data by offsetting the work item IDs.
	 * Typically 0 (process from beginning).</p>
	 *
	 * @return The starting offset for work item IDs
	 */
	@Override
	public long getGlobalWorkOffset() { return globalWorkOffset; }

	/**
	 * Sets the global work offset (starting index for parallel execution).
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * kernel.setGlobalWorkSize(1000);
	 * kernel.setGlobalWorkOffset(5000);  // Process indices 5000-5999
	 * kernel.accept(args, null);
	 * }</pre>
	 *
	 * @param globalWorkOffset The starting offset for work item IDs
	 */
	@Override
	public void setGlobalWorkOffset(long globalWorkOffset) { this.globalWorkOffset = globalWorkOffset; }

	/**
	 * Returns the unique ID of this operator.
	 *
	 * @return The operator ID
	 */
	protected long getId() { return id; }

	/**
	 * Returns whether this operator executes on GPU or CPU.
	 *
	 * <p>Used for metrics tracking and context selection.</p>
	 *
	 * @return true if this is a GPU operator (OpenCL, Metal), false for CPU (JNI)
	 */
	public abstract boolean isGPU();

	/**
	 * Returns the list of supported memory providers for this operator.
	 *
	 * <p>Arguments with unsupported memory providers will be automatically
	 * reallocated to a supported provider during {@link #prepareArguments}.</p>
	 *
	 * @return List of memory providers this operator can directly access
	 */
	public abstract List<MemoryProvider<? extends Memory>> getSupportedMemory();

	/**
	 * Returns the human-readable hardware backend name.
	 *
	 * <p>Used for logging and debugging. Examples: "OpenCL", "Metal", "JNI".</p>
	 *
	 * @return The backend name
	 */
	protected abstract String getHardwareName();

	/**
	 * Returns the number of arguments this operator expects.
	 *
	 * @return The argument count
	 */
	protected abstract int getArgCount();

	/**
	 * Prepares arguments for execution by validating and converting to {@link MemoryData}.
	 *
	 * <p>Performs automatic memory migration if an argument's memory provider is not
	 * supported by this operator. Migration copies the entire root delegate to a
	 * supported provider.</p>
	 *
	 * <p>Validation:</p>
	 * <ul>
	 *   <li>All arguments must be non-null</li>
	 *   <li>All arguments must be {@link MemoryData} instances</li>
	 *   <li>Argument count must match expected count</li>
	 * </ul>
	 *
	 * @param argCount Expected number of arguments
	 * @param args The arguments to prepare
	 * @return Array of validated {@link MemoryData} arguments
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if any argument is not {@link MemoryData}
	 */
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

		recordDuration(null, new OperationWithInfo.RunnableWithInfo(metadata,
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

	/**
	 * Records the execution duration of a {@link Runnable} via the timing listener.
	 *
	 * <p>Automatically updates global GPU/CPU operation counts and timing statistics.
	 * If no timing listener is attached, the runnable executes without timing overhead.</p>
	 *
	 * @param semaphore Optional semaphore providing operation metadata
	 * @param r The runnable to execute and time
	 */
	protected void recordDuration(Semaphore semaphore, Runnable r) {
		recordDuration(semaphore, r, true);
	}

	/**
	 * Records the execution duration of a {@link Runnable} with optional statistics tracking.
	 *
	 * <p>Allows disabling global operation counting (e.g., for internal operations like
	 * memory reallocation that shouldn't count as user operations).</p>
	 *
	 * @param semaphore Optional semaphore providing operation metadata
	 * @param r The runnable to execute and time
	 * @param countOp Whether to increment global operation counts
	 */
	protected void recordDuration(Semaphore semaphore, Runnable r, boolean countOp) {
		long duration = -1;

		OperationMetadata requester = null;
		if (semaphore != null) requester = semaphore.getRequester();

		if (timingListener == null) {
			r.run();
		} else if (r instanceof OperationInfo) {
			duration = timingListener.recordDuration(requester, r);
		} else {
			duration = timingListener.recordDuration(requester, OperationWithInfo.RunnableWithInfo.of(getMetadata(), r));
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

	/**
	 * Returns a human-readable description of this operator.
	 *
	 * <p>Format: "{DisplayName} ({WorkSize}x)"</p>
	 *
	 * <p>Example: "VectorAdd (1024x)"</p>
	 *
	 * @return A description string
	 */
	@Override
	public String describe() {
		return getMetadata().getDisplayName() + " (" + getGlobalWorkSize() + "x)";
	}

	/**
	 * Returns the console for logging.
	 *
	 * @return Hardware console instance
	 */
	@Override
	public Console console() { return Hardware.console; }

	/**
	 * Records a compilation event for global statistics tracking.
	 *
	 * <p>Increments either {@link #gpuCompileCount} or {@link #cpuCompileCount}
	 * depending on the backend type.</p>
	 *
	 * @param gpu true if this is a GPU compilation, false for CPU
	 */
	public static void recordCompilation(boolean gpu) {
		if (gpu) {
			gpuCompileCount++;
		} else {
			cpuCompileCount++;
		}
	}

	/**
	 * Executes a {@link Runnable} with verbose logging temporarily enabled.
	 *
	 * <p>Enables {@link #enableVerboseLog} and {@link NativeCompiler#enableVerbose}
	 * for the duration of the runnable, then restores original settings.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * HardwareOperator.verboseLog(() -> {
	 *     kernel.accept(args, null);  // Logs detailed execution info
	 * });
	 * }</pre>
	 *
	 * @param r The runnable to execute with verbose logging
	 */
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
}
