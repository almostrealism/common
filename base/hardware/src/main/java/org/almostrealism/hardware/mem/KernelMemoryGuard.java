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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * and {@link #releaseFor(Reservation)} methods to bracket kernel dispatch. What
 * acquiring returns is what releasing takes: the arguments are not consulted
 * again, because by then they may no longer name the memory they used.</p>
 *
 * <pre>{@code
 * KernelMemoryGuard.Reservation guard = KernelMemoryGuard.acquireFor(data);
 * try {
 *     // dispatch kernel...
 * } finally {
 *     KernelMemoryGuard.releaseFor(guard);
 * }
 * }</pre>
 *
 * <p>The deallocation pipeline in {@link HardwareMemoryProvider} consults
 * {@link #canDeallocate(long)} before freeing native memory, and holds the
 * release back while a kernel is still using the block. That verdict is only
 * worth acting on because the counts below are given back reliably — an
 * earlier attempt to act on them, made while they still leaked, waited for
 * something that never came and exhausted memory instead.</p>
 *
 * <p>Guarding only covers memory the guard can resolve to a {@link RAM}. An
 * argument it cannot resolve is reported when the kernel takes it, because
 * nothing later can report it: every check downstream is keyed by the address
 * that resolution would have produced.</p>
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
	 * What one kernel execution took, and must give back.
	 *
	 * <p>Holds the addresses that were counted, rather than the arguments they
	 * came from. An argument is not a reliable way to find its own memory again
	 * later: it may be destroyed while the kernel runs, and then it can name no
	 * address at all. Recording the addresses is what makes giving them back
	 * independent of what becomes of the arguments.</p>
	 *
	 * <p>The memory itself is held too, so that nothing counted here can be
	 * collected while the kernel is still reading it.</p>
	 */
	public static final class Reservation {
		/** The guard the addresses were counted against. */
		private final KernelMemoryGuard guard;

		/** The addresses counted, one entry per argument that had one. */
		private final List<Long> addresses;

		/** The memory behind those addresses, held so it cannot be collected. */
		private final List<RAM> held;

		/** Creates an empty reservation against the given guard. */
		private Reservation(KernelMemoryGuard guard) {
			this.guard = guard;
			this.addresses = new ArrayList<>();
			this.held = new ArrayList<>();
		}

		/**
		 * Notes that one more count was taken against the given address.
		 *
		 * @param address the address counted
		 * @param ram     the memory behind it, held until release
		 */
		private void record(long address, RAM ram) {
			addresses.add(address);
			held.add(ram);
		}
	}

	/**
	 * Registers all memory arguments as actively used by a kernel execution.
	 *
	 * <p>For each non-null argument with a resolvable {@link RAM} backing,
	 * increments the reference count for the native address and holds a strong
	 * reference to the {@link RAM} object to prevent garbage collection.</p>
	 *
	 * <p>An argument that cannot be resolved is reported rather than passed
	 * over: every check downstream is keyed by the address this would have
	 * produced, so nothing later can report it either.</p>
	 *
	 * @param args the kernel memory arguments (may contain nulls)
	 * @return what was taken, to be handed to {@link #release(Reservation)}
	 */
	public Reservation acquire(MemoryData... args) {
		Reservation reservation = new Reservation(this);
		if (args == null) return reservation;

		for (MemoryData arg : args) {
			if (arg == null) continue;

			RAM ram = resolveRAM(arg);
			if (ram == null) {
				warn("Kernel argument " + arg.getClass().getSimpleName() +
						" has no resolvable memory and cannot be guarded; a" +
						" kernel using it will not be protected from release");
				continue;
			}

			long address = ram.getContentPointer();

			activeReferences.compute(address, (k, existing) -> {
				AtomicInteger count = existing != null ? existing : new AtomicInteger(0);
				count.incrementAndGet();
				return count;
			});

			heldMemory.computeIfAbsent(address,
					k -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
					.add(ram);

			reservation.record(address, ram);
		}

		return reservation;
	}

	/**
	 * Gives back what a kernel execution took.
	 *
	 * <p>Decrements each address the matching {@link #acquire} recorded, and
	 * forgets an address once nothing is left holding it.</p>
	 *
	 * <p>The addresses come from the reservation rather than from the arguments
	 * because by now the arguments may name nothing: memory destroyed while the
	 * kernel ran leaves an argument that can no longer say which address it
	 * used, and a count that is never given back marks that address as in use
	 * for the life of the process. That is not hypothetical — rendering
	 * destroys its intermediates as a matter of course.</p>
	 *
	 * @param reservation what {@link #acquire} returned, or {@code null}
	 */
	public void release(Reservation reservation) {
		if (reservation == null) return;

		for (long address : reservation.addresses) {
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
	 * <p>Returns what was taken, to be handed back to
	 * {@link #releaseFor(Reservation)}, or {@code null} if no hardware or guard
	 * is available, or if acquisition fails for any reason. Guard failures are
	 * silently absorbed to avoid disrupting kernel execution.</p>
	 *
	 * @param data the kernel memory arguments
	 * @return the reservation, or {@code null}
	 */
	public static Reservation acquireFor(MemoryData[] data) {
		try {
			Hardware hw = Hardware.getLocalHardware();
			if (hw != null) {
				KernelMemoryGuard guard = hw.getKernelMemoryGuard();
				if (guard != null) {
					return guard.acquire(data);
				}
			}
		} catch (Exception e) {
			// Guard failures must not prevent kernel execution
		}
		return null;
	}

	/**
	 * Gives back what {@link #acquireFor(MemoryData[])} took.
	 *
	 * <p>No-op if the reservation is {@code null}. Release failures are
	 * silently absorbed to avoid disrupting kernel return.</p>
	 *
	 * @param reservation what was acquired, or {@code null}
	 */
	public static void releaseFor(Reservation reservation) {
		if (reservation == null) return;
		try {
			reservation.guard.release(reservation);
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
