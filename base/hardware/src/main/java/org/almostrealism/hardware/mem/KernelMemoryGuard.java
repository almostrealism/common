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
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
 * <p>Kernel execution backends take a {@link Reservation} from the guard of the local
 * {@link Hardware} to bracket kernel dispatch, and give it back when the kernel is done.
 * What acquiring returns is what releasing takes: the arguments are not consulted again,
 * because by then they may no longer name the memory they used.</p>
 *
 * <pre>{@code
 * KernelMemoryGuard.Reservation reservation =
 *         Hardware.getLocalHardware().getKernelMemoryGuard().acquire(data);
 * try {
 *     // dispatch kernel...
 * } finally {
 *     reservation.release();
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
 * <h2>What this does not protect</h2>
 *
 * <p>This is a defense-in-depth layer around the standard backend operators, not
 * a global invariant on all native memory. It does not protect:</p>
 * <ul>
 *   <li><strong>An unresolvable argument.</strong> {@link #acquire(MemoryData...)}
 *   warns and skips an argument whose memory it cannot resolve to a {@link RAM};
 *   a kernel using that argument runs unguarded.</li>
 *   <li><strong>A caller that frees anyway.</strong>
 *   {@link #warnIfReserved(String, long, StackTraceElement[])} is
 *   diagnostic only &mdash; it never throws or blocks, so it does not turn
 *   {@link #canDeallocate(long)} into a hard barrier.</li>
 *   <li><strong>A dispatch that was never bracketed</strong> with
 *   {@link #acquire(MemoryData...)}/{@link Reservation#release()}.</li>
 *   <li><strong>A kernel-execution guard that outlives the deferred-release timeout.</strong>
 *   A non-zero execution count holds a release back only up to
 *   {@code HardwareMemoryProvider.deferredReleaseTimeoutMs} (30&nbsp;s by default);
 *   past that, {@code HardwareMemoryProvider.sweepDeferred()} frees the block anyway
 *   (with a warning), so a hung or leaked dispatch is not protected indefinitely. A
 *   {@linkplain #acquireScheduled(MemoryData...) scheduling lease} is exempt from this
 *   backstop: its scheduling-to-execution window may legitimately outlast the timeout, so it
 *   holds the release back until it is explicitly given back.</li>
 * </ul>
 *
 * <p>The internals doc on the native runtime lifecycle covers the exact
 * use-after-free race this closes and the ones it leaves open.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>All operations are thread-safe. Each address has one record of both its
 * kernel-execution count and its scheduling-lease count, changed only inside
 * {@link ConcurrentHashMap#compute} for that address and read as a single
 * {@link ReservationState}, so a reservation handed from a lease to an execution is never
 * observed as neither.</p>
 *
 * @see HardwareMemoryProvider
 * @see org.almostrealism.hardware.Hardware#getKernelMemoryGuard()
 */
public class KernelMemoryGuard implements ConsoleFeatures {

	/**
	 * Reservations per native allocation, keyed by {@link RAM#getContainerPointer()} as every
	 * check from a provider is. An address is present only while something holds it.
	 */
	private final ConcurrentHashMap<Long, AddressReservations> reservations;

	/**
	 * Creates a new {@link KernelMemoryGuard} with nothing reserved.
	 */
	public KernelMemoryGuard() {
		this.reservations = new ConcurrentHashMap<>();
	}

	/**
	 * What one kernel execution or scheduling lease took, and must give back.
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

		/** Whether this is a scheduling lease rather than a kernel-execution guard. */
		private final boolean scheduled;

		/** The addresses counted, one entry per argument that had one. */
		private final List<Long> addresses;

		/** The memory behind those addresses, held so it cannot be collected. */
		private final List<RAM> held;

		/** The memory reserved for each argument, by argument identity. */
		private final Map<MemoryData, RAM> leased;

		/** Creates an empty reservation against the given guard. */
		private Reservation(KernelMemoryGuard guard, boolean scheduled) {
			this.guard = guard;
			this.scheduled = scheduled;
			this.addresses = new ArrayList<>();
			this.held = new ArrayList<>();
			this.leased = new IdentityHashMap<>();
		}

		/**
		 * Notes that one more count was taken against the given address.
		 *
		 * @param address the address counted
		 * @param ram     the memory behind it, held until release
		 */
		private synchronized void record(long address, RAM ram) {
			addresses.add(address);
			held.add(ram);
		}

		/**
		 * Returns what this reservation holds, as a snapshot that later extensions do not change.
		 *
		 * @return the addresses and memory counted so far, in matching order
		 */
		private synchronized List<Map.Entry<Long, RAM>> counted() {
			List<Map.Entry<Long, RAM>> counted = new ArrayList<>(addresses.size());
			for (int i = 0; i < addresses.size(); i++) {
				counted.add(Map.entry(addresses.get(i), held.get(i)));
			}
			return counted;
		}

		/**
		 * Returns a reference to the given argument for work that runs later, which yields the
		 * argument itself for as long as it is backed by memory, and a view of the memory this
		 * reservation holds for it once it has been destroyed.
		 *
		 * <p>Yielding the argument itself lets the deferred work observe what happened to it in
		 * the meantime, and lets argument preparation move the argument (not a copy of it) to a
		 * provider the work supports, so what the work writes reaches the argument. The memory is
		 * read once when the reference is resolved. If the argument has moved to memory this
		 * reservation does not cover, the reservation is extended to that memory first, so
		 * everything the deferred work can reach stays reserved until the reservation is
		 * released.</p>
		 *
		 * @param data an argument this reservation was acquired for
		 * @return a supplier resolving the argument when the deferred work runs
		 */
		public Supplier<MemoryData> deferredReference(MemoryData data) {
			RAM reserved = reservedFor(data);
			if (reserved == null) return () -> data;

			MemoryData reservedView = data.detachedView(reserved);
			return () -> {
				Memory current = data.getMem();
				if (current == null) return reservedView;
				if (current != reserved && !extendTo(data, current)) return reservedView;
				return data;
			};
		}

		/**
		 * Returns a reference to the given argument for work that runs later and does not need
		 * the argument object itself, which always yields a view bound to reserved memory.
		 *
		 * <p>The view is of the memory backing the argument when the reference is resolved, or
		 * of the memory this reservation holds for it if the argument has since been destroyed.
		 * Unlike {@link #deferredReference(MemoryData)}, nothing the reference yields can lose
		 * its memory to a concurrent destroy between being resolved and being used.</p>
		 *
		 * @param data an argument this reservation was acquired for
		 * @return a supplier resolving a view of the argument when the deferred work runs
		 */
		public Supplier<MemoryData> detachedReference(MemoryData data) {
			RAM reserved = reservedFor(data);
			if (reserved == null) return () -> data;

			MemoryData reservedView = data.detachedView(reserved);
			return () -> {
				Memory current = data.getMem();
				if (current == null || current == reserved || !extendTo(data, current)) return reservedView;
				return data.detachedView(current);
			};
		}

		/**
		 * Returns the deferred arguments for an operation that runs later, resolved when the
		 * returned supplier is invoked: each {@link MemoryData} argument through its
		 * {@link #deferredReference(MemoryData) deferred reference}, every other entry
		 * (including {@code null}) unchanged so argument preparation still reports it.
		 *
		 * @param args the raw arguments this reservation was acquired for, or {@code null}
		 * @return a supplier of the arguments to run with
		 */
		public Supplier<Object[]> deferredArguments(Object[] args) {
			if (args == null) return () -> null;

			List<Supplier<?>> references = new ArrayList<>(args.length);
			for (Object arg : args) {
				references.add(arg instanceof MemoryData data ? deferredReference(data) : () -> arg);
			}

			return () -> references.stream().map(Supplier::get).toArray();
		}

		/**
		 * Releases what resolving {@link #deferredArguments(Object[])} produced, once the work
		 * that used the resolved arguments has settled.
		 *
		 * <p>An argument destroyed before the work ran was resolved to a view, and argument
		 * preparation may since have moved that view's root to a supported provider, giving it a
		 * replacement allocation that nothing else references. Destroying the root of each such
		 * view frees that replacement; it never frees the memory the view was bound to, which
		 * remains with the (destroyed) argument and this reservation. Arguments resolved to
		 * themselves are left alone. Every root is destroyed even if one destroy throws.</p>
		 *
		 * @param args     the raw arguments given to {@link #deferredArguments(Object[])}, or {@code null}
		 * @param resolved what its supplier returned, or {@code null} if it was never invoked
		 */
		public void releaseResolvedViews(Object[] args, Object[] resolved) {
			if (args == null || resolved == null) return;

			List<Runnable> releases = new ArrayList<>(resolved.length);
			for (int i = 0; i < resolved.length; i++) {
				if (resolved[i] != args[i] && resolved[i] instanceof MemoryData view) {
					releases.add(() -> view.getRootDelegate().destroy());
				}
			}

			Destroyable.releaseAll(releases);
		}

		/**
		 * Gives back everything this reservation took from its guard.
		 */
		public void release() {
			guard.release(this);
		}

		/**
		 * Returns the memory this reservation holds for the given argument.
		 *
		 * @param data the argument
		 * @return the reserved memory, or {@code null} if none was reserved for it
		 */
		private synchronized RAM reservedFor(MemoryData data) {
			return leased.get(data);
		}

		/**
		 * Extends this reservation to memory an argument has moved to since it was reserved,
		 * and confirms the memory was not released before the extension took hold.
		 *
		 * @param data    the argument
		 * @param current the memory now backing it
		 * @return true if the memory is now reserved and usable; false if it cannot be used
		 */
		private boolean extendTo(MemoryData data, Memory current) {
			if (!(current instanceof RAM ram)) return false;

			guard.reserve(this, ram);
			synchronized (this) {
				leased.put(data, ram);
			}

			MemoryProvider provider = current.getProvider();
			return provider == null || !provider.isReleased(current);
		}
	}

	/**
	 * The reservations held against one address. Every change is made inside the guard's
	 * per-address {@link ConcurrentHashMap#compute} and every read through
	 * {@link #state()}, both under this object's monitor, so the two counts always change and
	 * are observed together.
	 */
	private static final class AddressReservations {
		/** Kernel executions using the memory. */
		private int executions;

		/** Scheduling leases covering the memory. */
		private int leases;

		/** The memory held against collection, one entry per reservation. */
		private final List<RAM> held = new ArrayList<>();

		/**
		 * Records one more reservation.
		 *
		 * @param scheduled whether it is a scheduling lease
		 * @param ram       the memory to hold
		 */
		synchronized void add(boolean scheduled, RAM ram) {
			if (scheduled) leases++; else executions++;
			held.add(ram);
		}

		/**
		 * Gives back one reservation.
		 *
		 * @param scheduled whether it is a scheduling lease
		 * @param ram       the memory it held
		 * @return true if nothing remains reserved
		 */
		synchronized boolean remove(boolean scheduled, RAM ram) {
			if (scheduled) leases--; else executions--;
			held.remove(ram);
			return executions <= 0 && leases <= 0;
		}

		/**
		 * Returns both counts as they are at this moment.
		 *
		 * @return the current state
		 */
		synchronized ReservationState state() {
			return new ReservationState(executions, leases);
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
	 * @return what was taken, to be given back with {@link Reservation#release()}
	 */
	public Reservation acquire(MemoryData... args) {
		return acquire(false, args);
	}

	/**
	 * Registers all memory arguments as covered by a scheduling lease: an asynchronous
	 * operation that has been scheduled against them but not yet executed.
	 *
	 * <p>A lease blocks deallocation the same way {@link #acquire(MemoryData...)} does, but
	 * {@link HardwareMemoryProvider} never force-expires it. It covers the
	 * scheduling-to-execution window of a deferred dispatch or copy, which waits on a foreign
	 * dependency and so may legitimately outlast the deferred-release backstop that a
	 * millisecond-scale kernel execution never reaches.</p>
	 *
	 * <p>Give it back at whichever of two points ends that window, so the lease is never
	 * retained across the execution it was meant to precede:</p>
	 * <ul>
	 *   <li>once the deferred work has taken its own execution reservation, if it does
	 *   &mdash; a deferred kernel dispatch releases its lease the moment the dispatch has
	 *   acquired an {@link #acquire(MemoryData...) execution guard}, handing the memory to that
	 *   guard (and its backstop) rather than exempting it for a possibly-unbounded kernel run;</li>
	 *   <li>otherwise once the deferred work has settled &mdash; a scheduled copy, which has no
	 *   separate execution reservation, and the dependency-failure path of a deferred dispatch,
	 *   where the work never runs, both release through {@link io.almostrealism.streams.Semaphore#whenSettled}.</li>
	 * </ul>
	 *
	 * @param args the memory arguments (may contain nulls)
	 * @return what was taken, to be given back with {@link Reservation#release()}
	 */
	public Reservation acquireScheduled(MemoryData... args) {
		return acquire(true, args);
	}

	/**
	 * Registers all memory arguments against either the kernel-execution counts or the
	 * scheduling-lease counts, depending on {@code scheduled}.
	 *
	 * <p>Each argument is counted against its allocation's {@link RAM#getContainerPointer()
	 * container pointer}, because that is the address a {@link HardwareMemoryProvider} tracks
	 * an allocation by and asks about when releasing it. For a backend whose container is not
	 * its contents &mdash; a Metal buffer object versus the bytes it holds &mdash; counting the
	 * content pointer would never match that query, and the memory would go unprotected.</p>
	 *
	 * @param scheduled whether to record a scheduling lease rather than a kernel-execution guard
	 * @param args      the memory arguments (may contain nulls)
	 * @return what was taken, to be given back with {@link Reservation#release()}
	 */
	private Reservation acquire(boolean scheduled, MemoryData... args) {
		Reservation reservation = new Reservation(this, scheduled);
		if (args == null) return reservation;

		for (MemoryData arg : args) {
			if (arg == null || isUnguarded(arg)) continue;

			RAM ram = resolveRAM(arg);
			if (ram == null) {
				warn("Kernel argument " + arg.getClass().getSimpleName() +
						" has no resolvable memory and cannot be guarded; a" +
						" kernel using it will not be protected from release");
				continue;
			}

			reserve(reservation, ram);
			synchronized (reservation) {
				reservation.leased.put(arg, ram);
			}
		}

		return reservation;
	}

	/**
	 * Adds one count against the given memory to a reservation.
	 *
	 * @param reservation the reservation the count belongs to
	 * @param ram         the memory to count
	 */
	private void reserve(Reservation reservation, RAM ram) {
		long address = ram.getContainerPointer();
		reservations.compute(address, (k, existing) -> {
			AddressReservations entry = existing != null ? existing : new AddressReservations();
			entry.add(reservation.scheduled, ram);
			return entry;
		});

		reservation.record(address, ram);
	}

	/**
	 * Takes a scheduling lease over the memory arguments among a raw operator argument array,
	 * without the validation or provider migration argument preparation performs: a dependency
	 * may still be writing the arguments, so preparing them now would be incorrect.
	 * {@code null} and non-{@link MemoryData} entries are skipped.
	 *
	 * @param args the raw arguments, or {@code null}
	 * @return the lease, to be given back with {@link Reservation#release()}
	 */
	public Reservation leaseArguments(Object[] args) {
		if (args == null) return acquireScheduled();

		return acquireScheduled(Arrays.stream(args)
				.filter(MemoryData.class::isInstance)
				.map(MemoryData.class::cast)
				.toArray(MemoryData[]::new));
	}

	/**
	 * Gives back what a kernel execution or scheduling lease took.
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

		for (Map.Entry<Long, RAM> counted : reservation.counted()) {
			reservations.computeIfPresent(counted.getKey(),
					(k, entry) -> entry.remove(reservation.scheduled, counted.getValue()) ? null : entry);
		}
	}

	/**
	 * Returns the reservations held against the given address, with both counts read at the
	 * same moment.
	 *
	 * @param address the native memory address to check
	 * @return the current state, or {@link ReservationState#NONE} if nothing holds the address
	 */
	public ReservationState stateOf(long address) {
		AddressReservations entry = reservations.get(address);
		return entry == null ? ReservationState.NONE : entry.state();
	}

	/**
	 * Checks whether native memory at the given address can be safely deallocated.
	 *
	 * @param address the native memory address to check
	 * @return {@code true} if deallocation is safe, {@code false} if a kernel or a scheduling
	 *         lease is still holding the memory
	 */
	public boolean canDeallocate(long address) {
		return stateOf(address).isReleasable();
	}

	/**
	 * Returns whether native memory at the given address is held by a scheduling lease.
	 *
	 * @param address the native memory address to check
	 * @return {@code true} if a scheduling lease still holds the memory
	 */
	public boolean isScheduled(long address) {
		return stateOf(address).isLeased();
	}

	/**
	 * Emits a warning if the given native address is still reserved by a kernel execution or
	 * a scheduling lease. This is a <em>diagnostic-only</em> check: it never throws, never
	 * blocks, and does not prevent the caller from proceeding with deallocation. Callers that
	 * want to avoid an imminent use-after-free must decide how to react on their own (defer,
	 * retry, etc.) — this only surfaces the condition.
	 *
	 * <p>When the allocation stack trace is available (controlled by
	 * {@code AR_HARDWARE_ALLOCATION_TRACE_FRAMES}) it is included in the warning
	 * so the developer can see where the memory about to be freed was allocated.</p>
	 *
	 * @param context         short description of the destroy path (e.g. {@code "NativeBuffer"},
	 *                        {@code "NativeMemory"}) used to identify the source of the warning
	 * @param address         the container pointer of the allocation about to be freed
	 * @param allocationTrace the allocation stack trace captured at RAM creation time, may be null
	 */
	public void warnIfReserved(String context, long address, StackTraceElement[] allocationTrace) {
		if (canDeallocate(address)) return;

		warn(context + " at 0x" + Long.toHexString(address) +
				" is being deallocated while the KernelMemoryGuard still " +
				"reports active kernel references; in-flight kernels may " +
				"read from unmapped memory");
		if (allocationTrace != null && allocationTrace.length > 0) {
			StringBuilder sb = new StringBuilder("  (allocated at:");
			for (StackTraceElement el : allocationTrace) {
				sb.append("\n    at ").append(el);
			}
			sb.append(")");
			warn(sb.toString());
		}
	}

	/**
	 * Returns true if an argument's memory is present but is not native {@link RAM}, such as memory
	 * on the JVM heap. A provider never frees such memory while it is still referenced, so there is
	 * nothing for this guard to protect, and the argument is skipped without a warning. (An argument
	 * with no memory at all is a different case: it is reported, because a kernel using it is
	 * reading memory that is gone.)
	 *
	 * @param data the argument
	 * @return whether the argument's memory needs no guarding
	 */
	private static boolean isUnguarded(MemoryData data) {
		try {
			Memory mem = data.getMem();
			return mem != null && !(mem instanceof RAM);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Resolves the native {@link RAM} behind an argument, or {@code null} if it has none: its
	 * memory is gone (destroyed, or failing to report itself), or is not native memory at all
	 * (see {@link #isUnguarded}).
	 *
	 * @param data the argument
	 * @return the native memory behind it, or {@code null}
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
