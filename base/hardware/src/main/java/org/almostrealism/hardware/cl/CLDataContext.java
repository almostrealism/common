/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.cl;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.DataContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jni.NativeCompiler;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.hardware.mem.RAM;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;
import org.jocl.CL;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_platform_id;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * {@link DataContext} implementation for OpenCL backend providing CPU/GPU hardware acceleration.
 *
 * <p>{@link CLDataContext} manages the lifecycle of OpenCL devices, contexts, memory providers,
 * and compute contexts for hardware-accelerated computation. It supports:</p>
 * <ul>
 *   <li><strong>Device selection:</strong> CPU or GPU execution</li>
 *   <li><strong>Memory management:</strong> Multiple memory provider strategies (host, device, heap)</li>
 *   <li><strong>Compute contexts:</strong> Thread-local OpenCL or native C compilation</li>
 *   <li><strong>Lazy initialization:</strong> Resources allocated on first use</li>
 * </ul>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * // Create context for GPU execution
 * CLDataContext context = new CLDataContext(
 *     "GPU",                    // Name
 *     1024 * 1024 * 1024,      // 1GB max reservation
 *     1024 * 1024,             // 1MB off-heap threshold
 *     CLMemoryProvider.Location.DEVICE
 * );
 *
 * context.init();
 *
 * // Access memory provider
 * MemoryProvider<RAM> memory = context.getMemoryProvider();
 *
 * // Use for computation
 * ComputeContext<MemoryData> compute = context.getComputeContexts().get(0);
 * }</pre>
 *
 * <h2>Device Selection</h2>
 *
 * <p>Automatically selects OpenCL devices (CPU or GPU):</p>
 *
 * <pre>{@code
 * // identifyDevices() finds best available devices:
 * // 1. Main device: CPU (CL_DEVICE_TYPE_CPU) for memory operations
 * // 2. Kernel device: GPU (CL_DEVICE_TYPE_GPU) for compute kernels (optional)
 *
 * if (kernelDevice != null) {
 *     // Dual-queue mode: CPU for memory, GPU for kernels
 * } else {
 *     // Single-queue mode: CPU for both
 * }
 * }</pre>
 *
 * <h2>Memory Provider Strategies</h2>
 *
 * <p>Supports multiple memory allocation strategies via {@link CLMemoryProvider.Location}:</p>
 *
 * <pre>{@code
 * // DEVICE: Allocate on GPU device memory
 * CLDataContext device = new CLDataContext(
 *     "GPU", maxMem, threshold, CLMemoryProvider.Location.DEVICE);
 *
 * // HOST: Use host-pinned memory (faster transfers)
 * CLDataContext host = new CLDataContext(
 *     "CPU", maxMem, threshold, CLMemoryProvider.Location.HOST);
 *
 * // HEAP: Use Java heap arrays
 * CLDataContext heap = new CLDataContext(
 *     "Heap", maxMem, threshold, CLMemoryProvider.Location.HEAP);
 *
 * // DELEGATE: Delegate to another memory provider
 * CLDataContext delegate = new CLDataContext(
 *     "Delegate", maxMem, threshold, CLMemoryProvider.Location.DELEGATE);
 * delegate.setDelegateMemoryProvider(customProvider);
 * }</pre>
 *
 * <h2>Lazy Initialization</h2>
 *
 * <p>Resources are initialized on first access:</p>
 *
 * <pre>{@code
 * CLDataContext context = new CLDataContext(...);
 * context.init();  // Sets up start callback
 *
 * // First access triggers OpenCL initialization
 * Precision p = context.getPrecision();  // Calls start()
 * // -> Identifies devices
 * // -> Creates cl_context
 * // -> Creates command queues
 * // -> Initializes memory providers
 *
 * // Subsequent access reuses initialized resources
 * }</pre>
 *
 * <h2>Compute Context Management</h2>
 *
 * <p>Provides thread-local {@link ComputeContext} instances:</p>
 *
 * <pre>{@code
 * // Get default compute context for current thread
 * List<ComputeContext<MemoryData>> contexts = context.getComputeContexts();
 * ComputeContext<MemoryData> cc = contexts.get(0);
 *
 * // Compile and execute operation
 * Scope<Void> scope = myComputation.getScope(cc);
 * InstructionSet instructions = cc.deliver(scope);
 * }</pre>
 *
 * <h2>Temporary Compute Context</h2>
 *
 * <p>Execute code with specific {@link ComputeRequirement}:</p>
 *
 * <pre>{@code
 * // Temporarily use native C compilation
 * T result = context.computeContext(() -> {
 *     // Code here uses CLNativeComputeContext
 *     return compile(myOperation).evaluate();
 * }, ComputeRequirement.C);
 *
 * // Enable profiling
 * T result = context.computeContext(() -> {
 *     // Profiling enabled for this execution
 *     return operation.evaluate();
 * }, ComputeRequirement.PROFILING);
 * }</pre>
 *
 * <h2>Memory Provider Selection</h2>
 *
 * <p>Selects memory provider based on allocation size:</p>
 *
 * <pre>{@code
 * // Small allocations (<= offHeapSize) use JVM heap
 * MemoryProvider<?> small = context.getMemoryProvider(1024);
 * // Returns JVMMemoryProvider
 *
 * // Large allocations use CLMemoryProvider
 * MemoryProvider<?> large = context.getMemoryProvider(10 * 1024 * 1024);
 * // Returns CLMemoryProvider
 * }</pre>
 *
 * <h2>Device Memory Scope</h2>
 *
 * <p>Force all allocations to use device memory:</p>
 *
 * <pre>{@code
 * T result = context.deviceMemory(() -> {
 *     // All memory allocations use CLMemoryProvider
 *     // regardless of size
 *     return operation.evaluate();
 * });
 * }</pre>
 *
 * <h2>Device Information</h2>
 *
 * <p>Access device capabilities:</p>
 *
 * <pre>{@code
 * DeviceInfo main = context.getMainDeviceInfo();
 * System.out.println("Cores: " + main.getCores());
 * System.out.println("Clock: " + main.getClockMhz() + " MHz");
 * System.out.println("Global Memory: " + main.getGlobalMem() + " bytes");
 *
 * DeviceInfo kernel = context.getKernelDeviceInfo();
 * // GPU device info (if available)
 * }</pre>
 *
 * <h2>Precision Selection</h2>
 *
 * <p>Automatically selects FP32 for GPU, FP64 for CPU:</p>
 *
 * <pre>{@code
 * // With kernel device (GPU)
 * precision = Precision.FP32;  // 32-bit floats
 *
 * // Without kernel device (CPU only)
 * precision = Precision.FP64;  // 64-bit doubles
 * }</pre>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <pre>{@code
 * CLDataContext context = new CLDataContext(...);
 * context.init();
 *
 * try {
 *     // Use context for computation
 * } finally {
 *     context.destroy();
 *     // -> Destroys all compute contexts
 *     // -> Releases memory providers
 *     // -> Releases OpenCL context
 * }
 * }</pre>
 *
 * @see CLComputeContext
 * @see CLMemoryProvider
 * @see CLMemory
 */
