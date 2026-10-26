/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.compute.ComputeRequirement;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.jvm.JVMMemoryProvider;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * A {@link HardwareDataContext} for an accelerator device that is started lazily, shares one
 * compute context between threads, and must outlive the memory allocated from it.
 *
 * <p>Subclasses supply the device-specific parts: {@link #startDevice()}, which opens the
 * device and returns its memory provider; {@link #newComputeContext()}; and
 * {@link #releaseDevice()}. This class owns the lifecycle they plug into.</p>
 *
 * <h2>Lazy start</h2>
 *
 * <p>{@link #init()} does not touch the device. It is started on first use, once, even when
 * several threads race to use it: {@link #ensureStarted()} runs the deferred start under the
 * read side of {@link #lifecycleLock} and serializes it on {@link #startLock} with a
 * double-checked test. Use after {@link #destroy()} fails fast with
 * {@link IllegalStateException} rather than restarting a device for a destroyed context.</p>
 *
 * <h2>Compute contexts</h2>
 *
 * <p>All threads share a single compute context, created on first use. A device's compiled
 * kernels are cached per data context but dispatched through the compute context's command
 * submission (a Metal command buffer, a CUDA stream), so per-thread contexts would let a kernel
 * compiled on one thread be dispatched through another thread's submission path.
 * {@link #computeContext(Callable, ComputeRequirement...)} installs a temporary context for the
 * calling thread only, for the duration of the call.</p>
 *
 * <h2>Destruction</h2>
 *
 * <p>{@link #destroy()} takes the write side of {@link #lifecycleLock}, which is not granted
 * while any thread is creating or using a compute context. Calling it from inside such a scope
 * on the same thread would deadlock, so it is rejected with {@link IllegalStateException}.
 * {@link HardwareMemoryProvider#destroy()} retains blocks that are still referenced, and those
 * blocks point into the device, so the device is released only once
 * {@link HardwareMemoryProvider#onFullyReleased(Runnable)} reports every block gone.</p>
 *
 * <p>Allocations smaller than the off-heap size are made on the JVM heap; kernels move them
 * to device memory when they need them.</p>
 *
 * @param <M> the type of the device's memory provider
 */
public abstract class AcceleratorDataContext<M extends HardwareMemoryProvider<?>> extends HardwareDataContext {
	/** Allocations of fewer values than this are made on the JVM heap. */
	private final int offHeapSize;

	/** The device's memory provider, once started. */
	private M deviceRam;

	/** The JVM heap memory provider for small allocations. */
	private MemoryProvider<Memory> altRam;

	/**
	 * Serializes context creation and use against {@link #destroy()}. Readers (context
	 * creation and use) run concurrently with each other; {@link #destroy()} takes the write
	 * lock, which is not granted until every such call has finished.
	 */
	private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

	/** The compute context shared by every thread; guarded by {@code this} for publication. */
	private volatile ComputeContext<MemoryData> sharedContext;

	/** The compute context of the current {@link #computeContext} scope, per thread. */
	private final ThreadLocal<ComputeContext<MemoryData>> scopedContext = new ThreadLocal<>();

	/**
	 * The deferred start, cleared once it has run. Volatile so that a thread observing it
	 * cleared also observes everything the start wrote.
	 */
	private volatile Runnable start;

	/**
	 * Serializes the deferred start. Always acquired after the read side of
	 * {@link #lifecycleLock}, never before, so it adds no lock-ordering cycle with
	 * {@link #destroy()}.
	 */
	private final Object startLock = new Object();

	/**
	 * Creates a data context. The device is not started until first use.
	 *
	 * @param name           the context name
	 * @param maxReservation the maximum number of values that may be allocated at once
	 * @param offHeapSize    allocations of fewer values than this are made on the JVM heap
	 */
	protected AcceleratorDataContext(String name, long maxReservation, int offHeapSize) {
		super(name, maxReservation);
		this.offHeapSize = offHeapSize;
	}

	/** Prepares the JVM heap memory provider and defers starting the device until first use. */
	@Override
	public void init() {
		altRam = new JVMMemoryProvider();
		start = () -> {
			deviceRam = startDevice();
			start = null;
		};
	}

	/**
	 * Opens the device. Called at most once, on first use.
	 *
	 * @return the memory provider for the device
	 */
	protected abstract M startDevice();

	/**
	 * Creates a compute context for the device. Called only after the device has started.
	 *
	 * @return the new compute context
	 */
	protected abstract ComputeContext<MemoryData> newComputeContext();

	/** Releases the device. Called once, after all device memory has been released. */
	protected abstract void releaseDevice();

	/**
	 * Starts the device if it has not been started.
	 *
	 * @throws IllegalStateException if this data context has been destroyed
	 */
	protected void ensureStarted() {
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
	 * Creates a compute context meeting the given requirements.
	 *
	 * @throws IllegalStateException if this data context has been destroyed
	 * @throws UnsupportedOperationException if a C or profiling context is required
	 */
	private ComputeContext<MemoryData> createContext(ComputeRequirement... expectations) {
		if (isDestroyed()) {
			throw new IllegalStateException("Cannot create a compute context for " +
					getName() + " because the data context has been destroyed");
		}

		if (Stream.of(expectations).anyMatch(r -> r == ComputeRequirement.C || r == ComputeRequirement.PROFILING)) {
			throw new UnsupportedOperationException();
		}

		ensureStarted();
		return newComputeContext();
	}

	/** Returns the device's memory provider, starting the device if necessary. */
	public M getMemoryProvider() {
		ensureStarted();
		return deviceRam;
	}

	/** Returns the JVM heap memory provider used for small allocations. */
	public MemoryProvider<Memory> getAltMemoryProvider() {
		return altRam;
	}

	@Override
	public List<MemoryProvider<? extends Memory>> getMemoryProviders() {
		return List.of(getMemoryProvider());
	}

	@Override
	public MemoryProvider<? extends Memory> getKernelMemoryProvider() { return getMemoryProvider(); }

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
	 * Returns the calling thread's scoped compute context if a
	 * {@link #computeContext(Callable, ComputeRequirement...)} scope is active, and otherwise
	 * the shared compute context.
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
	 * Returns the shared compute context, creating it on first use.
	 *
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
	 * Runs {@code exec} with a new compute context meeting the given requirements installed
	 * for the calling thread, then destroys that context and restores the previous one. The
	 * whole scope holds the read side of {@link #lifecycleLock}, so the device cannot be
	 * released while the scope's context is in use.
	 */
	@Override
	public <T> T computeContext(Callable<T> exec, ComputeRequirement... expectations) {
		lifecycleLock.readLock().lock();

		try {
			ComputeContext<MemoryData> current = scopedContext.get();
			ComputeContext<MemoryData> next = createContext(expectations);
			String ccName = next.getClass().getSimpleName();

			try {
				if (Hardware.enableVerbose) log("Hardware[" + getName() + "]: Start " + ccName);
				scopedContext.set(next);
				return call(exec);
			} finally {
				if (Hardware.enableVerbose) log("Hardware[" + getName() + "]: End " + ccName);
				next.destroy();

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

	/** Runs {@code exec} with every allocation, whatever its size, made in device memory. */
	@Override
	public <T> T deviceMemory(Callable<T> exec) {
		return withMemoryProvider(s -> getMemoryProvider(), exec);
	}

	/**
	 * Destroys the compute contexts and memory providers, releasing the device once all of its
	 * memory has been released.
	 *
	 * @throws IllegalStateException if called from within a compute-context scope on the same thread
	 */
	@Override
	public void destroy() {
		if (lifecycleLock.getReadHoldCount() > 0) {
			throw new IllegalStateException("Cannot destroy " + getName() +
					" from within a compute-context scope on the same thread");
		}

		lifecycleLock.writeLock().lock();

		try {
			super.destroy();
			start = null;

			if (sharedContext != null) {
				sharedContext.destroy();
				sharedContext = null;
			}

			scopedContext.remove();
		} finally {
			lifecycleLock.writeLock().unlock();
		}

		if (altRam != null) altRam.destroy();

		if (deviceRam != null) {
			deviceRam.destroy();
			deviceRam.onFullyReleased(this::releaseDevice);
		} else {
			releaseDevice();
		}
	}
}
