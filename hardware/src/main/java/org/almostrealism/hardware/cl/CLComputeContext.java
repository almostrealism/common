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

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.InstructionSet;
import io.almostrealism.code.Memory;
import io.almostrealism.code.Precision;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.ScopeEncoder;
import io.almostrealism.scope.Scope;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.ctx.AbstractComputeContext;
import org.almostrealism.hardware.profile.ProfileData;
import org.almostrealism.hardware.profile.RunData;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_device_id;
import org.jocl.cl_event;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link io.almostrealism.code.ComputeContext} implementation for OpenCL kernel compilation and execution.
 *
 * <p>{@link CLComputeContext} manages OpenCL resources including command queues, kernel compilation,
 * and execution profiling. It compiles {@link Scope} instances into OpenCL kernels and manages their
 * execution lifecycle.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * CLDataContext dataContext = ...;
 * cl_context ctx = ...;
 * CLComputeContext computeContext = new CLComputeContext(dataContext, ctx);
 * computeContext.init(mainDevice, kernelDevice, true);  // Enable profiling
 *
 * // Compile scope to OpenCL kernels
 * Scope<Matrix> scope = operation.getScope(computeContext);
 * InstructionSet instructions = computeContext.deliver(scope);
 *
 * // Execute compiled kernel
 * Operator op = instructions.get("functionName");
 * op.accept(args);
 * }</pre>
 *
 * <h2>Command Queue Management</h2>
 *
 * <p>Maintains multiple command queues for different operations:</p>
 *
 * <pre>{@code
 * // Main queue: General operations and memory transfers
 * cl_command_queue main = computeContext.getClQueue();
 *
 * // Fast queue: Specialized fast path (if enabled)
 * cl_command_queue fast = computeContext.getFastClQueue();
 *
 * // Kernel queue: Dedicated GPU kernel execution (if available)
 * cl_command_queue kernel = computeContext.getKernelClQueue();
 *
 * // Dynamic queue selection based on work size
 * cl_command_queue selected = computeContext.getClQueue(isKernelOp);
 * }</pre>
 *
 * <h2>Scope Compilation</h2>
 *
 * <p>Compiles {@link Scope} to OpenCL C code:</p>
 *
 * <pre>{@code
 * @Override
 * public InstructionSet deliver(Scope scope) {
 *     StringBuffer buf = new StringBuffer();
 *
 *     // Add FP64 pragma if needed
 *     if (enableFp64) buf.append("#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n");
 *
 *     // Encode scope to OpenCL C
 *     ScopeEncoder enc = new ScopeEncoder(
 *         p -> new OpenCLPrintWriter(p, precision),
 *         Accessibility.EXTERNAL);
 *     buf.append(enc.apply(scope));
 *
 *     // Compile to cl_program and create operator map
 *     return new CLOperatorMap(this, scope.getMetadata(), buf.toString(), profile);
 * }
 * }</pre>
 *
 * <h2>Profiling Support</h2>
 *
 * <p>Tracks kernel execution time when profiling is enabled:</p>
 *
 * <pre>{@code
 * // Initialize with profiling
 * computeContext.init(device, null, true);  // profiling = true
 *
 * // Execution automatically records timing
 * op.accept(args);  // Timing added to ProfileData
 *
 * // View profiling results
 * computeContext.logProfiles();
 * // Output:
 * //   Top Total Durations:
 * //     - matmul: 45.2ms (1000 runs, avg 45.2us)
 * //     - add: 12.3ms (5000 runs, avg 2.46us)
 * }</pre>
 *
 * <h2>Event Processing</h2>
 *
 * <p>Waits for OpenCL events and extracts profiling data:</p>
 *
 * <pre>{@code
 * cl_event event = new cl_event();
 * CL.clEnqueueNDRangeKernel(..., event);
 *
 * processEvent(event, profile);
 * // -> clWaitForEvents(event)
 * // -> Extract CL_PROFILING_COMMAND_START/END
 * // -> profile.accept(new RunData(duration))
 * // -> clReleaseEvent(event)
 * }</pre>
 *
 * <h2>Multi-Queue Mode</h2>
 *
 * <p>Optional fast queue for performance optimization (experimental):</p>
 *
 * <pre>{@code
 * // Enable fast queue (off by default due to consistency issues)
 * CLComputeContext.enableFastQueue = true;
 *
 * // Creates separate queue for certain operations
 * if (enableFastQueue) {
 *     fastQueue = CL.clCreateCommandQueue(ctx, device, flags, null);
 * }
 *
 * // WARNING: Known issues with argument caching when enabled
 * }</pre>
 *
 * <h2>FP64 Extension</h2>
 *
 * <p>Automatically adds OpenCL FP64 extension pragma:</p>
 *
 * <pre>{@code
 * // When precision is FP64
 * String code = computeContext.deliver(scope);
 * // Prepends: #pragma OPENCL EXTENSION cl_khr_fp64 : enable
 * }</pre>
 *
 * <h2>Profile Reporting</h2>
 *
 * <p>Detailed profiling statistics:</p>
 *
 * <pre>{@code
 * computeContext.logProfiles();
 * // Output:
 * // Profiler Results:
 * //   Top Total Durations:
 * //     - conv2d: 1.23s (100 runs, avg 12.3ms, stddev 2.1ms)
 * //     - matmul: 450ms (500 runs, avg 900us, stddev 150us)
 * //   Top Execution Count:
 * //     - add: 10000 runs, total 50ms
 * //     - multiply: 8000 runs, total 40ms
 * }</pre>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <pre>{@code
 * CLComputeContext context = new CLComputeContext(dc, ctx);
 * context.init(mainDevice, kernelDevice, profiling);
 *
 * try {
 *     // Compile and execute kernels
 * } finally {
 *     context.destroy();
 *     // -> Logs profiling results (if enabled)
 *     // -> Destroys all instruction sets
 *     // -> Releases command queues
 * }
 * }</pre>
 *
 * @see CLDataContext
 * @see CLOperatorMap
 * @see CLOperator
 */