public class CLDataContext implements DataContext<MemoryData>, ConsoleFeatures {
	/**
	 * Enables native C compilation context in addition to OpenCL.
	 * When enabled, creates a {@link CLNativeComputeContext} alongside the standard
	 * OpenCL context. Controlled by the AR_HARDWARE_CL_NATIVE environment variable.
	 */
	public static boolean enableClNative = SystemUtils.isEnabled("AR_HARDWARE_CL_NATIVE").orElse(false);

	/** The name of this data context (e.g., "GPU", "CPU"). */
	private final String name;

	/** The maximum memory reservation in bytes. */
	private final long maxReservation;

	/** The threshold size in bytes below which allocations use JVM heap. */
	private final int offHeapSize;

	/** The memory allocation strategy (HOST, DEVICE, HEAP, or DELEGATE). */
	private final CLMemoryProvider.Location location;

	/** The floating-point precision (FP32 or FP64). */
	private Precision precision;

	/** The OpenCL platform handle. */
	private cl_platform_id platform;

	/** The OpenCL device type constant (CL_DEVICE_TYPE_CPU or CL_DEVICE_TYPE_GPU). */
	private long deviceType;

	/** The main OpenCL device handle for memory operations. */
	private cl_device_id mainDevice;

	/** Device information for the main CPU device. */
	private DeviceInfo mainDeviceInfo;

