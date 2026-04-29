package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;
import org.almostrealism.io.SystemUtils;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

/**
 * Phantom reference to {@link Memory} that preserves allocation stack trace for leak detection.
 *
 * <p>{@link MemoryReference} extends {@link PhantomReference} to track native memory objects while
 * allowing them to be garbage collected when no strong references remain. It preserves the
 * allocation stack trace from {@link RAM} to enable debugging of memory leaks detected after
 * garbage collection.</p>
 *
 * <h2>Why PhantomReference (not WeakReference)</h2>
 *
 * <p>{@link PhantomReference} is safer than {@link java.lang.ref.WeakReference} for native memory
 * tracking because phantom references are only enqueued <em>after</em> the referent has been
 * finalized. This provides an additional safety window: if any finalizer (e.g.,
 * {@link org.almostrealism.hardware.mem.MemoryDataAdapter#finalize()}) needs to access the
 * native memory during finalization, the deallocation pipeline will not race with it.
 * With {@code WeakReference}, the reference is cleared and enqueued <em>before</em> finalization,
 * creating a window where native memory could be freed while a finalizer is still running.</p>
 *
 * <h2>Phantom Reference Semantics</h2>
 *
 * <p>{@link PhantomReference#get()} always returns {@code null}. Subclasses must cache any
 * metadata needed for post-GC cleanup (e.g., native address, size) in separate fields during
 * construction. See {@link NativeRef} for the standard pattern.</p>
 *
 * <h2>Integration with ReferenceQueue</h2>
 *
 * <p>A {@link ReferenceQueue} is <em>required</em> by {@link PhantomReference}. When the
 * referenced memory is garbage collected and finalized, this reference is enqueued to the
 * queue, enabling automatic deallocation:</p>
 * <pre>{@code
 * ReferenceQueue<RAM> queue = new ReferenceQueue<>();
 * RAM memory = allocate(1000);
 * MemoryReference<RAM> ref = new NativeRef<>(memory, queue);
 *
 * memory = null;  // Release strong reference
 * System.gc();    // Trigger collection and finalization
 *
 * // Background thread processes queue
 * MemoryReference<RAM> collected = (MemoryReference<RAM>) queue.remove();
 * // collected == ref (now enqueued, after finalization)
 * // Trigger native deallocation using cached metadata (address, size)
 * }</pre>
 *
 * <h2>Allocation Stack Trace Preservation</h2>
 *
 * <p>The allocation stack trace is copied from {@link RAM} during construction and preserved
 * even after the memory object is garbage collected:</p>
 * <pre>{@code
 * // At allocation
 * RAM memory = new DirectBuffer(1000);  // Line 42 in MyClass.java
 * MemoryReference<RAM> ref = new NativeRef<>(memory, queue);
 * ref.setAllocationStackTrace(memory.getAllocationStackTrace());
 *
 * // After GC
 * memory = null;
 * System.gc();
 *
 * // Stack trace still available for leak debugging
 * StackTraceElement[] trace = ref.getAllocationStackTrace();
 * // Shows: MyClass.java:42
 * }</pre>
 *
 * @param <T> Memory type, must extend {@link Memory}
 * @see PhantomReference
 * @see NativeRef
 * @see HardwareMemoryProvider
 */
public abstract class MemoryReference<T extends Memory> extends PhantomReference<T> {
	/**
	 * If true, the memory provider dispatch path captures a stack trace at the point
	 * of first deallocation, in addition to the cheap volatile flag guard. The volatile
	 * guard is always active; this flag controls only the stack trace capture (which is
	 * the more expensive part). Controlled by the
	 * {@code AR_HARDWARE_DOUBLE_FREE_DETECTION} system property, which accepts
	 * {@code enabled} or {@code disabled} (per {@link SystemUtils#isEnabled(String)});
	 * if the property is unset, defaults to enabled.
	 */
	public static boolean captureFreeStackTrace =
			SystemUtils.isEnabled("AR_HARDWARE_DOUBLE_FREE_DETECTION").orElse(true);

	/** Stack trace captured at allocation time, stored for post-GC leak reporting. */
	private StackTraceElement[] allocationStackTrace;

	/**
	 * True once the backend has been instructed to release this memory. Used by the
	 * double-free guard in {@link HardwareMemoryProvider#deallocateNow} to skip a
	 * second native release of the same address. Volatile so concurrent deallocators
	 * see the flag even outside the synchronized claim.
	 */
	private volatile boolean freed;

	/** Stack trace captured at the first deallocation, when {@link #captureFreeStackTrace} is enabled. */
	private StackTraceElement[] firstFreeStackTrace;

	/**
	 * Creates a phantom reference for the given memory object, registered with the given queue.
	 *
	 * @param referent The memory object to track
	 * @param q        Reference queue that receives this reference after GC
	 */
	public MemoryReference(T referent, ReferenceQueue<? super T> q) {
		super(referent, q);
	}

	/**
	 * Returns whether this reference has already been claimed by a deallocator.
	 *
	 * @return true if the backend release path has been entered for this reference
	 */
	public boolean isFreed() { return freed; }

	/**
	 * Returns the stack trace captured at the first successful claim of {@link #tryClaimFreed},
	 * or null if {@link #captureFreeStackTrace} was disabled when the claim was made.
	 *
	 * @return Stack trace from the first free, or null
	 */
	public StackTraceElement[] getFirstFreeStackTrace() { return firstFreeStackTrace; }

	/**
	 * Atomically claims this reference for deallocation. The first caller wins and
	 * receives true; concurrent or subsequent callers receive false and must skip
	 * calling the backend release path. When {@link #captureFreeStackTrace} is true,
	 * the winning caller also has its current stack trace recorded for diagnostic
	 * reporting on a later double-free attempt.
	 *
	 * <p>If the backend release fails, the caller should invoke {@link #unclaimFreed()}
	 * to allow a future deallocation attempt to retry. Otherwise the reference would
	 * remain marked as freed even though the underlying memory was never released,
	 * permanently leaking the block.</p>
	 *
	 * @return true if this caller is responsible for releasing the underlying memory,
	 *         false if a previous caller has already claimed responsibility
	 */
	public synchronized boolean tryClaimFreed() {
		if (freed) return false;
		if (captureFreeStackTrace) {
			firstFreeStackTrace = Thread.currentThread().getStackTrace();
		}
		freed = true;
		return true;
	}

	/**
	 * Reverses a previous successful claim of {@link #tryClaimFreed()} when the
	 * backend release call failed. Restores the reference to an unfreed state so
	 * that a subsequent attempt (e.g. via the GC-driven deallocation path) can
	 * retry. The first-free stack trace, if captured, is also cleared so that
	 * a later genuine double-free can record fresh diagnostic context.
	 */
	public synchronized void unclaimFreed() {
		freed = false;
		firstFreeStackTrace = null;
	}

	/**
	 * Returns the allocation stack trace captured when the tracked memory was created.
	 *
	 * @return Stack trace elements, or null if stack tracing was disabled
	 */
	public StackTraceElement[] getAllocationStackTrace() {
		return allocationStackTrace;
	}

	/**
	 * Sets the allocation stack trace for leak reporting.
	 *
	 * @param allocationStackTrace Stack trace from the allocation site
	 */
	public void setAllocationStackTrace(StackTraceElement[] allocationStackTrace) {
		this.allocationStackTrace = allocationStackTrace;
	}
}
