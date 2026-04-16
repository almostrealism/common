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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;
import org.almostrealism.hardware.Hardware;
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
 * <p>Kernel execution backends use the static {@link #acquireFor(MemoryData[])}
 * and {@link #releaseFor(KernelMemoryGuard, MemoryData[])} methods to bracket
 * kernel dispatch:</p>
 *
 * <pre>{@code
 * KernelMemoryGuard guard = KernelMemoryGuard.acquireFor(data);
 * try {
 *     // dispatch kernel...
 * } finally {
 *     KernelMemoryGuard.releaseFor(guard, data);
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
 * {@link AtomicInteger} for lock-free reference counting. The {@link #release}
 * method uses {@link ConcurrentHashMap#computeIfPresent} to atomically
 * decrement and remove entries, preventing races with concurrent
 * {@link #acquire} calls.</p>
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

			activeReferences.compute(address, (k, existing) -> {
				AtomicInteger count = existing != null ? existing : new AtomicInteger(0);
				count.incrementAndGet();
				return count;
			});

			heldMemory.computeIfAbsent(address,
					k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
					.add(ram);
		}
	}

	/**
	 * Releases all memory arguments after kernel execution completes.
	 *
	 * <p>For each non-null argument with a resolvable {@link RAM} backing,
	 * atomically decrements the reference count. When the count reaches zero,
	 * removes the address from both tracking maps.</p>
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

			activeReferences.computeIfPresent(address, (k, count) -> {
				int remaining = count.decrementAndGet();
				if (remaining <= 0) {
					heldMemory.remove(address);
					return null;
				}
				return count;
			});
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
	 * Acquires the guard for the given memory data from the local {@link Hardware}.
	 *
	 * <p>Returns the guard instance if acquisition succeeds, or {@code null} if
	 * no hardware or guard is available, or if acquisition fails for any reason.
	 * Guard failures are silently absorbed to avoid disrupting kernel execution.</p>
	 *
	 * @param data the kernel memory arguments
	 * @return the guard instance, or {@code null}
	 */
	public static KernelMemoryGuard acquireFor(MemoryData[] data) {
		try {
			Hardware hw = Hardware.getLocalHardware();
			if (hw != null) {
				KernelMemoryGuard guard = hw.getKernelMemoryGuard();
				if (guard != null) {
					guard.acquire(data);
					return guard;
				}
			}
		} catch (Exception e) {
			// Guard failures must not prevent kernel execution
		}
		return null;
	}

	/**
	 * Releases the guard for the given memory data.
	 *
	 * <p>No-op if the guard is {@code null}. Release failures are silently
	 * absorbed to avoid disrupting kernel return.</p>
	 *
	 * @param guard the guard to release, or {@code null}
	 * @param data the kernel memory arguments
	 */
	public static void releaseFor(KernelMemoryGuard guard, MemoryData[] data) {
		if (guard == null) return;
		try {
			guard.release(data);
		} catch (Exception e) {
			// Guard failures must not prevent returning
		}
	}

	/**
	 * Consults the local {@link Hardware}'s {@link KernelMemoryGuard} and emits a
	 * warning if the given native address is still marked as actively referenced
	 * by a running kernel. This is a <em>diagnostic-only</em> check: it never
	 * throws, never blocks, and does not prevent the caller from proceeding with
	 * deallocation. Callers that want to avoid an imminent use-after-free crash
	 * must decide how to react on their own (defer, retry, etc.) — this helper
	 * only surfaces the condition.
	 *
	 * <p>When the allocation stack trace is available (controlled by
	 * {@code AR_HARDWARE_ALLOCATION_TRACE_FRAMES}) it is included in the warning
	 * so the developer can see where the memory about to be freed was allocated.</p>
	 *
	 * @param address         the native content pointer about to be freed
	 * @param allocationTrace the allocation stack trace captured at RAM creation time, may be null
	 * @param context         short description of the destroy path (e.g. {@code "NativeBuffer"},
	 *                        {@code "NativeMemory"}) used to identify the source of the warning
	 */
	public static void warnIfActivelyReferenced(long address,
												StackTraceElement[] allocationTrace,
												String context) {
		try {
			Hardware hw = Hardware.getLocalHardware();
			if (hw == null) return;
			KernelMemoryGuard guard = hw.getKernelMemoryGuard();
			if (guard == null || guard.canDeallocate(address)) return;

			Hardware.console.warn(
					context + " at 0x" + Long.toHexString(address) +
					" is being deallocated while the KernelMemoryGuard still " +
					"reports active kernel references; in-flight kernels may " +
					"read from unmapped memory");
			if (allocationTrace != null && allocationTrace.length > 0) {
				StringBuilder sb = new StringBuilder("  (allocated at:");
				for (StackTraceElement el : allocationTrace) {
					sb.append("\n    at ").append(el);
				}
				sb.append(")");
				Hardware.console.warn(sb.toString());
			}
		} catch (Throwable t) {
			// Diagnostic inspection must never block the caller's destroy/deallocate path
		}
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