	/** Optional GPU device handle for kernel execution. */
	private cl_device_id kernelDevice;

	/** Device information for the kernel GPU device. */
	private DeviceInfo kernelDeviceInfo;

	/** The OpenCL context handle. */
	private cl_context ctx;

	/** The main OpenCL memory provider for RAM allocations. */
	private MemoryProvider<RAM> mainRam;

	/** The alternate JVM heap-based memory provider for small allocations. */
	private MemoryProvider<Memory> altRam;

	/** Optional delegate memory provider for DELEGATE location mode. */
	private MemoryProvider<? extends RAM> delegateMemory;

	/** Thread-local list of compute contexts for concurrent access. */
	private ThreadLocal<List<ComputeContext<MemoryData>>> computeContexts;

	/** Thread-local memory provider function for size-based allocation. */
	private ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	/** Lazy initialization callback for OpenCL resources. */
	private Runnable start;

	/**
	 * Constructs a new CLDataContext with the specified configuration.
	 *
	 * @param name           the name of this data context (e.g., "GPU", "CPU")
	 * @param maxReservation the maximum memory reservation in bytes
	 * @param offHeapSize    the threshold size in bytes below which allocations use JVM heap
	 * @param location       the memory allocation strategy (HOST, DEVICE, HEAP, or DELEGATE)
	 */
	public CLDataContext(String name, long maxReservation, int offHeapSize, CLMemoryProvider.Location location) {
		this.name = name;
		this.maxReservation = maxReservation;
		this.offHeapSize = offHeapSize;
		this.location = location;
		this.computeContexts = ThreadLocal.withInitial(ArrayList::new);
		this.memoryProvider = new ThreadLocal<>();
	}

	/**
	 * Initializes the data context by setting up the alternate memory provider
	 * and configuring the lazy initialization callback. Actual OpenCL resource
	 * initialization is deferred until first access.
	 */
	@Override
	public void init() {
		altRam = new JVMMemoryProvider();
		start = () -> start(!SystemUtils.isMacOS() || SystemUtils.isAarch64());
	}

	/**
	 * Identifies and selects OpenCL devices for this context. Selects a CPU device
	 * as the main device for memory operations, and optionally selects a GPU device
	 * for kernel execution if available and requested.
	 *
	 * @param kernelQueue if {@code true}, attempt to find a GPU device for kernel execution
	 */
	protected void identifyDevices(boolean kernelQueue) {
		if (platform != null && mainDevice != null) return;

		final int platformIndex = 0;
		final int deviceIndex = 0;
		deviceType = CL.CL_DEVICE_TYPE_CPU;

		int numPlatformsArray[] = new int[1];
		CL.clGetPlatformIDs(0, null, numPlatformsArray);
		int numPlatforms = numPlatformsArray[0];

		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: " + numPlatforms + " platforms available");

		cl_platform_id platforms[] = new cl_platform_id[numPlatforms];
		CL.clGetPlatformIDs(platforms.length, platforms, null);
		platform = platforms[platformIndex];

		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + name + "]: Using platform " + platformIndex + " -- " + platform);

		/* Main Device Selection */

		int numDevicesArray[] = new int[1];
		int numDevices = 0;

