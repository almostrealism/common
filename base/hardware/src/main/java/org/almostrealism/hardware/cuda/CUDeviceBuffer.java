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
	/** Zero-filled host memory shared by {@link #clear()}, which only ever reads it. */
	private static ByteBuffer zeroSource = ByteBuffer.allocateDirect(0);

	/** Size of the allocation in bytes. */
	private final long size;

	/** Whether this is a managed allocation, addressable from the host. */
	private final boolean managed;

	/** A direct buffer over a managed allocation's contents, created on first use. */
	private volatile ByteBuffer hostView;

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
		ByteBuffer buf = staged(offset, length, Float.BYTES, false);
		buf.asFloatBuffer().put(source, sourceOffset, length);
		store(buf, offset, Float.BYTES);
	}

	/**
	 * Copies {@code length} doubles from {@code source}, starting at {@code sourceOffset},
	 * into this allocation, treated as an array of doubles, starting at element {@code offset}.
	 */
	public void setContents(double[] source, int sourceOffset, int offset, int length) {
		ByteBuffer buf = staged(offset, length, Double.BYTES, false);
		buf.asDoubleBuffer().put(source, sourceOffset, length);
		store(buf, offset, Double.BYTES);
	}

	/**
	 * Copies {@code length} floats from this allocation, treated as an array of floats and
	 * starting at element {@code offset}, into {@code out} starting at {@code outOffset}.
	 */
	public void getContents(float[] out, int outOffset, int offset, int length) {
		staged(offset, length, Float.BYTES, true).asFloatBuffer().get(out, outOffset, length);
	}

	/**
	 * Copies {@code length} doubles from this allocation, treated as an array of doubles and
	 * starting at element {@code offset}, into {@code out} starting at {@code outOffset}.
	 */
	public void getContents(double[] out, int outOffset, int offset, int length) {
		staged(offset, length, Double.BYTES, true).asDoubleBuffer().get(out, outOffset, length);
	}

	/**
	 * Returns a native-order buffer over {@code length} elements of {@code elementSize} bytes
	 * starting at element {@code offset}, through which {@link #setContents} and
	 * {@link #getContents} transfer values.
	 *
	 * <p>For a {@link #isHostAccessible() host accessible} allocation this is a slice of its
	 * {@link #hostView() view}, so values move with no driver call; this is what makes reading
	 * or writing a few elements at a time affordable. Otherwise it is a direct staging buffer,
	 * which {@code load} fills from the allocation and {@link #store} writes back.</p>
	 */
	private ByteBuffer staged(int offset, int length, int elementSize, boolean load) {
		long start = (long) offset * elementSize;
		long bytes = (long) length * elementSize;

		if (isHostAccessible()) {
			checkRange(start, bytes);
			return hostView().slice((int) start, (int) bytes).order(ByteOrder.nativeOrder());
		}

		ByteBuffer buf = ByteBuffer.allocateDirect(Math.toIntExact(bytes)).order(ByteOrder.nativeOrder());
		if (load) read(buf, 0, start, bytes);
		return buf;
	}

	/**
	 * Writes a buffer returned by {@link #staged} back to the allocation at element
	 * {@code offset}, unless it is a slice of the host view and so already in place.
	 */
	private void store(ByteBuffer buf, int offset, int elementSize) {
		if (!isHostAccessible()) {
			write(buf, 0, (long) offset * elementSize, buf.capacity());
		}
	}

	/**
	 * Copies {@code bytes} bytes from another allocation into this one, synchronously.
	 * Offsets are in bytes.
	 *
	 * <p>When both allocations are {@link #isHostAccessible() accessible from the host}, the
	 * copy is made by the host over their {@link #hostView() views}, without a driver call.
	 * Otherwise it is a device-to-device copy by the driver. Neither form is ordered against
	 * kernels queued on a stream, which do not run on the null stream, so a caller orders the
	 * copy after any kernel that writes the source or uses the destination, as it would for
	 * any host access to device memory.</p>
	 */
	public void copyFrom(CUDeviceBuffer source, long sourceOffset, long destinationOffset, long bytes) {
		source.checkRange(sourceOffset, bytes);
		checkRange(destinationOffset, bytes);

		if (isHostAccessible() && source.isHostAccessible()) {
			hostView().put((int) destinationOffset, source.hostView(), (int) sourceOffset, (int) bytes);
			return;
		}

		CU.memcpyDtoD(getContextPointer(), getNativePointer() + destinationOffset,
				source.getNativePointer() + sourceOffset, bytes);
	}

	/**
	 * Sets every byte of this allocation to zero, restoring the state it was allocated in
	 * (see {@link CUContext#allocate(long)}). A managed allocation is cleared by the host over
	 * its {@link #hostView() view}; a device allocation by a copy from zero-filled host memory.
	 * As with {@link #copyFrom}, the caller orders this after any kernel using the allocation.
	 */
	public void clear() {
		ByteBuffer zeros = zeros(size);

		if (isHostAccessible()) {
			hostView().put(0, zeros, 0, (int) size);
		} else {
			write(zeros, 0, 0, size);
		}
	}

	/**
	 * Returns zero-filled direct memory of at least {@code bytes} bytes, replacing the shared
	 * buffer with a larger one when it is too small.
	 */
	private static synchronized ByteBuffer zeros(long bytes) {
		if (zeroSource.capacity() < bytes) {
			zeroSource = ByteBuffer.allocateDirect(Math.toIntExact(bytes));
		}

		return zeroSource;
	}

	/**
	 * Returns true if the host can read and write this allocation directly through
	 * {@link #hostView()}: it is managed, and small enough to be viewed by one buffer.
	 */
	public boolean isHostAccessible() {
		return managed && size <= Integer.MAX_VALUE;
	}

	/**
	 * Returns a direct buffer over the whole of this allocation, created on first use. The
	 * buffer is a view rather than a copy: writes through it are writes to the allocation.
	 * It must not be used once the allocation is released.
	 *
	 * @throws UnsupportedOperationException if this allocation is not
	 *                                       {@link #isHostAccessible() host accessible}
	 */
	public ByteBuffer hostView() {
		if (!isHostAccessible()) {
			throw new UnsupportedOperationException("Allocation is not accessible from the host");
		}

		long pointer = getContentPointer();

		ByteBuffer view = hostView;
		if (view == null) {
			view = CU.hostView(pointer, size).order(ByteOrder.nativeOrder());
			hostView = view;
		}

		return view;
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