public class CLComputeContext extends AbstractComputeContext {
	/**
	 * Enables creation of a separate fast command queue for certain operations.
	 *
	 * <p><strong>Warning:</strong> Using multiple queues by enabling this flag appears to cause
	 * hard-to-identify issues wherein kernel functions behave totally differently depending
	 * on whether kernel arguments have been retrieved using {@link CLMemoryProvider#toArray(Memory, int)}
	 * before the kernel is invoked. Disabled by default due to these consistency issues.</p>
	 */
	public static boolean enableFastQueue = false;

	/** OpenCL pragma to enable FP64 (double precision) extension. */
	private static final String fp64 = "#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n";

	/** Whether FP64 double precision is enabled for this context. */
	private boolean enableFp64;

	/** The underlying OpenCL context handle. */
	private cl_context ctx;

	/** The main OpenCL command queue for general operations. */
	private cl_command_queue queue;

	/** Optional fast command queue for performance optimization (experimental). */
	private cl_command_queue fastQueue;

	/** Optional dedicated command queue for kernel execution on GPU. */
	private cl_command_queue kernelQueue;

	/** Whether profiling is enabled for timing measurements. */
	private boolean profiling;

	/** Map of profile names to their collected profiling data. */
	private Map<String, ProfileData> profiles;

	/** Loader for manually-authored .cl kernel source files (deprecated). */
	private CLOperatorSources functions;

	/** List of all instruction sets created by this context for cleanup. */
	private List<CLOperatorMap> instructionSets;

	/**
	 * Creates a new OpenCL compute context for kernel compilation and execution.
	 *
	 * @param dc   the data context providing memory management and precision settings
	 * @param ctx  the OpenCL context handle for resource allocation
	 */
	public CLComputeContext(CLDataContext dc, cl_context ctx) {
		super(dc);
		this.enableFp64 = dc.getPrecision() == Precision.FP64;
		this.ctx = ctx;
		this.instructionSets = new ArrayList<>();
		this.profiles = new HashMap<>();
	}

