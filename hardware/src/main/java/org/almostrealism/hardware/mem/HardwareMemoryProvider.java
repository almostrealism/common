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