		try {
			CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
			numDevices = numDevicesArray[0];
		} catch (Exception e) { }

		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(deviceType) + "(s) available");

		if (numDevices > 0) {
			cl_device_id devices[] = new cl_device_id[numDevices];
			CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
			mainDevice = devices[deviceIndex];

			System.out.println("Hardware[" + name + "]: Using " + deviceName(deviceType) + " " + deviceIndex);
		}

		/* Kernel Device Selection */

		if (kernelQueue) {
			CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, 0, null, numDevicesArray);
			numDevices = numDevicesArray[0];
			cl_device_id devices[] = new cl_device_id[numDevices];

			if (Hardware.enableVerbose)
				System.out.println("Hardware[" + name + "]: " + numDevices + " " + deviceName(CL.CL_DEVICE_TYPE_GPU) + "(s) available for kernels");

			if (numDevices > 0) {
				CL.clGetDeviceIDs(platform, CL.CL_DEVICE_TYPE_GPU, numDevices, devices, null);
				kernelDevice = devices[deviceIndex];

				System.out.println("Hardware[" + name + "]: Using " + deviceName(CL.CL_DEVICE_TYPE_GPU) + " " + deviceIndex + " for kernels");
			}
		}

		precision = kernelDevice == null ? Precision.FP64 : Precision.FP32;
	}

	/**
	 * Performs the actual OpenCL initialization including device identification,
	 * context creation, command queue setup, and memory provider initialization.
	 * This method is called lazily on first resource access.
	 *
	 * @param kernelQueue if {@code true}, attempt to use a separate GPU device for kernels
	 */
	private void start(boolean kernelQueue) {
		if (ctx != null) return;

		CL.setExceptionsEnabled(true);

		identifyDevices(kernelQueue);

		this.mainDeviceInfo = mainDevice == null ? null : deviceInfo(mainDevice);
		this.kernelDeviceInfo = kernelDevice == null ? null : deviceInfo(kernelDevice);

		cl_context_properties contextProperties = new cl_context_properties();
		contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

		if (kernelDevice == null) {
			ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{mainDevice},
					null, null, null);
		} else if (mainDevice == null) {
			ctx = CL.clCreateContext(contextProperties, 1, new cl_device_id[]{kernelDevice},
					null, null, null);
			mainDevice = kernelDevice;
		} else {
			ctx = CL.clCreateContext(contextProperties, 2, new cl_device_id[]{mainDevice, kernelDevice},
					null, null, null);
		}

		if (Hardware.enableVerbose) System.out.println("Hardware[" + name + "]: OpenCL context initialized");

		cl_command_queue queue = CL.clCreateCommandQueue(ctx, mainDevice, 0, null);
		if (Hardware.enableVerbose)
			System.out.println("Hardware[" + getName() + "]: OpenCL read/write command queue initialized");

		mainRam = new CLMemoryProvider(this, queue, getPrecision().bytes(),
						maxReservation * getPrecision().bytes(), location);

		start = null;
	}

	/**
	 * Creates a new compute context based on the specified requirements.
	 * If {@link ComputeRequirement#C} is present, creates a native C compilation context;
	 * otherwise creates a standard OpenCL compute context.
	 *
	 * @param expectations the compute requirements influencing context creation
	 * @return a new {@link ComputeContext} configured for the specified requirements
	 */
	private ComputeContext createContext(ComputeRequirement... expectations) {
		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent()) {
			cc = new CLNativeComputeContext(this, NativeCompiler.factory(getPrecision(), true).construct());
		} else {
			if (start != null) start.run();
			cc = new CLComputeContext(this, ctx);
			((CLComputeContext) cc).init(mainDevice, kernelDevice, pReq.isPresent());
		}

		return cc;
	}

	/** Returns the name of this data context. */
	public String getName() { return name; }

	/**
	 * Returns the floating-point precision used by this context.
	 * Triggers lazy initialization if not already started.
	 * Returns {@link Precision#FP32} when using a GPU kernel device,
	 * or {@link Precision#FP64} when using CPU only.
	 *
	 * @return the precision (FP32 or FP64) for this context
	 */
	@Override
	public Precision getPrecision() {
		if (start != null) start.run();
		return precision;
	}

	/**
	 * Returns the underlying OpenCL context.
	 * Triggers lazy initialization if not already started.
	 *
	 * @return the OpenCL {@link cl_context} for this data context
	 */
	public cl_context getClContext() {
		if (start != null) start.run();
		return ctx;
	}

	/**
	 * Returns {@code true} if this context is using CPU-only execution (no GPU kernel device).
	 *
	 * @return true if CPU-only, false if a GPU kernel device is available
	 */
	protected boolean isCPU() { return kernelDevice == null; }

	/**
	 * Returns device information for the main CPU device used for memory operations.
	 *
	 * @return the main device information
	 */
	public DeviceInfo getMainDeviceInfo() { return mainDeviceInfo; }

	/**
	 * Returns device information for the GPU kernel device, or {@code null} if CPU-only.
	 *
	 * @return the kernel device information, or null
	 */
	public DeviceInfo getKernelDeviceInfo() { return kernelDeviceInfo; }

	/**
	 * Returns the list of memory providers available in this context.
	 *
	 * @return an immutable list containing the main RAM memory provider
	 */
	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(mainRam);
	}

	/**
	 * Returns the main OpenCL memory provider for RAM allocations.
	 * Triggers lazy initialization if not already started.
	 *
	 * @return the main {@link CLMemoryProvider} for this context
	 */
	public MemoryProvider<RAM> getMemoryProvider() {
		if (start != null) start.run();
		return mainRam;
	}

	/**
	 * Returns the alternate JVM heap-based memory provider for small allocations.
	 *
	 * @return the alternate memory provider
	 */
	public MemoryProvider<Memory> getAltMemoryProvider() { return altRam; }

	/**
	 * Returns the memory provider used for kernel execution.
	 * In this implementation, delegates to {@link #getMemoryProvider()}.
	 *
	 * @return the memory provider for kernel memory allocations
	 */
	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

	/**
	 * Returns an appropriate memory provider based on the allocation size.
	 * If a custom memory provider function has been set via thread-local,
	 * uses that; otherwise returns the alternate provider for small allocations
	 * (below {@code offHeapSize}) or the main provider for larger allocations.
	 *
	 * @param size the size of the allocation in bytes
	 * @return the appropriate memory provider for the given size
	 */
	@Override
	public MemoryProvider<?> getMemoryProvider(int size) {
		IntFunction<MemoryProvider<?>> supply = memoryProvider.get();
		if (supply == null) {
			return size < offHeapSize ? getAltMemoryProvider() : getMemoryProvider();
		} else {
			return supply.apply(size);
		}
	}

	/**
	 * Sets the delegate memory provider for use when location is set to DELEGATE.
	 *
	 * @param delegate the memory provider to delegate allocations to
	 */
	public void setDelegateMemoryProvider(MemoryProvider<? extends RAM> delegate) {
		this.delegateMemory = delegate;
	}

	/**
	 * Returns the delegate memory provider, or {@code null} if not set.
	 *
	 * @return the delegate memory provider, or null
	 */
	public MemoryProvider<? extends RAM> getDelegateMemoryProvider() {
		return delegateMemory;
	}

	/**
	 * Returns the list of compute contexts for the current thread. If no contexts
	 * exist for this thread, creates a default OpenCL context and optionally
	 * a native C compilation context if {@code enableClNative} is true.
	 *
	 * @return the thread-local list of compute contexts
	 */
	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (computeContexts.get().isEmpty()) {
			if (Hardware.enableVerbose) log("No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContexts.get().add(createContext());

			if (enableClNative) {
				computeContexts.get().add(createContext(ComputeRequirement.C));
			}
		}

		return computeContexts.get();
	}

	/**
	 * Executes the given callable within a temporary compute context configured
	 * with the specified requirements. The temporary context is destroyed after
	 * execution, and the previous context is restored.
	 *
	 * @param <T>          the return type of the callable
	 * @param exec         the callable to execute
	 * @param expectations the compute requirements for the temporary context
	 * @return the result of the callable execution
	 * @throws RuntimeException if the callable throws an exception
	 */
	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		List<ComputeContext<MemoryData>> current = computeContexts.get();
		List<ComputeContext<MemoryData>> next = List.of(createContext(expectations));

		String ccName = next.toString();
		if (ccName.contains(".")) {
			ccName = ccName.substring(ccName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + ccName);
			computeContexts.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + ccName);
			next.get(0).destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + ccName);
			computeContexts.set(current);
		}
	}

	/**
	 * Executes the given callable with all memory allocations forced to use
	 * the main device memory provider, regardless of allocation size.
	 * Restores the previous memory provider behavior after execution.
	 *
	 * @param <T>  the return type of the callable
	 * @param exec the callable to execute
	 * @return the result of the callable execution
	 * @throws RuntimeException if the callable throws an exception
	 */
	@Override
	public <T> T deviceMemory(Callable<T> exec) {
		IntFunction<MemoryProvider<?>> current = memoryProvider.get();
		IntFunction<MemoryProvider<?>> next = s -> mainRam;

		try {
			memoryProvider.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			memoryProvider.set(current);
		}
	}

	/**
	 * Releases all resources held by this data context including compute contexts,
	 * memory providers, and the underlying OpenCL context.
	 */
	@Override
	public void destroy() {
		// TODO  Destroy any other compute contexts
		if (computeContexts.get() != null) {
			computeContexts.get().forEach(cc -> cc.destroy());
			computeContexts.remove();
		}

		if (mainRam != null) mainRam.destroy();
		if (altRam != null) altRam.destroy();
		if (ctx != null) CL.clReleaseContext(ctx);
		ctx = null;
	}

	/** Returns the console for logging output. */
	@Override
	public Console console() { return Hardware.console; }

	/**
	 * Returns a human-readable name for the given OpenCL device type.
	 *
	 * @param type the OpenCL device type constant (CL_DEVICE_TYPE_CPU or CL_DEVICE_TYPE_GPU)
	 * @return "CPU" or "GPU" depending on the device type
	 * @throws IllegalArgumentException if the device type is unknown
	 */
	protected static String deviceName(long type) {
		if (type == CL.CL_DEVICE_TYPE_CPU) {
			return "CPU";
		} else if (type == CL.CL_DEVICE_TYPE_GPU) {
			return "GPU";
		} else {
			throw new IllegalArgumentException("Unknown device type " + type);
		}
	}

	/**
	 * Queries and returns detailed information about the specified OpenCL device.
	 * Also prints device capabilities (cores, clock speed, memory) to standard output.
	 *
	 * @param device the OpenCL device to query
	 * @return a {@link DeviceInfo} object containing the device's capabilities
	 */
	protected DeviceInfo deviceInfo(cl_device_id device) {
		DeviceInfo info = new DeviceInfo(device);

		double kb = 1024.0;
		double mb = kb * kb;
		double gb = mb * kb;

		long cores = info.getCores();
		String clock = info.getClockMhz() / 1000.0 + "GHz";
		String work = info.getMaxWorkItemDimensions() + "D work support and " +
				info.getWorkGroupSize() / kb + "kb work size";
		String memory = info.getLocalMem() / kb + "kb local / " +
				info.getGlobalMem() / gb + "gb global (" +
				info.getMaxAlloc() / gb + "gb allocation limit)";

		System.out.println("Hardware[" + getName() + "]: " + cores + " cores @ " + clock);
		System.out.println("Hardware[" + getName() + "]: " + work);
		System.out.println("Hardware[" + getName() + "]: " + memory);
		if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Max args " + info.getMaxConstantArgs());

		return info;
	}
}