	/**
	 * Initializes the OpenCL command queues for this compute context.
	 * Creates the main queue, and optionally creates fast and kernel queues
	 * if enabled or if a separate kernel device is provided.
	 *
	 * @param mainDevice    the primary OpenCL device for general operations
	 * @param kernelDevice  optional dedicated device for kernel execution, or null to use main device
	 * @param profiling     true to enable profiling on command queues for timing information
	 */
	protected void init(cl_device_id mainDevice, cl_device_id kernelDevice, boolean profiling) {
		if (queue != null) return;

		this.profiling = profiling;

		queue = CL.clCreateCommandQueue(ctx, mainDevice, profiling ? CL.CL_QUEUE_PROFILING_ENABLE : 0, null);
		if (Hardware.enableVerbose) System.out.println("Hardware[CL]: OpenCL command queue initialized");

		if (enableFastQueue) {
			fastQueue = CL.clCreateCommandQueue(ctx, mainDevice, profiling ? CL.CL_QUEUE_PROFILING_ENABLE : 0, null);
			if (Hardware.enableVerbose)
				System.out.println("Hardware[CL]: OpenCL fast command queue initialized");
		}

		if (kernelDevice != null) {
			kernelQueue = CL.clCreateCommandQueue(ctx, kernelDevice, profiling ? CL.CL_QUEUE_PROFILING_ENABLE : 0, null);
			if (Hardware.enableVerbose)
				System.out.println("Hardware[CL]: OpenCL kernel command queue initialized");
		}
	}

	/** Returns the OpenCL language operations for code generation. */
	@Override
	public LanguageOperations getLanguage() {
		return new OpenCLLanguageOperations(getDataContext().getPrecision());
	}

	/**
	 * Returns the shared operator sources for this context, initializing if needed.
	 *
	 * @return the CLOperatorSources instance
	 * @deprecated Use {@link #deliver(Scope)} instead for scope compilation
	 */
	@Deprecated
	public synchronized CLOperatorSources getFunctions() {
		if (functions == null) {
			functions = new CLOperatorSources();
			functions.init(this);
		}

		return functions;
	}

	/**
	 * Compiles a scope to OpenCL C code and creates an instruction set for execution.
	 * Adds the FP64 pragma if double precision is enabled.
	 *
	 * @param scope  the scope containing the computation graph to compile
	 * @return an instruction set containing the compiled OpenCL kernels
	 */
	@Override
	public InstructionSet deliver(Scope scope) {
		long start = System.nanoTime();
		StringBuffer buf = new StringBuffer();

		try {
			if (enableFp64) buf.append(fp64);

			ScopeEncoder enc = new ScopeEncoder(
					p -> new OpenCLPrintWriter(p, getDataContext().getPrecision()),
					Accessibility.EXTERNAL);
			buf.append(enc.apply(scope));

			CLOperatorMap instSet = new CLOperatorMap(this, scope.getMetadata(), buf.toString(), profileFor(scope.getName()));
			instructionSets.add(instSet);
			return instSet;
		} finally {
			recordCompilation(scope, buf::toString, System.nanoTime() - start);
		}
	}

	/** Returns true if the underlying OpenCL device is a CPU. */
	@Override
	public boolean isCPU() { return ((CLDataContext) getDataContext()).isCPU(); }

	/** Returns true if profiling is enabled on the command queues. */
	@Override
	public boolean isProfiling() { return profiling; }

	/**
	 * Returns the underlying OpenCL context handle.
	 *
	 * @return the OpenCL context
	 */
	protected cl_context getCLContext() {
		return ctx;
	}

	/**
	 * Returns the main OpenCL command queue.
	 *
	 * @return the main command queue
	 */
	public cl_command_queue getClQueue() { return queue; }

	/**
	 * Returns the appropriate command queue based on the operation type.
	 *
	 * @param kernel  true to use the kernel queue, false for the main queue
	 * @return the kernel queue if requested and available, otherwise the main queue
	 */
	public cl_command_queue getClQueue(boolean kernel) { return kernel ? getKernelClQueue() : getClQueue(); }

	/**
	 * Returns the fast command queue if enabled, otherwise returns the main queue.
	 *
	 * @return the fast queue or main queue
	 */
	public cl_command_queue getFastClQueue() { return fastQueue == null ? getClQueue() : fastQueue; }

