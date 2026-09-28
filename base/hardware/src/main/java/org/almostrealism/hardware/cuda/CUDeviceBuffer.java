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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;

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
	 * Copies {@code bytes} bytes from a direct buffer into this allocation.
	 *
	 * @param source            a direct buffer
	 * @param sourceOffset      byte offset into the source
	 * @param destinationOffset byte offset into this allocation
	 * @param bytes             number of bytes to copy
	 */
	public void write(Buffer source, long sourceOffset, long destinationOffset, long bytes) {
		checkRange(destinationOffset, bytes);
		CU.memcpyHtoD(getContextPointer(), getNativePointer(), destinationOffset, source, sourceOffset, bytes);
	}

	/**
	 * Copies {@code bytes} bytes from this allocation into a direct buffer.
	 *
	 * @param destination       a direct buffer
	 * @param destinationOffset byte offset into the destination
	 * @param sourceOffset      byte offset into this allocation
	 * @param bytes             number of bytes to copy
	 */
	public void read(Buffer destination, long destinationOffset, long sourceOffset, long bytes) {
		checkRange(sourceOffset, bytes);
		CU.memcpyDtoH(getContextPointer(), destination, destinationOffset, getNativePointer(), sourceOffset, bytes);
	}

	/**
	 * Copies {@code length} floats from {@code source}, starting at {@code sourceOffset},
	 * into this allocation, treated as an array of floats, starting at element {@code offset}.
	 */
	public void setContents(float[] source, int sourceOffset, int offset, int length) {
		FloatBuffer buf = direct(length, Float.BYTES).asFloatBuffer();
		buf.put(source, sourceOffset, length);
		write(buf, 0, (long) offset * Float.BYTES, (long) length * Float.BYTES);
	}

	/**
	 * Copies {@code length} doubles from {@code source}, starting at {@code sourceOffset},
	 * into this allocation, treated as an array of doubles, starting at element {@code offset}.
	 */
	public void setContents(double[] source, int sourceOffset, int offset, int length) {
		DoubleBuffer buf = direct(length, Double.BYTES).asDoubleBuffer();
		buf.put(source, sourceOffset, length);
		write(buf, 0, (long) offset * Double.BYTES, (long) length * Double.BYTES);
	}

	/**
	 * Copies {@code length} floats from this allocation, treated as an array of floats and
	 * starting at element {@code offset}, into {@code out} starting at {@code outOffset}.
	 */
	public void getContents(float[] out, int outOffset, int offset, int length) {
		FloatBuffer buf = direct(length, Float.BYTES).asFloatBuffer();
		read(buf, 0, (long) offset * Float.BYTES, (long) length * Float.BYTES);
		buf.get(out, outOffset, length);
	}

	/**
	 * Copies {@code length} doubles from this allocation, treated as an array of doubles and
	 * starting at element {@code offset}, into {@code out} starting at {@code outOffset}.
	 */
	public void getContents(double[] out, int outOffset, int offset, int length) {
		DoubleBuffer buf = direct(length, Double.BYTES).asDoubleBuffer();
		read(buf, 0, (long) offset * Double.BYTES, (long) length * Double.BYTES);
		buf.get(out, outOffset, length);
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
	private void checkRange(long offset, long bytes) {
		if (offset < 0 || bytes < 0 || offset + bytes > size) {
			throw new IndexOutOfBoundsException("Range [" + offset + ", " + (offset + bytes) +
					") is outside an allocation of " + size + " bytes");
		}
	}

	/** Frees this allocation. */
	@Override
	public synchronized void release() {
		if (isReleased()) return;
		CU.memFree(getContextPointer(), getNativePointer());
		super.release();
	}
}
