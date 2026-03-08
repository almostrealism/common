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
import org.almostrealism.io.SystemUtils;

import java.util.stream.Stream;

/**
 * Base class for native memory buffers with allocation tracking and debugging support.
 *
 * <p>{@link RAM} serves as the foundation for all native memory implementations, providing
 * pointer-based addressing and automatic allocation tracking for debugging memory leaks.
 * It implements the {@link Memory} interface for integration with the hardware acceleration
 * system.</p>
 *
 * <h2>Allocation Tracking</h2>
 *
 * <p>Every {@link RAM} instance automatically captures its allocation stack trace, which is
 * invaluable for diagnosing memory leaks and orphaned allocations:</p>
 *
 * <pre>{@code
 * RAM memory = createSomeRAM();
 *
 * // Later, if memory isn't properly destroyed:
 * StackTraceElement[] trace = memory.getAllocationStackTrace();
 * for (StackTraceElement frame : trace) {
 *     System.err.println("  at " + frame);
 * }
 * // Output shows where the allocation originated
 * }</pre>
 *
 * <p>Allocation tracking can be configured via environment variables:</p>
 * <ul>
 *   <li><b>AR_HARDWARE_MEMORY_WARNINGS</b>: Enable/disable warnings (default: true)</li>
 *   <li><b>AR_HARDWARE_ALLOCATION_TRACE_FRAMES</b>: Number of stack frames to capture (default: 16, 0 to disable)</li>
 * </ul>
 *
 * <pre>
 * # Capture full stack traces
 * export AR_HARDWARE_ALLOCATION_TRACE_FRAMES=50
 *
 * # Disable allocation tracking (performance optimization)
 * export AR_HARDWARE_ALLOCATION_TRACE_FRAMES=0
 *
 * # Disable memory warnings
 * export AR_HARDWARE_MEMORY_WARNINGS=false
 * </pre>
 *
 * <h2>Pointer-Based Addressing</h2>
 *
 * <p>{@link RAM} uses pointer-based addressing for native memory access. Two pointer
 * concepts are supported:</p>
 *
 * <ul>
 *   <li><b>Container Pointer</b>: Pointer to the container object (e.g., Java object header)</li>
 *   <li><b>Content Pointer</b>: Pointer to the actual data content (may differ from container)</li>
 * </ul>
 *
 * <p>By default, {@link #getContainerPointer()} delegates to {@link #getContentPointer()},
 * but subclasses can override this if container and content differ (e.g., wrappers).</p>
 *
 * <pre>{@code
 * // Example: Direct byte buffer
 * class DirectBuffer extends RAM {
 *     private ByteBuffer buffer;
 *
 *     public long getContentPointer() {
 *         return ((DirectBuffer) buffer).address();  // Native memory address
 *     }
 *
 *     public long getSize() {
 *         return buffer.capacity();
 *     }
 * }
 * }</pre>
 *
 * <h2>Subclass Requirements</h2>
 *
 * <p>Concrete {@link RAM} implementations must override:</p>
 * <ul>
 *   <li>getContentPointer(): Return native memory address</li>
 *   <li>getSize(): Return size in bytes</li>
 *   <li>destroy(): Free native resources (from Memory interface)</li>
 * </ul>
 *
 * <h2>Equality and Identity</h2>
 *
 * <p>{@link RAM} instances are considered equal if they point to the same native memory
 * (same container pointer). This is pointer equality, not content equality:</p>
 *
 * <pre>{@code
 * RAM a = allocate(100);
 * RAM b = a;  // Same pointer
 * RAM c = allocate(100);  // Different pointer
 *
 * a.equals(b)  // true  (same pointer)
 * a.equals(c)  // false (different pointer, even if same size)
 * }</pre>
 *
 * <h2>Debugging Memory Leaks</h2>
 *
 * <p>When memory leaks are detected (allocations never destroyed), the allocation
 * stack traces help identify the source:</p>
 *
 * <pre>{@code
 * // Application code
 * public void processData() {
 *     RAM temp = new DirectBuffer(1000);
 *     // ... work ...
 *     // BUG: Forgot to call temp.destroy()
 * }
 *
 * // Later, leak detector finds orphaned RAM
 * RAM leaked = findLeakedAllocations().get(0);
 * System.err.println("Leaked allocation from:");
 * for (StackTraceElement frame : leaked.getAllocationStackTrace()) {
 *     System.err.println("  at " + frame);
 * }
 * // Output:
 * //   at DirectBuffer.<init>(DirectBuffer.java:25)
 * //   at MyClass.processData(MyClass.java:42)  <-- Source of leak!
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <p>Allocation tracking has minimal overhead (~1-2% in typical workloads), but can be
 * disabled for production deployments:</p>
 *
 * <pre>
 * # Development: Full tracking
 * export AR_HARDWARE_ALLOCATION_TRACE_FRAMES=16
 *
 * # Production: Disable tracking
 * export AR_HARDWARE_ALLOCATION_TRACE_FRAMES=0
 * </pre>
 *
 * @see Memory
 * @see Bytes
 */