	/**
	 * Returns the dedicated kernel queue if available, otherwise returns the main queue.
	 *
	 * @return the kernel queue or main queue
	 */
	public cl_command_queue getKernelClQueue() { return kernelQueue == null ? getClQueue() : kernelQueue; }

	/**
	 * Returns a consumer that records run data for the given profile name.
	 * Creates a new profile entry if one does not exist.
	 *
	 * @param name  the profile name to record data under
	 * @return a consumer that adds run data to the named profile
	 */
	protected Consumer<RunData> profileFor(String name) {
		if (!profiles.containsKey(name)) profiles.put(name, new ProfileData());
		return profiles.get(name)::addRun;
	}

	/**
	 * Waits for an OpenCL event to complete and releases it.
	 *
	 * @param event  the OpenCL event to process
	 */
	protected void processEvent(cl_event event) { processEvent(event, null); }

	/**
	 * Waits for an OpenCL event to complete, optionally records profiling data, and releases the event.
	 *
	 * @param event    the OpenCL event to process
	 * @param profile  optional consumer to receive timing data, or null to skip profiling
	 */
	protected void processEvent(cl_event event, Consumer<RunData> profile) {
		CL.clWaitForEvents(1, new cl_event[] { event });

		if (profiling && profile != null) {
			long startTime[] = { 0 };
			long endTime[] = { 0 };
			CL.clGetEventProfilingInfo(event, CL.CL_PROFILING_COMMAND_START, Sizeof.cl_ulong, Pointer.to(startTime), null);
			CL.clGetEventProfilingInfo(event, CL.CL_PROFILING_COMMAND_END, Sizeof.cl_ulong, Pointer.to(endTime), null);
			profile.accept(new RunData(endTime[0] - startTime[0]));
		}

		CL.clReleaseEvent(event);
	}

	/**
	 * Logs profiling results to standard output, showing top and bottom durations
	 * as well as execution counts. Does nothing if no profiling data has been collected.
	 */
	public void logProfiles() {
		if (profiles.size() <= 0) {
			System.out.println("No profiling results");
			return;
		}

		List<String> names = new ArrayList<>();
		names.addAll(profiles.keySet());

		Comparator<String> totalDuration = (a, b) -> (int) (profiles.get(b).getTotalRuntimeNanos() - profiles.get(a).getTotalRuntimeNanos());
		Comparator<String> totalCount = (a, b) -> (int) (profiles.get(b).getTotalRuns() - profiles.get(a).getTotalRuns());

		int include = 4;

		System.out.println("Profiler Results:");

		names.sort(totalDuration);

		System.out.println("\tTop Total Durations:");
		for (int i = 0; i < Math.min(include, names.size()); i++) {
			System.out.println("\t\t-" + names.get(i) + ": " + profiles.get(names.get(i)).getSummaryString());
		}

		System.out.println("\tBottom Total Durations:");
		for (int i = 0; i < Math.min(include, names.size()); i++) {
			System.out.println("\t\t-" + names.get(names.size() - i - 1) + ": " + profiles.get(names.get(names.size() - i - 1)).getSummaryString());
		}

		names.sort(totalCount.thenComparing(totalDuration));

		System.out.println("\tTop Execution Count:");
		for (int i = 0; i < Math.min(include, names.size()); i++) {
			System.out.println("\t\t-" + names.get(i) + ": " + profiles.get(names.get(i)).getSummaryString());
		}
	}

	/**
	 * Releases all OpenCL resources held by this context.
	 * Logs profiling results if profiling was enabled, destroys all instruction sets,
	 * and releases all command queues.
	 */
	@Override
	public void destroy() {
		if (profiling) logProfiles();
		this.instructionSets.forEach(InstructionSet::destroy);
		if (queue != null) CL.clReleaseCommandQueue(queue);
		if (fastQueue != null) CL.clReleaseCommandQueue(fastQueue);
		if (kernelQueue != null) CL.clReleaseCommandQueue(kernelQueue);
		queue = null;
		fastQueue = null;
		kernelQueue = null;
	}
}
