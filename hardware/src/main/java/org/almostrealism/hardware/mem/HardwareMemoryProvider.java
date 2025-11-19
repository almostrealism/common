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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.lang.ref.ReferenceQueue;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * Base class for hardware-specific memory providers with automatic garbage collection integration.
 *
 * <p>{@link HardwareMemoryProvider} manages the lifecycle of native memory allocations across
 * different hardware backends (OpenCL, Metal, JNI). It integrates with Java's garbage collector
 * using {@link java.lang.ref.PhantomReference} to automatically deallocate native memory when
 * Java objects are collected, preventing memory leaks.</p>
 *
 * <h2>Garbage Collection Integration</h2>
 *
 * <p>When native memory is allocated, a {@link NativeRef} (PhantomReference) is created and
 * registered with a {@link ReferenceQueue}. When the Java {@link RAM} object is garbage collected,
 * the reference appears in the queue and triggers automatic deallocation:</p>
 *
 * <pre>{@code
 * // Allocation
 * RAM memory = provider.allocate(1000);
 * // NativeRef created and tracked
 *
 * // ... use memory ...
 *
 * memory = null;  // Only reference lost
 * // Eventually: GC runs → NativeRef enqueued → native memory deallocated
 * }</pre>
 *
 * <h2>Two-Thread Deallocation Architecture</h2>
 *
 * <p>Deallocation is handled by two background daemon threads:</p>
 *
 * <h3>1. Submit Thread</h3>
 * <p>Monitors the {@link ReferenceQueue} for garbage-collected objects:</p>
 * <pre>
 * while (true) {
 *   NativeRef ref = referenceQueue.remove();  // Blocks until GC collects an object
 *   if (queueDeallocation)
 *     deallocationQueue.put(ref);  // Queue for processing
 *   else
 *     deallocateNow(ref);  // Deallocate immediately
 * }
 * </pre>
 *
 * <h3>2. Process Thread</h3>
 * <p>Performs actual deallocation from the priority queue (largest allocations first):</p>
 * <pre>
 * while (true) {
 *   NativeRef ref = deallocationQueue.take();  // Blocks until work available
 *   deallocateNow(ref);  // Free native resources
 * }
 * </pre>
 *
 * <p>This two-thread design separates GC detection from deallocation work, preventing GC pauses
 * from blocking on expensive native operations.</p>
 *
 * <h2>Allocation Tracking</h2>
 *
 * <p>All allocations are tracked in a {@link HashMap} keyed by native pointer address. This
 * enables leak detection and prevents double-deallocation:</p>
 *
 * <pre>{@code
 * // Allocation tracking
 * RAM memory = allocate(1000);
 * // tracked.put(memory.getContainerPointer(), new NativeRef(memory))
 *
 * // Deallocation tracking
 * deallocate(1000, memory);
 * // tracked.remove(memory.getContainerPointer())
 * }</pre>
 *
 * <p>On {@link #destroy()}, any remaining allocations are reported with stack traces to identify
 * memory leaks.</p>
 *
 * <h2>Deallocation Modes</h2>
 *
 * <h3>Immediate Deallocation (Default)</h3>
 * <p>Native memory is freed immediately when {@link #deallocate(int, RAM)} is called:</p>
 * <pre>{@code
 * HardwareMemoryProvider.queueDeallocation = false;  // Default
 * RAM memory = provider.allocate(1000);
 * provider.deallocate(1000, memory);  // Freed immediately
 * }</pre>
 *
 * <h3>Queued Deallocation</h3>
 * <p>Deallocation is queued and processed asynchronously by the process thread:</p>
 * <pre>{@code
 * HardwareMemoryProvider.queueDeallocation = true;
 * RAM memory = provider.allocate(1000);
 * provider.deallocate(1000, memory);  // Queued, returns immediately
 * // Freed later by process thread (largest allocations first)
 * }</pre>
 *
 * <p>Queued deallocation can improve performance when many small deallocations occur in quick
 * succession by batching and prioritizing work.</p>
 *
 * <h2>Shared Memory Support</h2>
 *
 * <p>Hardware backends can support shared/named memory via thread-local naming:</p>
 * <pre>{@code
 * provider.sharedMemory(size -> "shared_buffer_" + size, () -> {
 *     // Allocations within this scope use shared memory if supported
 *     RAM shared = provider.allocate(1000);
 *     // May be mapped to shared memory region "shared_buffer_1000"
 *     return processData(shared);
 * });
 * }</pre>
 *
 * <h2>Leak Detection</h2>
 *
 * <p>When the provider is destroyed, leaked allocations are reported with their allocation
 * stack traces (if {@link RAM#allocationTraceFrames} > 0):</p>
 *
 * <pre>
 * provider.destroy();
 * // Output (if leaks exist):
 * // WARN: DirectBuffer[1000] was not deallocated
 * //   at DirectBuffer.&lt;init&gt;(DirectBuffer.java:42)
 * //   at MyClass.allocateBuffers(MyClass.java:123)
 * //   at MyClass.processData(MyClass.java:88)
 * </pre>
 *
 * <h2>Subclass Requirements</h2>
 *
 * <p>Concrete providers must implement:</p>
 * <ul>
 *   <li>{@link #allocate(int)}: Allocate native memory and return {@link RAM} instance</li>
 *   <li>{@link #deallocate(NativeRef)}: Free native resources for the given reference</li>
 * </ul>
 *
 * <h2>Common Subclasses</h2>
 * <ul>
 *   <li>CLMemoryProvider: OpenCL memory allocation (cl_mem)</li>
 *   <li>MetalMemoryProvider: Metal buffer allocation (MTLBuffer)</li>
 *   <li>NativeMemoryProvider: JNI direct buffer allocation (ByteBuffer)</li>
 * </ul>
 *
 * @param <T> RAM type specific to this provider
 * @see RAM
 * @see NativeRef
 * @see MemoryProvider
 */
public abstract class HardwareMemoryProvider<T extends RAM> implements MemoryProvider<T>, ConsoleFeatures {
	public static boolean queueDeallocation = false;

	protected static ThreadLocal<IntFunction<String>> memoryName;

	static {
		memoryName = new ThreadLocal<>();
	}

	private HashMap<Long, NativeRef<T>> allocated;
	private PriorityBlockingQueue<NativeRef<T>> deallocationQueue;
	private ReferenceQueue<T> referenceQueue;
	private volatile boolean destroying;

	public HardwareMemoryProvider() {
		this.allocated = new HashMap<>();
		this.deallocationQueue = new PriorityBlockingQueue<>(100, Comparator.comparing(NativeRef<T>::getSize).reversed());
		this.referenceQueue = new ReferenceQueue<>();

		Thread deallocationSubmit = new Thread(() -> {
			while (true) {
				try {
					NativeRef ref = (NativeRef) getReferenceQueue().remove();

					if (queueDeallocation) {
						getDeallocationQueue().put(ref);
					} else {
						deallocateNow(ref);
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (IllegalStateException e) {
					warn(e.getMessage());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, getClass().getSimpleName() + " Deallocation Submit Thread");
		deallocationSubmit.setDaemon(true);

		Thread deallocationProcess = new Thread(() -> {
			while (true) {
				try {
					deallocateNow(getDeallocationQueue().take());
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, getClass().getSimpleName() + " Deallocation Process Thread");
		deallocationProcess.setDaemon(true);

		deallocationSubmit.start();
		deallocationProcess.start();
	}

	protected ReferenceQueue<T> getReferenceQueue() { return referenceQueue; }

	protected PriorityBlockingQueue<NativeRef<T>> getDeallocationQueue() { return deallocationQueue; }

	protected List<NativeRef<T>> getAllocated() {
		return allocated.values().stream()
				.sorted(Comparator.comparing(NativeRef<T>::getSize).reversed())
				.toList();
	}

	public int getAllocatedCount() { return allocated.size(); }

	private void deallocateNow(T mem) {
		if (allocated == null) {
			warn("Cannot deallocate " + mem + " as the provider has been destroyed");
			return;
		}

		NativeRef<T> ref = getNativeRef(mem);
		if (ref == null) {
			if (mem.isActive()) {
				warn("Attempting to deallocate untracked memory " + mem);
			}

			return;
		}

		deallocateNow(ref);
	}

	private void deallocateNow(NativeRef<T> ref) {
		deallocate(ref);

		if (!destroying) {
			allocated.remove(ref.getAddress());
		}
	}

	protected NativeRef<T> nativeRef(T ram) {
		return new NativeRef<>(ram, getReferenceQueue());
	}

	protected NativeRef<T> getNativeRef(T ram) {
		if (ram.getProvider() != this)
			throw new IllegalArgumentException("RAM does not belong to this provider");

		return allocated.get(ram.getContainerPointer());
	}

	protected T allocated(T ram) {
		if (destroying) {
			throw new IllegalStateException("Cannot allocate " + ram + " as the provider is being destroyed");
		}

		NativeRef<T> ref = nativeRef(ram);
		if (allocated.containsKey(ref.getAddress())) {
			warn(new IllegalStateException("Already allocated " + ref + " (" + ref.getAddress() + ")"));
		}

		try {
			allocated.put(ref.getAddress(), ref);
		} catch (ClassCastException e) {
			warn("Unable to record allocation", e);
		}

		return ram;
	}

	protected abstract void deallocate(NativeRef<T> ref);

	@Override
	public void deallocate(int size, T mem) {
		if (mem.getProvider() != this)
			throw new IllegalArgumentException();

		if (queueDeallocation) {
			getDeallocationQueue().put(getNativeRef(mem));
		} else {
			deallocateNow(mem);
		}
	}

	protected IntFunction<String> getMemoryName() {
		return memoryName.get();
	}

	public <V> V sharedMemory(IntFunction<String> name, Callable<V> exec) {
		IntFunction<String> currentName = memoryName.get();
		IntFunction<String> nextName = name;

		try {
			memoryName.set(nextName);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			memoryName.set(currentName);
		}
	}

	@Override
	public synchronized void destroy() {
		try {
			destroying = true;

			if (allocated != null) {
				List<NativeRef<T>> stillAllocated = new ArrayList<>();

				w: while (true) {
					try {
						stillAllocated.clear();
						allocated.values().forEach(stillAllocated::add);
						break w;
					} catch (Exception e) {
						// start over and try again if the allocated map was
						// modified while attempting to capture its contents
						warn(e.getClass().getSimpleName() + " - " + e.getMessage());
					}
				}

				stillAllocated.stream()
						.sorted(Comparator.nullsLast(Comparator.comparing(NativeRef<T>::getSize).reversed()))
						.limit(10)
						.forEach(ref -> {
							warn(ref + " was not deallocated");
							if (ref.getAllocationStackTrace() != null) {
								Stream.of(ref.getAllocationStackTrace())
										.forEach(stack -> warn("\tat " + stack));
							}
						});

				// TODO  Deallocating all of these at once appears to produce SIGSEGV
				// List<MetalMemory> available = new ArrayList<>(allocated);
				// available.forEach(mem -> deallocate(0, mem));
				allocated = null;
			}
		} finally {
			destroying = false;
		}
	}


	@Override
	public Console console() { return Hardware.console; }
}
