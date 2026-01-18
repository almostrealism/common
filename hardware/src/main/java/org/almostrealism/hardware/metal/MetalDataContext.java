/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.metal;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.ctx.HardwareDataContext;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * {@link io.almostrealism.code.DataContext} for Apple Metal GPU backend.
 *
 * <p>Manages Metal {@link MTLDevice}, {@link MetalMemoryProvider}, and {@link MetalComputeContext}
 * for GPU-accelerated computation on macOS and iOS platforms.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * MetalDataContext context = new MetalDataContext(
 *     "Metal",
 *     1024 * 1024 * 1024,  // 1GB max
 *     1024 * 1024);         // 1MB threshold
 * context.init();
 *
 * MTLDevice device = context.getDevice();
 * MetalMemoryProvider memory = (MetalMemoryProvider) context.getMemoryProvider();
 * }</pre>
 *
 * <h2>Precision Support</h2>
 *
 * <pre>{@code
 * // Default: FP32
 * Precision p = context.getPrecision();  // FP32
 *
 * // Enable FP16: Set environment variable
 * // AR_HARDWARE_PRECISION=FP16
 * Precision p = context.getPrecision();  // FP16
 * }</pre>
 *
 * @see MetalComputeContext
 * @see MetalMemoryProvider
 * @see MTLDevice
 */
public class MetalDataContext extends HardwareDataContext {
	/**
	 * True if FP16 (half precision) mode is enabled via AR_HARDWARE_PRECISION=FP16 environment variable.
	 */
	public static final boolean fp16 = SystemUtils.getProperty("AR_HARDWARE_PRECISION", "FP32").equals("FP16");

	private final int offHeapSize;

	private MTLDevice mainDevice;

	private MemoryProvider<MetalMemory> mainRam;
	private MemoryProvider<Memory> altRam;

	private ThreadLocal<ComputeContext<MemoryData>> computeContext;

	private Runnable start;

	/**
	 * Creates a Metal data context with specified memory limits.
	 *
	 * @param name Display name for this context
	 * @param maxReservation Maximum memory in elements (not bytes) that can be allocated
	 * @param offHeapSize Threshold in elements below which JVM heap is used instead of Metal buffers
	 */
	public MetalDataContext(String name, long maxReservation, int offHeapSize) {
		super(name, maxReservation);
		this.offHeapSize = offHeapSize;
		this.computeContext = new ThreadLocal<>();
	}

	/**
	 * Initializes the data context by setting up JVM fallback memory provider.
	 *
	 * <p>Defers Metal device initialization until first use (lazy initialization).</p>
	 */
	@Override
	public void init() {
		altRam = new JVMMemoryProvider();
		start = this::start;
	}

	/**
	 * Identifies and initializes the Metal device.
	 *
	 * <p>Creates the system default Metal device if not already initialized.
	 * Called lazily on first access to the device or memory providers.</p>
	 */
	protected void identifyDevices() {
		if (mainDevice != null) return;

		mainDevice = MTLDevice.createSystemDefaultDevice();
	}

	private void start() {
		if (mainDevice != null) return;

		identifyDevices();
		mainRam = new MetalMemoryProvider(this, getPrecision().bytes(),
				getMaxReservation() * getPrecision().bytes());
		start = null;
	}

	private ComputeContext createContext(ComputeRequirement... expectations) {
		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent() || pReq.isPresent()) {
			throw new UnsupportedOperationException();
		} else {
			if (start != null) start.run();
			cc = new MetalComputeContext(this);
			((MetalComputeContext) cc).init(mainDevice);
		}

