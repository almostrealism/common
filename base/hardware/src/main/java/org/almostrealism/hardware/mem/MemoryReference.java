package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;

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
	/** Stack trace captured at allocation time, stored for post-GC leak reporting. */
	private StackTraceElement[] allocationStackTrace;

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
