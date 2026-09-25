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
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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

	/** Minimum allocation size in elements below which JVM heap is used instead of Metal buffers. */
	private final int offHeapSize;

	/** The primary Metal device used for all GPU buffer allocations and kernel execution. */
	private MTLDevice mainDevice;

	/**
	 * Memory provider for Metal-backed GPU buffers (main allocation path). Typed as
	 * {@link HardwareMemoryProvider} (always a {@link MetalMemoryProvider}), not the plain
	 * {@link MemoryProvider} interface, so {@link #destroy()} can register a completion
	 * callback via {@link HardwareMemoryProvider#onFullyReleased(Runnable)} to defer
	 * releasing the Metal device this provider's retained blocks point into.
	 */
	private HardwareMemoryProvider<MetalMemory> mainRam;
	/** Fallback JVM-backed memory provider for small allocations below {@link #offHeapSize}. */
	private MemoryProvider<Memory> altRam;

	/**
	 * Serializes context creation and use against {@link #destroy()}, so a compute-context
	 * scope in progress on any thread — whether the shared context or a temporary one from
	 * {@link #computeContext(Callable, ComputeRequirement...)} — cannot have its context (or
	 * the underlying Metal device) torn down underneath it. Readers (context creation and use)
	 * run concurrently with each other; {@link #destroy()} takes the write lock, which is not
	 * granted until every such call has finished.
	 */
	private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

	/**
	 * The single {@link MetalComputeContext} shared by every thread of this data context.
	 *
	 * <p>A compiled Metal kernel ({@link org.almostrealism.hardware.metal.MTLComputePipelineState})
	 * is created from the device and is usable from any command queue on that device, but a
	 * {@link MetalComputeContext} owns the {@link MetalCommandRunner}/command buffer that a
	 * dispatch encodes into and commits. If each thread held its own context (and therefore its
	 * own runner), a kernel cached at the {@code DataContext} level and reused on a different
	 * thread would encode into the originating thread's command buffer, which the executing
	 * thread never commits — the kernel would silently never run. A single shared context keeps
	 * the (device-wide) instruction cache and the command-buffer lifecycle aligned; the runner
	 * already serializes all encoding through one executor, so sharing is safe.</p>
	 *
	 * <p>Lazily created via {@link #sharedContext()}; guarded by {@code this} for publication.</p>
	 */
	private volatile ComputeContext<MemoryData> sharedContext;

	/**
	 * Per-thread override installed only for the duration of a
	 * {@link #computeContext(Callable, ComputeRequirement...)} scope, so an explicitly scoped
	 * context (e.g. forced requirements) applies to the calling thread without disturbing the
	 * shared default other threads use.
	 */
	private final ThreadLocal<ComputeContext<MemoryData>> scopedContext = new ThreadLocal<>();

	/**
	 * Deferred initialization runnable; invoked on first device access, then set to null.
	 * Volatile so that a thread observing it become {@code null} (in {@link #ensureStarted()},
	 * after acquiring {@link #startLock}) also observes every field {@link #start()} wrote
	 * before clearing it.
	 */
	private volatile Runnable start;

	/**
	 * Serializes the lazy {@link #start} callback itself, independent of
	 * {@link #lifecycleLock}. {@link #lifecycleLock}'s read side is shared, so without
	 * this, two threads racing in {@link #ensureStarted()} could both pass the
	 * {@code destroyed} check and both invoke {@link #start} concurrently,
	 * double-initializing the Metal device. Always acquired only after
	 * {@link #lifecycleLock}'s read lock (never the other way around), so it introduces
	 * no new lock-ordering cycle with {@link #destroy()}'s write lock.
	 */
	private final Object startLock = new Object();

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
		log("Hardware[" + getName() + "]: Using the system default GPU for kernels");
	}

	/**
	 * Performs deferred initialization of the Metal device and main memory provider.
	 *
	 * <p>Calls {@link #identifyDevices()} to locate the system GPU, then creates a
	 * {@link MetalMemoryProvider} sized to the configured max reservation. Sets the
	 * {@code start} field to null to signal that initialization is complete.</p>
	 */
	private void start() {
		if (mainDevice != null) return;

		identifyDevices();
		mainRam = new MetalMemoryProvider(this, getPrecision().bytes(),
				getMaxReservation() * getPrecision().bytes());
		start = null;
	}

	/**
	 * Triggers deferred Metal initialization if it has not yet run. Held under the read side of
	 * {@link #lifecycleLock} so {@link #destroy()} cannot be granted (and cannot have already
	 * completed) while this runs, and fails fast once this context has been destroyed instead of
	 * silently reinitializing the Metal device and {@link #mainRam} for a context {@link #destroy()}
	 * has already torn down.
	 *
	 * <p>{@link #lifecycleLock}'s read side is shared, so it does not by itself stop two
	 * threads from both observing {@link #start} as non-null and both running it. The nested
	 * {@link #startLock} monitor serializes the callback itself with a standard double-checked
	 * check: a thread that loses the race to {@link #startLock} re-checks {@link #start} once
	 * inside and finds it already cleared by the winner, so the Metal device is only
	 * initialized once.</p>
	 *
	 * @throws IllegalStateException if this data context has already been destroyed
	 */
	private void ensureStarted() {
		lifecycleLock.readLock().lock();

		try {
			if (isDestroyed()) {
				throw new IllegalStateException("Cannot use " + getName() +
						" because the data context has been destroyed");
			}

			if (start != null) {
				synchronized (startLock) {
					if (start != null) start.run();
				}
			}
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/**
	 * Creates a new {@link MetalComputeContext} for the given compute requirements.
	 *
	 * <p>Triggers deferred device initialization if not yet complete, via
	 * {@link #ensureStarted()} rather than invoking {@link #start} directly, so this call
	 * shares that method's fail-fast destroyed check and {@link #startLock} serialization
	 * instead of racing a concurrent {@link #ensureStarted()} call to double-initialize the
	 * Metal device. Then constructs a {@link MetalComputeContext} backed by the main device.</p>
	 *
	 * @param expectations Compute requirements (e.g., profiling); C and PROFILING are not supported
	 * @return A new {@link MetalComputeContext} initialized with the main Metal device
	 * @throws UnsupportedOperationException if a C or profiling compute context is requested
	 */
	private ComputeContext createContext(ComputeRequirement... expectations) {
		if (isDestroyed()) {
			throw new IllegalStateException("Cannot create a compute context for " +
					getName() + " because the data context has been destroyed");
		}

		Optional<ComputeRequirement> cReq = Stream.of(expectations).filter(ComputeRequirement.C::equals).findAny();
		Optional<ComputeRequirement> pReq = Stream.of(expectations).filter(ComputeRequirement.PROFILING::equals).findAny();

		ComputeContext cc;

		if (cReq.isPresent() || pReq.isPresent()) {
			throw new UnsupportedOperationException();
		} else {
			ensureStarted();
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
		ensureStarted();
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
		ensureStarted();
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
	 * Returns the compute context to use on the current thread.
	 *
	 * <p>Returns the per-thread scoped context if one is active (see
	 * {@link #computeContext(Callable, ComputeRequirement...)}); otherwise the single
	 * {@link #sharedContext shared context}, lazily created on first use.</p>
	 *
	 * @return a single-element list containing the active {@link MetalComputeContext}
	 */
	@Override
	public List<ComputeContext<MemoryData>> getComputeContexts() {
		ComputeContext<MemoryData> scoped = scopedContext.get();
		if (scoped != null) {
			return List.of(scoped);
		}

		return List.of(sharedContext());
	}

	/**
	 * Returns the shared {@link MetalComputeContext}, creating it on first use.
	 *
	 * <p>Held under the read side of {@link #lifecycleLock} so {@link #destroy()} cannot
	 * release {@link #mainDevice} while this is creating (or handing out) the shared
	 * context; without it, a call arriving just after teardown could see a stale
	 * {@code null} field and recreate a context against an already-released device. Uses
	 * double-checked locking on {@code this} so concurrent threads (e.g. several
	 * {@code Evaluable.async} dispatch threads) observe a single instance.</p>
	 *
	 * @return the shared compute context
	 * @throws IllegalStateException if this data context has been destroyed
	 */
	private ComputeContext<MemoryData> sharedContext() {
		lifecycleLock.readLock().lock();

		try {
			if (isDestroyed()) {
				throw new IllegalStateException("Cannot create a compute context for " +
						getName() + " because the data context has been destroyed");
			}

			ComputeContext<MemoryData> cc = sharedContext;

			if (cc == null) {
				synchronized (this) {
					cc = sharedContext;
					if (cc == null) {
						cc = createContext();
						sharedContext = cc;
					}
				}
			}

			return cc;
		} finally {
			lifecycleLock.readLock().unlock();
		}
	}

	/**
	 * Executes a callable within a specific compute context scope.
	 *
	 * <p>Creates a temporary {@link MetalComputeContext} with the specified requirements,
	 * executes the callable, then destroys the context. The original context is restored
	 * after execution.</p>
	 *
	 * <p>Runs under the read side of {@link #lifecycleLock} for the whole scope, not just
	 * context creation, so {@link #destroy()} (which takes the write lock) cannot release
	 * {@code mainDevice} out from under a context this call created and is about to, or is
	 * still, dispatching through.</p>
	 *
	 * @param <T> Return type of the callable
	 * @param exec The callable to execute
	 * @param expectations Compute requirements (PROFILING, C, etc.)
	 * @return Result from the callable
	 * @throws UnsupportedOperationException if PROFILING or C requirements are specified
	 * @throws RuntimeException if execution fails
	 */
	@Override
	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		lifecycleLock.readLock().lock();

		try {
			ComputeContext<MemoryData> current = scopedContext.get();
			ComputeContext next = createContext(expectations);

			String ccName = next.toString();
			if (ccName.contains(".")) {
				ccName = ccName.substring(ccName.lastIndexOf('.') + 1);
			}

			try {
				if (Hardware.enableVerbose) log("Hardware[" + getName() + "]: Start " + ccName);
				scopedContext.set(next);
				return exec.call();
			} catch (RuntimeException e) {
				throw e;
			} catch (Exception e) {
				throw new RuntimeException(e);
			} finally {
				if (Hardware.enableVerbose) log("Hardware[" + getName() + "]: End " + ccName);
				next.destroy();
				if (Hardware.enableVerbose) log("Hardware[" + getName() + "]: Destroyed " + ccName);

				if (current == null) {
					scopedContext.remove();
				} else {
					scopedContext.set(current);
				}
			}
		} finally {
			lifecycleLock.readLock().unlock();
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
	 *
	 * <p>Takes the write side of {@link #lifecycleLock}, which is not granted until every
	 * in-progress {@link #sharedContext()} or {@link #computeContext(Callable, ComputeRequirement...)}
	 * call on any thread has finished — so no scope created against this context is still
	 * starting or running when {@code mainDevice} is released, and {@link #getComputeContexts()}
	 * cannot observe a partially torn-down context.</p>
	 *
	 * <p>{@link HardwareMemoryProvider#destroy()} retains blocks that are still referenced
	 * rather than freeing them, so that a caller still holding one keeps seeing valid memory.
	 * Releasing {@code mainDevice} immediately would break that promise, since a retained
	 * {@link MetalMemory} points into it — so the device is released only once
	 * {@link HardwareMemoryProvider#onFullyReleased(Runnable)} reports every retained block
	 * actually gone (immediately, if none were retained).</p>
	 */
	@Override
	public void destroy() {
		// TODO(review): destroy() from inside computeContext() on the same thread self-deadlocks (read->write upgrade)
		lifecycleLock.writeLock().lock();

		try {
			super.destroy();
			start = null;

			if (sharedContext != null) {
				sharedContext.destroy();
				sharedContext = null;
			}

			// Defensive: no live scope can be holding the calling thread's slot here.
			scopedContext.remove();
		} finally {
			lifecycleLock.writeLock().unlock();
		}

		if (altRam != null) altRam.destroy();

		if (mainRam != null) {
			mainRam.destroy();
			mainRam.onFullyReleased(this::releaseDevice);
		} else {
			releaseDevice();
		}
	}

	/** Releases the underlying Metal device, once, if it has not already been released. */
	private synchronized void releaseDevice() {
		if (mainDevice != null) {
			mainDevice.release();
			mainDevice = null;
		}
	}
}
