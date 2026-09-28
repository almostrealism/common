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

package org.almostrealism.hardware.cuda;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A CUDA memory allocation, either device memory or managed memory.
 *
 * <p>Managed allocations are addressable from the host as well as the device, which is
 * what allows them to be shared zero-copy with host (JNI) code through
 * {@link #getContentPointer()}. Device allocations are reachable from the host only
 * through {@link #write} and {@link #read}, which work for both kinds.</p>
 *
 * @see CUContext#allocate(long)
 * @see CUContext#allocateManaged(long)
 */
public class CUDeviceBuffer extends CUObject {
	/** Size of the allocation in bytes. */
	private final long size;

	/** Whether this is a managed allocation, addressable from the host. */
	private final boolean managed;

	/** Wraps an allocation. Obtain instances from {@link CUContext}. */
	CUDeviceBuffer(CUContext context, long nativePointer, long size, boolean managed) {
		super(context, nativePointer);
		this.size = size;
		this.managed = managed;
	}

	/** Returns the size of this allocation in bytes. */
	public long getSize() { return size; }

	/** Returns true if this is a managed allocation, addressable from the host. */
	public boolean isManaged() { return managed; }

	/**
	 * Returns the host address of the contents of a managed allocation.
	 *
	 * @throws UnsupportedOperationException if this is a device allocation
	 */
	public long getContentPointer() {
		if (!managed) {
			throw new UnsupportedOperationException("Device memory is not addressable from the host");
		}

		return getNativePointer();
	}

	/**
	 * Copies {@code bytes} bytes from a direct buffer into this allocation. Both the source
	 * buffer's byte range and this allocation's byte range are validated before the copy, so
	 * a caller cannot make the driver read past the end of the host buffer.
	 *
	 * @param source            a direct byte buffer
	 * @param sourceOffset      byte offset into the source
	 * @param destinationOffset byte offset into this allocation
	 * @param bytes             number of bytes to copy
	 */
	public void write(ByteBuffer source, long sourceOffset, long destinationOffset, long bytes) {
		checkBuffer(source, sourceOffset, bytes);
		checkRange(destinationOffset, bytes);
		CU.memcpyHtoD(getContextPointer(), getNativePointer(), destinationOffset, source, sourceOffset, bytes);
	}

	/**
	 * Copies {@code bytes} bytes from this allocation into a direct buffer. Both this
	 * allocation's byte range and the destination buffer's byte range are validated before the
	 * copy, so a caller cannot make the driver write past the end of the host buffer.
	 *
	 * @param destination       a direct byte buffer
	 * @param destinationOffset byte offset into the destination
	 * @param sourceOffset      byte offset into this allocation
	 * @param bytes             number of bytes to copy
	 */
	public void read(ByteBuffer destination, long destinationOffset, long sourceOffset, long bytes) {
		checkBuffer(destination, destinationOffset, bytes);
		checkRange(sourceOffset, bytes);
		CU.memcpyDtoH(getContextPointer(), destination, destinationOffset, getNativePointer(), sourceOffset, bytes);
	}

	/**
	 * Copies {@code length} floats from {@code source}, starting at {@code sourceOffset},
	 * into this allocation, treated as an array of floats, starting at element {@code offset}.
	 */
	public void setContents(float[] source, int sourceOffset, int offset, int length) {
		ByteBuffer buf = direct(length, Float.BYTES);
		buf.asFloatBuffer().put(source, sourceOffset, length);
		write(buf, 0, (long) offset * Float.BYTES, (long) length * Float.BYTES);
	}

	/**
	 * Copies {@code length} doubles from {@code source}, starting at {@code sourceOffset},
	 * into this allocation, treated as an array of doubles, starting at element {@code offset}.
	 */
	public void setContents(double[] source, int sourceOffset, int offset, int length) {
		ByteBuffer buf = direct(length, Double.BYTES);
		buf.asDoubleBuffer().put(source, sourceOffset, length);
		write(buf, 0, (long) offset * Double.BYTES, (long) length * Double.BYTES);
	}

	/**
	 * Copies {@code length} floats from this allocation, treated as an array of floats and
	 * starting at element {@code offset}, into {@code out} starting at {@code outOffset}.
	 */
	public void getContents(float[] out, int outOffset, int offset, int length) {
		ByteBuffer buf = direct(length, Float.BYTES);
		read(buf, 0, (long) offset * Float.BYTES, (long) length * Float.BYTES);
		buf.asFloatBuffer().get(out, outOffset, length);
	}

	/**
	 * Copies {@code length} doubles from this allocation, treated as an array of doubles and
	 * starting at element {@code offset}, into {@code out} starting at {@code outOffset}.
	 */
	public void getContents(double[] out, int outOffset, int offset, int length) {
		ByteBuffer buf = direct(length, Double.BYTES);
		read(buf, 0, (long) offset * Double.BYTES, (long) length * Double.BYTES);
		buf.asDoubleBuffer().get(out, outOffset, length);
	}

	/** Returns a direct, native-order staging buffer for {@code count} elements of {@code elementSize} bytes. */
	private static ByteBuffer direct(int count, int elementSize) {
		return ByteBuffer.allocateDirect(count * elementSize).order(ByteOrder.nativeOrder());
	}

	/**
	 * Copies {@code bytes} bytes from another allocation into this one, synchronously.
	 * Offsets are in bytes.
	 */
	public void copyFrom(CUDeviceBuffer source, long sourceOffset, long destinationOffset, long bytes) {
		source.checkRange(sourceOffset, bytes);
		checkRange(destinationOffset, bytes);
		CU.memcpyDtoD(getContextPointer(), getNativePointer() + destinationOffset,
				source.getNativePointer() + sourceOffset, bytes);
	}

	/**
	 * Throws if {@code [offset, offset + bytes)} is not within this allocation.
	 *
	 * @throws IndexOutOfBoundsException if the range falls outside the allocation
	 */
	void checkRange(long offset, long bytes) {
		if (outOfRange(offset, bytes, size)) {
			throw new IndexOutOfBoundsException("Range at offset " + offset + " of " + bytes +
					" bytes is outside an allocation of " + size + " bytes");
		}
	}

	/**
	 * Throws if {@code [offset, offset + bytes)} is not within {@code buffer}, whose
	 * {@link ByteBuffer#capacity() capacity} is its length in bytes. This guards the JNI copy,
	 * which reads the buffer's raw address and would otherwise run past its end.
	 *
	 * @throws IndexOutOfBoundsException if the range falls outside the buffer
	 */
	private static void checkBuffer(ByteBuffer buffer, long offset, long bytes) {
		if (outOfRange(offset, bytes, buffer.capacity())) {
			throw new IndexOutOfBoundsException("Range at offset " + offset + " of " + bytes +
					" bytes is outside a buffer of " + buffer.capacity() + " bytes");
		}
	}

	/**
	 * Returns true if {@code [offset, offset + bytes)} is not contained in {@code [0, limit)}.
	 * The upper bound is checked as {@code offset > limit - bytes} rather than
	 * {@code offset + bytes > limit}, because the latter can overflow to a negative value (for
	 * example when {@code bytes} is near {@link Long#MAX_VALUE}) and let an out-of-bounds range
	 * pass. Since {@code offset} and {@code bytes} are already known non-negative and
	 * {@code limit} is non-negative, {@code limit - bytes} cannot overflow.
	 *
	 * @param offset the start of the range
	 * @param bytes  the length of the range
	 * @param limit  the exclusive upper bound
	 * @return true if the range falls outside {@code [0, limit)}
	 */
	private static boolean outOfRange(long offset, long bytes, long limit) {
		return offset < 0 || bytes < 0 || offset > limit - bytes;
	}

	/** Frees this allocation. */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		CU.memFree(getContextPointer(), getNativePointer());
		super.release();
	}
}
