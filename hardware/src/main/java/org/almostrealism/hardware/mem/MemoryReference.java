package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;

/**
 * Weak reference to {@link Memory} that preserves allocation stack trace for leak detection.
 *
 * <p>{@link MemoryReference} extends {@link WeakReference} to track native memory objects while
 * allowing them to be garbage collected when no strong references remain. It preserves the
 * allocation stack trace from {@link RAM} to enable debugging of memory leaks detected after
 * garbage collection.</p>
 *
 * <h2>Weak Reference Semantics</h2>
 *
 * <p>Unlike strong references, weak references do not prevent garbage collection:</p>
 * <pre>{@code
 * RAM memory = allocate(1000);
 * MemoryReference<RAM> ref = new NativeRef<>(memory, queue);
 *
 * // memory can still be GC'd even though ref exists
 * memory = null;  // No strong references remain
 * System.gc();    // GC may collect memory object
 *
 * // ref.get() returns null after GC
 * if (ref.get() == null) {
 *     // Object was collected
 *     // But ref preserves allocation trace for debugging
 *     StackTraceElement[] trace = ref.getAllocationStackTrace();
 * }
 * }</pre>
 *
 * <h2>Integration with ReferenceQueue</h2>
 *
 * <p>When the referenced memory is garbage collected, this reference is enqueued to the
 * {@link ReferenceQueue}, enabling automatic deallocation:</p>
 * <pre>{@code
 * ReferenceQueue<RAM> queue = new ReferenceQueue<>();
 * RAM memory = allocate(1000);
 * MemoryReference<RAM> ref = new NativeRef<>(memory, queue);
 *
 * memory = null;  // Release strong reference
 * System.gc();    // Trigger collection
 *
 * // Background thread processes queue
 * MemoryReference<RAM> collected = (MemoryReference<RAM>) queue.remove();
 * // collected == ref (now enqueued)
 * // Trigger native deallocation using preserved metadata
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
 * @see WeakReference
 * @see NativeRef
 * @see HardwareMemoryProvider
 */
public abstract class MemoryReference<T extends Memory> extends WeakReference<T> {
	private StackTraceElement[] allocationStackTrace;

	public MemoryReference(T referent, ReferenceQueue<? super T> q) {
		super(referent, q);
	}

	public StackTraceElement[] getAllocationStackTrace() {
		return allocationStackTrace;
	}

	public void setAllocationStackTrace(StackTraceElement[] allocationStackTrace) {
		this.allocationStackTrace = allocationStackTrace;
	}
}