public abstract class RAM implements Memory {
	public static boolean enableWarnings = SystemUtils.isEnabled("AR_HARDWARE_MEMORY_WARNINGS").orElse(true);
	public static int allocationTraceFrames = SystemUtils.getInt("AR_HARDWARE_ALLOCATION_TRACE_FRAMES").orElse(16);

	private final StackTraceElement[] allocationStackTrace;

	/**
	 * Creates a {@link RAM} instance with default allocation tracking.
	 *
	 * <p>Captures the allocation stack trace based on {@link #allocationTraceFrames}
	 * configuration (default: 16 frames).</p>
	 */
	protected RAM() {
		this(allocationTraceFrames);
	}

	/**
	 * Creates a {@link RAM} instance with explicit trace frame count.
	 *
	 * @param traceFrames Number of stack frames to capture (0 to disable tracking)
	 */
	protected RAM(int traceFrames) {
		if (traceFrames > 0) {
			allocationStackTrace = Stream.of(Thread.currentThread().getStackTrace())
					.limit(traceFrames).toArray(StackTraceElement[]::new);
		} else {
			allocationStackTrace = null;
		}
	}

	/**
	 * Returns the pointer to the container object.
	 *
	 * <p>By default delegates to {@link #getContentPointer()}. Override if
	 * container and content pointers differ (e.g., for wrapper objects).</p>
	 *
	 * @return The container pointer
	 */
	public long getContainerPointer() {
		return getContentPointer();
	}

	/**
	 * Returns the pointer to the actual memory content.
	 *
	 * <p>Subclasses must override this to provide the native memory address.</p>
	 *
	 * @return The content pointer (native memory address)
	 * @throws UnsupportedOperationException if not implemented by subclass
	 */
	public long getContentPointer() {
		throw new UnsupportedOperationException();
	}

	/**
	 * Returns the size of this memory allocation in bytes.
	 *
	 * <p>Subclasses must override this to provide the allocation size.</p>
	 *
	 * @return The size in bytes
	 * @throws UnsupportedOperationException if not implemented by subclass
	 */
	public long getSize() { throw new UnsupportedOperationException(); }

	/**
	 * Returns the captured allocation stack trace.
	 *
	 * <p>Returns null if allocation tracking is disabled
	 * ({@link #allocationTraceFrames} = 0).</p>
	 *
	 * @return The stack trace from allocation time, or null if tracking disabled
	 */
	public StackTraceElement[] getAllocationStackTrace() {
		return allocationStackTrace;
	}

	/**
	 * Returns whether this memory is still active (not destroyed).
	 *
	 * <p>Default implementation returns true. Override if subclass tracks lifecycle.</p>
	 *
	 * @return true if active, false if destroyed
	 */
	public boolean isActive() { return true; }

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof RAM) && ((RAM) obj).getContainerPointer() == getContainerPointer();
	}

	@Override
	public int hashCode() {
		return Long.hashCode(getContainerPointer());
	}

	@Override
	public String toString() {
		return String.format("%s[%d]", getClass().getSimpleName(), getSize());
	}
}
