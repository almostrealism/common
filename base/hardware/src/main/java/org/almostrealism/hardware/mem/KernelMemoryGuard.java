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

import io.almostrealism.code.Memory;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counting registry that tracks active kernel executions and their
 * associated native memory, preventing GC-triggered deallocation until all
 * kernels using that memory have completed.
 *
 * <p>{@link KernelMemoryGuard} provides a defense-in-depth layer against
 * use-after-free crashes caused by the JVM garbage collector freeing native
 * memory while kernel programs are still reading from or writing to it.</p>
 *
 * <h2>Usage Pattern</h2>
 *
 * <p>Kernel execution backends call {@link #acquire(MemoryData[])} before
 * dispatching a kernel and {@link #release(MemoryData[])} in a {@code finally}
 * block after completion:</p>
 *
 * <pre>{@code
 * KernelMemoryGuard guard = Hardware.getLocalHardware().getKernelMemoryGuard();
 * guard.acquire(data);
 * try {
 *     // dispatch kernel...
 * } finally {
 *     guard.release(data);
 * }
 * }</pre>
 *
 * <p>The deallocation pipeline in {@link HardwareMemoryProvider} checks
 * {@link #canDeallocate(long)} before freeing native memory. If the address
 * is guarded, the deallocation is deferred.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All operations are thread-safe, using {@link ConcurrentHashMap} and
 * {@link AtomicInteger} for lock-free reference counting.</p>
 *
 * @see HardwareMemoryProvider
 * @see org.almostrealism.hardware.Hardware#getKernelMemoryGuard()
 */
public class KernelMemoryGuard implements ConsoleFeatures {

	/** Active kernel reference counts per native memory address. */
	private final ConcurrentHashMap<Long, AtomicInteger> activeReferences;

	/** Strong references to {@link RAM} objects held while kernels are active, preventing GC. */
	private final ConcurrentHashMap<Long, Set<RAM>> heldMemory;

	/**
	 * Creates a new {@link KernelMemoryGuard} with empty tracking maps.
	 */
	public KernelMemoryGuard() {
		this.activeReferences = new ConcurrentHashMap<>();
		this.heldMemory = new ConcurrentHashMap<>();
	}

	/**
	 * Registers all memory arguments as actively used by a kernel execution.
	 *
	 * <p>For each non-null argument with a resolvable {@link RAM} backing,
	 * increments the reference count for the native address and holds a strong
	 * reference to the {@link RAM} object to prevent garbage collection.</p>
	 *
	 * @param args the kernel memory arguments (may contain nulls)
	 */
	public void acquire(MemoryData... args) {
		if (args == null) return;

		for (MemoryData arg : args) {
			if (arg == null) continue;

			RAM ram = resolveRAM(arg);
			if (ram == null) continue;

			long address = ram.getContentPointer();

			activeReferences.computeIfAbsent(address, k -> new AtomicInteger(0))
					.incrementAndGet();

			heldMemory.computeIfAbsent(address,
					k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
					.add(ram);
		}
	}

	/**
	 * Releases all memory arguments after kernel execution completes.
	 *
	 * <p>For each non-null argument with a resolvable {@link RAM} backing,
	 * decrements the reference count. When the count reaches zero, removes
	 * the address from both tracking maps.</p>
	 *
	 * @param args the kernel memory arguments (may contain nulls)
	 */
	public void release(MemoryData... args) {
		if (args == null) return;

		for (MemoryData arg : args) {
			if (arg == null) continue;

			RAM ram = resolveRAM(arg);
			if (ram == null) continue;

			long address = ram.getContentPointer();

			AtomicInteger count = activeReferences.get(address);
			if (count == null) continue;

			int remaining = count.decrementAndGet();
			if (remaining <= 0) {
				activeReferences.remove(address, count);
				heldMemory.remove(address);
			}
		}
	}

	/**
	 * Checks whether native memory at the given address can be safely deallocated.
	 *
	 * <p>Returns {@code true} if the address has no active kernel references
	 * (not in the map or count is zero or below).</p>
	 *
	 * @param address the native memory address to check
	 * @return {@code true} if deallocation is safe, {@code false} if kernels are still active
	 */
	public boolean canDeallocate(long address) {
		AtomicInteger count = activeReferences.get(address);
		return count == null || count.get() <= 0;
	}

	/**
	 * Resolves the underlying {@link RAM} object from a {@link MemoryData} argument.
	 *
	 * @param data the memory data to resolve
	 * @return the backing {@link RAM}, or {@code null} if not resolvable
	 */
	private RAM resolveRAM(MemoryData data) {
		try {
			Memory mem = data.getMem();
			if (mem instanceof RAM) {
				return (RAM) mem;
			}
		} catch (Exception e) {
			// Gracefully handle cases where memory is already destroyed
		}

		return null;
	}

	@Override
	public Console console() {
		return Console.root();
	}
}