		return cc;
	}

	/**
	 * Returns the precision mode for this context.
	 *
	 * @return {@link Precision#FP16} if AR_HARDWARE_PRECISION=FP16, otherwise {@link Precision#FP32}
	 */
	@Override
	public Precision getPrecision() { return fp16 ? Precision.FP16 : Precision.FP32; }

	/**
	 * Returns the Metal device for this context.
	 *
	 * <p>Triggers lazy initialization if not yet started.</p>
	 *
	 * @return The {@link MTLDevice} instance for the system default GPU
	 */
	public MTLDevice getDevice() {
		if (start != null) start.run();
		return mainDevice;
	}

	/**
	 * Returns the list of memory providers for this context.
	 *
	 * @return List containing the main Metal memory provider
	 */
	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(mainRam);
	}

	/**
	 * Returns or creates the shared memory provider for memory-mapped buffers.
	 *
	 * <p>Creates a {@link MetalMemoryProvider} in shared mode for CPU/GPU accessible
	 * memory backed by memory-mapped files.</p>
	 *
	 * @return {@link MetalMemoryProvider} in shared storage mode
	 */
	@Override
	protected MemoryProvider getSharedMemoryProvider() {
		return Optional.ofNullable(super.getSharedMemoryProvider())
				.orElseGet(() -> new MetalMemoryProvider(this, getPrecision().bytes(),
						getMaxReservation() * getPrecision().bytes(), true));
	}

	/**
	 * Returns the primary Metal memory provider.
	 *
	 * <p>Triggers lazy initialization if not yet started.</p>
	 *
	 * @return {@link MetalMemoryProvider} for Metal GPU buffers
	 */
	public MemoryProvider<MetalMemory> getMemoryProvider() {
		if (start != null) start.run();
		return mainRam;
	}

	/**
	 * Returns the alternative JVM heap memory provider.
	 *
	 * <p>Used for small allocations below the {@code offHeapSize} threshold
	 * to avoid GPU memory overhead.</p>
	 *
	 * @return {@link JVMMemoryProvider} for heap-based memory
	 */
	public MemoryProvider<Memory> getAltMemoryProvider() {
		return altRam;
	}

	/**
	 * Returns the memory provider for kernel arguments.
	 *
	 * @return The main Metal memory provider
	 */
	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

	/**
	 * Returns the appropriate memory provider based on allocation size.
	 *
	 * <p>Selects JVM heap for small allocations (below {@code offHeapSize}),
	 * Metal buffers for larger allocations, or a custom provider if configured.</p>
	 *
	 * @param size Allocation size in elements
	 * @return Either {@link MetalMemoryProvider} or {@link JVMMemoryProvider}
	 */
	@Override
	public MemoryProvider<?> getMemoryProvider(int size) {
		IntFunction<MemoryProvider<?>> supply = getMemoryProviderSupply();
		if (supply == null) {
			return size < offHeapSize ? getAltMemoryProvider() : getMemoryProvider();
		} else {
			return supply.apply(size);
		}
	}

	/**
	 * Returns the compute contexts for the current thread.
	 *
	 * <p>Creates a new {@link MetalComputeContext} if none exists for the current thread.
	 * Each thread maintains its own compute context via {@link ThreadLocal}.</p>
	 *
	 * @return List containing the thread's {@link MetalComputeContext}
	 */
	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		if (computeContext.get() == null) {
			if (Hardware.enableVerbose) log("No explicit ComputeContext for " + Thread.currentThread().getName());
			computeContext.set(createContext());
		}

		return List.of(computeContext.get());
	}

	/**
	 * Executes a callable within a specific compute context scope.
	 *
	 * <p>Creates a temporary {@link MetalComputeContext} with the specified requirements,
	 * executes the callable, then destroys the context. The original context is restored
	 * after execution.</p>
	 *
	 * @param <T> Return type of the callable
	 * @param exec The callable to execute
	 * @param expectations Compute requirements (PROFILING, C, etc.)
	 * @return Result from the callable
	 * @throws UnsupportedOperationException if PROFILING or C requirements are specified
	 * @throws RuntimeException if execution fails
	 */
	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		ComputeContext current = computeContext.get();
		ComputeContext next = createContext(expectations);

		String ccName = next.toString();
		if (ccName.contains(".")) {
			ccName = ccName.substring(ccName.lastIndexOf('.') + 1);
		}

		try {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Start " + ccName);
			computeContext.set(next);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: End " + ccName);
			next.destroy();
			if (Hardware.enableVerbose) System.out.println("Hardware[" + getName() + "]: Destroyed " + ccName);
			computeContext.set(current);
		}
	}

	/**
	 * Executes a callable with all memory allocations forced to Metal device memory.
	 *
	 * <p>Overrides the normal size-based memory provider selection to always use
	 * Metal buffers, even for small allocations. Useful for ensuring all data
	 * resides on the GPU during execution.</p>
	 *
	 * @param <T> Return type of the callable
	 * @param exec The callable to execute
	 * @return Result from the callable
	 * @throws RuntimeException if execution fails
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
	 * Destroys this data context and releases all Metal resources.
	 *
	 * <p>Destroys all compute contexts, releases memory providers, and frees
	 * the Metal device. After calling destroy, this context cannot be used.</p>
	 */
	@Override
	public void destroy() {
		// TODO  Destroy any other compute contexts
		if (computeContext.get() != null) {
			computeContext.get().destroy();
			computeContext.remove();
		}

		if (mainRam != null) mainRam.destroy();
		if (altRam != null) altRam.destroy();
		if (mainDevice != null) mainDevice.release();
		mainDevice = null;
	}
}
