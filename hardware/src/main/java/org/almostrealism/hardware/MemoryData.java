/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalOrdering;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.relation.Node;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.DoublePredicate;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 * The fundamental interface for hardware-accessible data in the Almost Realism framework.
 *
 * <p>{@link MemoryData} represents a contiguous region of memory that can be accessed
 * by both CPU and hardware accelerators (GPU/OpenCL/Metal). It provides a unified abstraction
 * for data that may reside in various memory spaces (JVM heap, native memory, GPU memory)
 * while exposing consistent access patterns for reading, writing, and manipulation.</p>
 *
 * <h2>Core Concepts</h2>
 *
 * <h3>Memory Abstraction</h3>
 * <p>{@link MemoryData} wraps a {@link Memory} object which handles the low-level details
 * of where data actually resides. This allows data structures to be:
 * <ul>
 *   <li>Allocated in JVM heap for CPU-only operations</li>
 *   <li>Allocated in native memory for JNI/C interop</li>
 *   <li>Allocated in GPU memory for accelerated computations</li>
 *   <li>Transparently moved between memory spaces as needed</li>
 * </ul>
 *
 * <h3>Delegation Pattern</h3>
 * <p>A key feature of {@link MemoryData} is the delegation mechanism, which enables:
 * <ul>
 *   <li><strong>Memory Sharing:</strong> Multiple {@link MemoryData} objects can reference
 *       different regions of the same underlying {@link Memory}</li>
 *   <li><strong>Zero-Copy Views:</strong> Create sub-ranges or reinterpretations without
 *       copying data</li>
 *   <li><strong>Complex Layouts:</strong> Implement strided access, transposition, and
 *       other memory layouts via {@link TraversalOrdering}</li>
 * </ul>
 *
 * <p><strong>Example:</strong> A matrix can be stored in row-major layout, while column
 * views are created via delegation with custom ordering - no data copying required.</p>
 *
 * <h3>Hardware Acceleration Integration</h3>
 * <p>All {@link MemoryData} instances are designed to participate in hardware-accelerated
 * computations. The interface provides:
 * <ul>
 *   <li>{@link #getMem()} - Access to underlying hardware-accessible memory</li>
 *   <li>{@link #getOffset()} - Offset into memory for this view (handles delegation chain)</li>
 *   <li>{@link #getMemLength()} - Number of double-precision values this view represents</li>
 * </ul>
 *
 * <h2>Memory Layout and Ordering</h2>
 *
 * <h3>Direct vs Ordered Access</h3>
 * <p>When {@link #getDelegateOrdering()} returns null, memory is accessed directly:</p>
 * <pre>{@code
 * // Direct access: value at logical index i is at memory[offset + i]
 * double value = memData.toDouble(i);  // Direct memory[offset + i]
 * }</pre>
 *
 * <p>With a {@link TraversalOrdering}, logical indices map to physical memory locations:</p>
 * <pre>{@code
 * // Ordered access: logical index i maps through ordering
 * TraversalOrdering order = ...;  // e.g., column-major for row-major data
 * memData.setDelegate(baseData, 0, order);
 * double value = memData.toDouble(i);  // Maps i through ordering
 * }</pre>
 *
 * <h3>Delegation Chains</h3>
 * <p>{@link MemoryData} objects can delegate to other {@link MemoryData} objects, forming
 * chains. Offsets accumulate through the chain:</p>
 * <pre>
 * Base Memory: [===================]  10,000 doubles
 * Delegate A:      [==========]       offset 100, length 5000
 * Delegate B:         [====]          offset 50 from A
 * Final offset: 100 + 50 = 150 from base
 * </pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Reading and Writing Data</h3>
 * <pre>{@code
 * // Write data
 * MemoryData data = ...;
 * data.setMem(new double[] {1.0, 2.0, 3.0, 4.0});
 *
 * // Read data
 * double[] values = data.toArray();
 * double single = data.toDouble(2);  // Get value at index 2
 *
 * // Stream processing
 * double sum = data.doubleStream().sum();
 * }</pre>
 *
 * <h3>Creating Views and Sub-Ranges</h3>
 * <pre>{@code
 * MemoryData fullArray = ...;  // 1000 elements
 *
 * // Create a view of elements 100-199 (zero-copy)
 * MemoryData subRange = new MemoryDataAdapter() {
 *     @Override
 *     public int getMemLength() { return 100; }
 * };
 * subRange.setDelegate(fullArray, 100);
 *
 * // Modifications to subRange affect fullArray
 * subRange.setMem(0, 42.0);  // Sets fullArray[100] = 42.0
 * }</pre>
 *
 * <h3>Persistence and Serialization</h3>
 * <pre>{@code
 * // Save to byte array
 * byte[] bytes = data.persist();
 *
 * // Save to stream
 * try (OutputStream out = new FileOutputStream("data.bin")) {
 *     data.persist(out);
 * }
 *
 * // Load from byte array
 * data.read(bytes);
 *
 * // Load from stream
 * try (InputStream in = new FileInputStream("data.bin")) {
 *     data.read(in);
 * }
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <h3>Memory Access Patterns</h3>
 * <ul>
 *   <li><strong>Direct Access:</strong> When delegation ordering is null, access is O(1)
 *       with minimal overhead</li>
 *   <li><strong>Ordered Access:</strong> With {@link TraversalOrdering}, each access incurs
 *       index mapping overhead - prefer bulk operations ({@link #toArray()}) for better
 *       performance</li>
 *   <li><strong>Delegation Chains:</strong> Keep chains shallow - each level adds offset
 *       calculation overhead</li>
 * </ul>
 *
 * <h3>Hardware Transfer Costs</h3>
 * <p>Data transfer between memory spaces (e.g., CPU -> GPU) can be expensive:
 * <ul>
 *   <li>Prefer batching: transfer large blocks rather than individual values</li>
 *   <li>Reuse allocations: avoid repeated allocate/deallocate cycles</li>
 *   <li>Use {@link #reallocate(MemoryProvider)} to move data between memory spaces
 *       when hardware context changes</li>
 * </ul>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <p>{@link MemoryData} extends {@link Destroyable}, indicating resources should be
 * explicitly released:</p>
 * <pre>{@code
 * MemoryData data = allocateData();
 * try {
 *     // Use data
 * } finally {
 *     data.destroy();  // Release underlying Memory
 * }
 * }</pre>
 *
 * <p><strong>Important:</strong> Delegated {@link MemoryData} instances do not own their
 * underlying {@link Memory}. Only destroy instances that allocated their own memory.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link MemoryData} is <strong>not thread-safe</strong>. Concurrent access must be
 * synchronized externally. However, read-only access to immutable data can be safely
 * shared across threads.</p>
 *
 * <h2>Implementation Notes</h2>
 *
 * <p>Most implementations extend {@link org.almostrealism.hardware.mem.MemoryDataAdapter},
 * which provides standard delegation support and memory management. Key implementations:
 * <ul>
 *   <li><strong>PackedCollection:</strong> Multi-dimensional arrays with shape tracking</li>
 *   <li><strong>Pair/Vector:</strong> Fixed-size geometric primitives</li>
 *   <li><strong>Bytes:</strong> Raw byte buffer access for custom data structures</li>
 * </ul>
 *
 * @see Memory
 * @see org.almostrealism.hardware.mem.MemoryDataAdapter
 * @see TraversalOrdering
 * @see Destroyable
 */
public interface MemoryData extends TraversableExpression<Double>, Delegated<MemoryData>, Destroyable, Node {

	/**
	 * Returns the underlying {@link Memory} object for this data.
	 *
	 * <p>For delegated {@link MemoryData}, this returns the delegate's memory.
	 * This is the low-level memory accessor used by hardware operations.</p>
	 *
	 * @return The underlying memory, or null if destroyed
	 */
	Memory getMem();

	/**
	 * Reallocates this memory data to a different {@link MemoryProvider}, copying existing data.
	 *
	 * <p>This is used when moving data between memory spaces (e.g., CPU -> GPU). The provider
	 * allocates new memory, copies existing data, and reassigns this {@link MemoryData} to
	 * reference the new memory.</p>
	 *
	 * @param provider The target memory provider (e.g., GPU memory)
	 */
	default void reallocate(MemoryProvider<?> provider) {
		reassign(provider.reallocate(getMem(), getOffset(), getMemLength()));
	}

	/**
	 * Reassigns this {@link MemoryData} to reference a different {@link Memory} object.
	 *
	 * <p>This is a low-level operation typically called by {@link #reallocate(MemoryProvider)}
	 * or during initialization. Use with caution - reassigning memory can break delegation chains.</p>
	 *
	 * @param mem The new memory to reference
	 */
	void reassign(Memory mem);

	/**
	 * Checks if this memory data has been destroyed.
	 *
	 * @return true if {@link #getMem()} returns null
	 */
	default boolean isDestroyed() {
		return getMem() == null;
	}

	/**
	 * Reads data from a byte array into this memory.
	 *
	 * <p>Expects data in double format (8 bytes per element). The byte array must contain
	 * at least {@code getMemLength() * 8} bytes.</p>
	 *
	 * @param b Byte array containing serialized double values
	 */
	default void read(byte b[]) {
		ByteBuffer buf = ByteBuffer.allocate(8 * getMemLength());

		for (int i = 0; i < getMemLength() * 8; i++) {
			buf.put(b[i]);
		}

		buf.position(0);

		for (int i = 0; i < getMemLength(); i++) {
			getMem().set(getOffset() + i, buf.getDouble());
		}
	}

	/**
	 * Reads data from an input stream into this memory.
	 *
	 * <p>Expects data in double format (8 bytes per element). Reads exactly
	 * {@code getMemLength() * 8} bytes from the stream.</p>
	 *
	 * @param in Input stream to read from
	 * @throws IOException If reading from the stream fails
	 */
	default void read(InputStream in) throws IOException {
		ByteBuffer buf = ByteBuffer.allocate(8 * getMemLength());

		for (int i = 0; i < getMemLength(); i++) {
			buf.put(in.readNBytes(8));
		}

		buf.position(0);

		for (int i = 0; i < getMemLength(); i++) {
			getMem().set(getOffset() + i, buf.getDouble());
		}
	}

	/**
	 * Serializes this memory data to a byte array.
	 *
	 * <p>Converts all double values to bytes (8 bytes per element). The returned array
	 * will contain {@code getMemLength() * 8} bytes.</p>
	 *
	 * @return Byte array containing serialized data
	 */
	default byte[] persist() {
		return getMem().getBytes(getOffset(), getMemLength()).array();
	}

	/**
	 * Writes this memory data to an output stream.
	 *
	 * <p>Serializes all double values to bytes (8 bytes per element) and writes to the stream.</p>
	 *
	 * @param out Output stream to write to
	 * @throws IOException If writing to the stream fails
	 */
	default void persist(OutputStream out) throws IOException {
		out.write(getMem().getBytes(getOffset(), getMemLength()).array());
	}

	/**
	 * Returns the offset into the underlying {@link Memory} where this data begins.
	 *
	 * <p>For delegated {@link MemoryData}, offsets accumulate through the delegation chain:
	 * <pre>
	 * Base Memory: [===================]
	 * Delegate A:      [==========]       offset 100
	 * Delegate B:         [====]          offset 50 from A
	 * B.getOffset() returns: 100 + 50 = 150
	 * </pre>
	 *
	 * @return The absolute offset in double-sized elements from the start of underlying memory
	 * @throws IllegalArgumentException If a circular delegate reference is detected
	 */
	default int getOffset() {
		if (getDelegate() == null) {
			return getDelegateOffset();
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			return getDelegateOffset() + getDelegate().getOffset();
		}
	}

	/**
	 * Returns the length of this memory data in double-sized elements.
	 *
	 * <p>This is the logical length of data accessible through this view. For delegated
	 * memory, this may be smaller than the underlying memory's total size.</p>
	 *
	 * @return The number of double values this memory data represents
	 */
	int getMemLength();

	/**
	 * Returns the atomic memory length, used for memory allocation sizing.
	 *
	 * <p>For most implementations, this equals {@link #getMemLength()}. Some specialized
	 * implementations may override this for different allocation sizes.</p>
	 *
	 * @return The atomic memory length
	 */
	default int getAtomicMemLength() {
		return getMemLength();
	}

	/**
	 * Returns the delegated length accounting for {@link TraversalOrdering}.
	 *
	 * <p>When a delegate ordering is set, this returns the ordering's length if specified,
	 * otherwise falls back to {@link #getMemLength()}.</p>
	 *
	 * @return The delegated length
	 */
	default int getDelegatedLength() {
		if (getDelegateOrdering() == null) {
			return getMemLength();
		} else {
			return getDelegateOrdering().getLength().orElse(getMemLength());
		}
	}

	/**
	 * If a delegate is set using this method, then the {@link Memory} for the delegate
	 * should be used to store and retrieve data, with the specified offset. The offset
	 * size is based on the size of a double, it indicates the number of double values
	 * to skip over to get to the location in the {@link Memory} where data should be
	 * kept.
	 *
	 * @param m      The delegate {@link MemoryData} whose memory will be used
	 * @param offset The offset in doubles from the start of the delegate's memory
	 */
	default void setDelegate(MemoryData m, int offset) {
		if (m == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		}

		setDelegate(m, offset, null);
	}

	/**
	 * If a delegate is set using this method, then the {@link Memory} for the delegate
	 * should be used to store and retrieve data, with the specified offset. The offset
	 * size is based on the size of a double, it indicates the number of double values
	 * to skip over to get to the location in the {@link Memory} where data should be
	 * kept.
	 *
	 * @param m      The delegate {@link MemoryData} whose memory will be used
	 * @param offset The offset in doubles from the start of the delegate's memory
	 * @param order  The traversal ordering for accessing the delegate's memory, or null for default
	 */
	void setDelegate(MemoryData m, int offset, TraversalOrdering order);

	/**
	 * Returns the delegate {@link MemoryData} that this instance references.
	 *
	 * @return The delegate, or null if this instance owns its own memory
	 */
	@Override
	MemoryData getDelegate();

	/**
	 * Returns the offset from the delegate's base (not absolute offset).
	 *
	 * @return The local offset relative to the delegate
	 */
	int getDelegateOffset();

	/**
	 * Returns the {@link TraversalOrdering} for mapping logical indices to physical memory.
	 *
	 * @return The ordering, or null for direct access
	 */
	TraversalOrdering getDelegateOrdering();

	/**
	 * Returns the composed memory ordering through the delegation chain.
	 *
	 * <p>If this instance and its delegates have orderings, they are composed together
	 * to produce a single ordering that maps logical indices through all delegation levels.</p>
	 *
	 * @return The composed ordering, or null if no ordering is applied
	 */
	default TraversalOrdering getMemOrdering() {
		if (getDelegate() == null) {
			return getDelegateOrdering();
		} else if (getDelegateOrdering() == null) {
			return getDelegate().getMemOrdering();
		} else {
			return getDelegateOrdering().compose(getDelegate().getMemOrdering());
		}
	}

	/**
	 * Returns a stream of all double values in this memory data.
	 *
	 * @return A {@link DoubleStream} of all values
	 */
	default DoubleStream doubleStream() {
		return doubleStream(0, getMemLength());
	}

	/**
	 * Returns a stream of double values for a specific range.
	 *
	 * @param offset Starting index
	 * @param length Number of elements
	 * @return A {@link DoubleStream} of the specified range
	 */
	default DoubleStream doubleStream(int offset, int length) {
		if (getDelegateOrdering() == null) {
			return DoubleStream.of(toArray(offset, length));
		} else {
			return IntStream.range(offset, offset + length).mapToDouble(this::toDouble);
		}
	}

	/**
	 * Counts the number of values matching a predicate.
	 *
	 * @param predicate The condition to test
	 * @return The count of matching values
	 */
	default int count(DoublePredicate predicate) {
		return Math.toIntExact(doubleStream().filter(predicate).count());
	}

	/**
	 * Returns the double value at the specified index.
	 *
	 * <p>If a {@link TraversalOrdering} is set, the logical index is mapped through the ordering
	 * to find the physical memory location. Returns 0.0 for out-of-bounds negative indices.</p>
	 *
	 * @param index The logical index
	 * @return The double value at that index
	 */
	default double toDouble(int index) {
		if (getMemOrdering() == null) {
			return index < 0 ? 0.0 : toArray(index, 1)[0];
		} else if (getDelegate() == null) {
			index = getMemOrdering().indexOf(index);
			if (index < 0) return 0.0;

			return getMem().toArray(getOffset() + index, 1)[0];
		} else {
			index = getDelegateOrdering().indexOf(index);
			if (index < 0) return 0.0;

			return getDelegate().toDouble(getDelegateOffset() + getDelegateOrdering().indexOf(index));
		}
	}

	/**
	 * Converts a range of this memory data to a double array.
	 *
	 * <p>If a {@link TraversalOrdering} is set, values are accessed through the ordering.
	 * Otherwise, values are copied directly from underlying memory.</p>
	 *
	 * @param offset Starting index
	 * @param length Number of elements to copy
	 * @return A new double array containing the specified range
	 * @throws IllegalArgumentException If the range extends beyond this data's length
	 */
	default double[] toArray(int offset, int length) {
		if (offset + length > getMemLength()) {
			throw new IllegalArgumentException("Array extends beyond the length of this MemoryData");
		}

		if (getMemOrdering() == null) {
			return getMem().toArray(getOffset() + offset, length);
		} else {
			return doubleStream(offset, length).toArray();
		}
	}

	/**
	 * Converts all data to a double array.
	 *
	 * @return A new double array containing all values
	 */
	default double[] toArray() {
		return toArray(0, getMemLength());
	}

	/**
	 * Converts a range of this memory data to a float array.
	 *
	 * <p>Values are cast from double to float precision.</p>
	 *
	 * @param offset Starting index
	 * @param length Number of elements to copy
	 * @return A new float array containing the specified range
	 * @throws IllegalArgumentException If the range extends beyond this data's length
	 */
	default float[] toFloatArray(int offset, int length) {
		if (offset + length > getMemLength()) {
			throw new IllegalArgumentException("Array extends beyond the length of this MemoryData");
		}

		if (getMemOrdering() == null) {
			float out[] = new float[length];
			getMem(offset, out, 0, length);
			return out;
		} else {
			double raw[] = toArray(offset, length);
			float out[] = new float[raw.length];
			for (int i = 0; i < raw.length; i++) {
				out[i] = (float) raw[i];
			}

			return out;
		}
	}

	/**
	 * Converts all data to a float array.
	 *
	 * @return A new float array containing all values
	 */
	default float[] toFloatArray() {
		return toFloatArray(0, getMemLength());
	}

	default String toArrayString(int offset, int length) {
		return Arrays.toString(toArray(offset, length));
	}

	default String toArrayString() {
		return Arrays.toString(toArray());
	}

	@Override
	default Expression<Double> getValue(Expression... pos) {
		throw new UnsupportedOperationException();
	}

	@Override
	default Expression<Double> getValueAt(Expression pos) {
		int i = pos.intValue().orElseThrow(UnsupportedOperationException::new);

		if (i > getMemLength()) {
			throw new IllegalArgumentException(i + " is out of bounds for MemoryData of length " + getMemLength());
		}

		if (getMem() == null) {
			throw new UnsupportedOperationException();
		} else if (getMem().getProvider().getNumberSize() == 8) {
			double out[] = new double[1];
			getMem(i, out, 0, 1);
			return new DoubleConstant(out[0]);
		} else {
			float out[] = new float[1];
			getMem(i, out, 0, 1);
			return new DoubleConstant((double) out[0]);
		}
	}

	@Override
	default Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
	}

	/**
	 * Writes double values to this memory starting at the specified offset.
	 *
	 * @param offset Starting index in this memory
	 * @param values Values to write
	 */
	default void setMem(int offset, double... values) {
		setMem(offset, values, 0, values.length);
	}

	/**
	 * Writes float values to this memory starting at the specified offset.
	 *
	 * <p>Values are converted from float to double precision.</p>
	 *
	 * @param offset Starting index in this memory
	 * @param values Values to write
	 */
	default void setMem(int offset, float... values) {
		setMem(offset, values, 0, values.length);
	}

	/**
	 * Writes float values to this memory starting at index 0.
	 *
	 * @param source Values to write
	 */
	default void setMem(float... source) {
		setMem(0, source, 0, source.length);
	}

	/**
	 * Writes double values to this memory starting at index 0.
	 *
	 * @param source Values to write
	 */
	default void setMem(double... source) {
		setMem(0, source, 0, source.length);
	}

	/**
	 * Writes a portion of a double array to this memory starting at index 0.
	 *
	 * @param source Source array
	 * @param srcOffset Starting index in source array
	 */
	default void setMem(double[] source, int srcOffset) {
		setMem(0, source, srcOffset, source.length - srcOffset);
	}

	/**
	 * Writes a range from a float array to this memory.
	 *
	 * @param offset Starting index in this memory
	 * @param source Source float array
	 * @param srcOffset Starting index in source array
	 * @param length Number of elements to copy
	 * @throws IllegalArgumentException If circular delegate reference detected
	 */
	default void setMem(int offset, float[] source, int srcOffset, int length) {
		if (getDelegate() == null) {
			setMem(getMem(), getOffset() + offset, source, srcOffset, length);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, source, srcOffset, length);
		}
	}

	/**
	 * Writes a range from a double array to this memory starting at index 0.
	 *
	 * @param source Source double array
	 * @param srcOffset Starting index in source array
	 * @param length Number of elements to copy
	 */
	default void setMem(double[] source, int srcOffset, int length) {
		setMem(0, source, srcOffset, length);
	}

	/**
	 * Writes a range from a double array to this memory.
	 *
	 * @param offset Starting index in this memory
	 * @param source Source double array
	 * @param srcOffset Starting index in source array
	 * @param length Number of elements to copy
	 * @throws IllegalArgumentException If the range extends beyond this data's length or circular delegate reference
	 */
	default void setMem(int offset, double[] source, int srcOffset, int length) {
		if (getDelegate() == null) {
			if (offset + length > getMemLength()) {
				throw new IllegalArgumentException("Array extends beyond the length of this MemoryData");
			}

			setMem(getMem(), getOffset() + offset, source, srcOffset, length);
		} else if (getDelegate() == this) {
			throw new IllegalArgumentException("Circular delegate reference");
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, source, srcOffset, length);
		}
	}

	/**
	 * Copies all data from another {@link MemoryData} to this memory starting at the specified offset.
	 *
	 * @param offset Starting index in this memory
	 * @param src Source memory data
	 */
	default void setMem(int offset, MemoryData src) {
		setMem(offset, src, 0, src.getMemLength());
	}

	/**
	 * Copies a range from another {@link MemoryData} to this memory starting at index 0.
	 *
	 * @param src Source memory data
	 * @param srcOffset Starting index in source
	 * @param length Number of elements to copy
	 */
	default void setMem(MemoryData src, int srcOffset, int length) {
		setMem(0, src, srcOffset, length);
	}

	/**
	 * Copies a range from another {@link MemoryData} to this memory.
	 *
	 * @param offset Starting index in this memory
	 * @param src Source memory data
	 * @param srcOffset Starting index in source
	 * @param length Number of elements to copy
	 * @throws IllegalArgumentException If source or destination ranges are invalid
	 */
	default void setMem(int offset, MemoryData src, int srcOffset, int length) {
		if (src.getMemLength() < srcOffset + length) {
			throw new IllegalArgumentException("Source MemoryData is not long enough to provide the requested data");
		} else if (offset + length > getMemLength()) {
			throw new IllegalArgumentException("MemoryData region extends beyond the length of this MemoryData");
		}

		if (getDelegate() == null) {
			setMem(getMem(), getOffset() + offset, src, srcOffset, length);
		} else {
			getDelegate().setMem(getDelegateOffset() + offset, src, srcOffset, length);
		}
	}

	/**
	 * Reads a range of data from this memory into a float array.
	 *
	 * @param sOffset Starting index in this memory (source offset)
	 * @param out Destination float array
	 * @param oOffset Starting index in destination array (output offset)
	 * @param length Number of elements to copy
	 */
	default void getMem(int sOffset, float out[], int oOffset, int length) {
		if (getDelegate() == null) {
			getMem(getMem(), getOffset() + sOffset, out, oOffset, length);
		} else {
			getDelegate().getMem(getDelegateOffset() + sOffset, out, oOffset, length);
		}
	}

	/**
	 * Reads a range of data from this memory into a double array.
	 *
	 * @param sOffset Starting index in this memory (source offset)
	 * @param out Destination double array
	 * @param oOffset Starting index in destination array (output offset)
	 * @param length Number of elements to copy
	 */
	default void getMem(int sOffset, double out[], int oOffset, int length) {
		if (getDelegate() == null) {
			getMem(getMem(), getOffset() + sOffset, out, oOffset, length);
		} else {
			getDelegate().getMem(getDelegateOffset() + sOffset, out, oOffset, length);
		}
	}

	/**
	 * Low-level method to write float array data directly to {@link Memory}.
	 *
	 * <p>Delegates to the memory's {@link io.almostrealism.code.MemoryProvider} for the actual write.
	 * This is used internally by the default {@link #setMem} methods.</p>
	 *
	 * @param mem Target memory
	 * @param offset Starting offset in memory
	 * @param source Source float array
	 * @param srcOffset Starting index in source array
	 * @param length Number of elements to write
	 */
	static void setMem(Memory mem, int offset, float[] source, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, source, srcOffset, length);
	}

	/**
	 * Low-level method to write double array data directly to {@link Memory}.
	 *
	 * <p>Delegates to the memory's {@link io.almostrealism.code.MemoryProvider} for the actual write.
	 * This is used internally by the default {@link #setMem} methods.</p>
	 *
	 * @param mem Target memory
	 * @param offset Starting offset in memory
	 * @param source Source double array
	 * @param srcOffset Starting index in source array
	 * @param length Number of elements to write
	 */
	static void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, source, srcOffset, length);
	}

	/**
	 * Low-level method to copy data between {@link Memory} objects.
	 *
	 * <p>Delegates to the memory's {@link io.almostrealism.code.MemoryProvider} for the actual copy.
	 * This is used internally by the default {@link #setMem} methods.</p>
	 *
	 * @param mem Target memory
	 * @param offset Starting offset in target memory
	 * @param src Source memory data
	 * @param srcOffset Starting offset in source memory
	 * @param length Number of elements to copy
	 */
	static void setMem(Memory mem, int offset, MemoryData src, int srcOffset, int length) {
		mem.getProvider().setMem(mem, offset, src.getMem(), src.getOffset() + srcOffset, length);
	}

	/**
	 * Low-level method to read data from {@link Memory} into a float array.
	 *
	 * <p>Delegates to the memory's {@link io.almostrealism.code.MemoryProvider} for the actual read.
	 * This is used internally by the default {@link #getMem} methods.</p>
	 *
	 * @param mem Source memory
	 * @param sOffset Starting offset in source memory
	 * @param out Destination float array
	 * @param oOffset Starting index in destination array
	 * @param length Number of elements to read
	 */
	static void getMem(Memory mem, int sOffset, float out[], int oOffset, int length) {
		mem.getProvider().getMem(mem, sOffset, out, oOffset, length);
	}

	/**
	 * Low-level method to read data from {@link Memory} into a double array.
	 *
	 * <p>Delegates to the memory's {@link io.almostrealism.code.MemoryProvider} for the actual read.
	 * This is used internally by the default {@link #getMem} methods.</p>
	 *
	 * @param mem Source memory
	 * @param sOffset Starting offset in source memory
	 * @param out Destination double array
	 * @param oOffset Starting index in destination array
	 * @param length Number of elements to read
	 */
	static void getMem(Memory mem, int sOffset, double out[], int oOffset, int length) {
		mem.getProvider().getMem(mem, sOffset, out, oOffset, length);
	}
}
