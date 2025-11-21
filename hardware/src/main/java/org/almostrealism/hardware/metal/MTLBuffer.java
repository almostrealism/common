/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.metal;

import io.almostrealism.code.Precision;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.io.TimingMetric;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Wrapper for Metal {@code id<MTLBuffer>}.
 *
 * <p>Manages GPU-accessible memory buffers supporting FP16/FP32 precision and
 * shared/managed storage modes.</p>
 *
 * <h2>Buffer Creation</h2>
 *
 * <pre>{@code
 * MTLBuffer buffer = device.newBuffer32(1024);  // FP32, 1024 elements
 * MTLBuffer buffer16 = device.newBuffer16(512); // FP16, 512 elements
 * MTLBuffer shared = device.newSharedBuffer32("/path/to/file", 1024);
 * }</pre>
 *
 * <h2>Data Transfer</h2>
 *
 * <pre>{@code
 * // Set contents
 * FloatBuffer data = FloatBuffer.wrap(new float[]{1.0f, 2.0f, 3.0f});
 * buffer.setContents(data, 0, 3);
 *
 * // Get contents
 * FloatBuffer output = FloatBuffer.allocate(3);
 * buffer.getContents(output, 0, 3);
 * }</pre>
 *
 * @see MTLDevice
 * @see MetalMemory
 */
public class MTLBuffer extends MTLObject {
	private final Precision precision;
	private final boolean shared;

	/**
	 * Metric tracking Metal buffer I/O operation timing.
	 */
	public static TimingMetric ioTime = Hardware.console.timing("metalIO");

	static {
		ioTime.setThreshold(20.0);
	}

	/**
	 * Creates an MTLBuffer wrapper for a native Metal buffer pointer.
	 *
	 * @param precision Buffer element precision ({@link Precision#FP16} or {@link Precision#FP32})
	 * @param nativePointer Native Metal buffer pointer
	 * @param shared True if buffer uses shared storage mode, false for managed mode
	 */
	public MTLBuffer(Precision precision, long nativePointer, boolean shared) {
		super(nativePointer);
		this.precision = precision;
		this.shared = shared;
	}

	/**
	 * Returns the native memory pointer to the buffer's contents.
	 *
	 * <p>Provides direct access to the buffer's underlying memory for low-level operations.
	 * Useful for JNI interop or memory mapping.</p>
	 *
	 * @return Native pointer to buffer contents
	 * @throws IllegalStateException if buffer has been released
	 * @throws HardwareException if pointer cannot be obtained
	 */
	public long getContentPointer() {
		if (isReleased()) throw new IllegalStateException();

		try {
			long ptr = MTL.getContentPointer(getNativePointer());
			if (ptr == -1) throw new NullPointerException();
			return ptr;
		} catch (Exception e) {
			throw new HardwareException("Failed to obtain content pointer for MTLBuffer", e);
		}
	}

	/**
	 * Writes integer array data to the buffer.
	 *
	 * <p>Copies all elements from the array into the buffer starting at offset 0.
	 * Tracks I/O timing in {@link #ioTime} metric.</p>
	 *
	 * @param values Integer array to copy into buffer
	 * @throws IllegalStateException if buffer has been released
	 */
	public void setContents(int values[]) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			ByteBuffer bufferByte = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
			IntBuffer buf = bufferByte.asIntBuffer();
			buf.put(values);
			MTL.setIntBufferContents32(getNativePointer(), buf, 0, values.length);
		} finally {
			ioTime.addEntry("setContents", System.nanoTime() - start);
		}
	}

	/**
	 * Writes integer data from an {@link IntBuffer} to the buffer.
	 *
	 * <p>Copies {@code length} integers starting at {@code offset} into the buffer.
	 * Tracks I/O timing in {@link #ioTime} metric.</p>
	 *
	 * @param buf Source {@link IntBuffer}
	 * @param offset Starting offset in buffer (in elements)
	 * @param length Number of integers to copy
	 * @throws IllegalStateException if buffer has been released
	 */
	public void setContents(IntBuffer buf, int offset, int length) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			MTL.setIntBufferContents32(getNativePointer(), buf, offset, length);
		} finally {
			ioTime.addEntry("setContents", System.nanoTime() - start);
		}
	}

	/**
	 * Writes float data from a {@link FloatBuffer} to the buffer.
	 *
	 * <p>Copies {@code length} floats starting at {@code offset} into the buffer.
	 * Automatically handles FP16/FP32 conversions based on buffer precision.
	 * Tracks I/O timing in {@link #ioTime} metric.</p>
	 *
	 * @param buf Source {@link FloatBuffer}
	 * @param offset Starting offset in buffer (in elements)
	 * @param length Number of floats to copy
	 * @throws IllegalStateException if buffer has been released
	 * @throws UnsupportedOperationException if FP16 is used with shared storage mode
	 */
	public void setContents(FloatBuffer buf, int offset, int length) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			if (precision == Precision.FP16) {
				if (shared) {
					throw new UnsupportedOperationException("Shared buffers do not support FP16");
				}

				MTL.setBufferContents16(getNativePointer(), buf, offset, length);
			} else {
				MTL.setBufferContents32(getNativePointer(), buf, offset, length, shared);
			}
		} finally {
			ioTime.addEntry("setContents", System.nanoTime() - start);
		}
	}

	/**
	 * Reads float data from the buffer into a {@link FloatBuffer}.
	 *
	 * <p>Copies {@code length} floats starting at {@code offset} from the buffer.
	 * Automatically handles FP16/FP32 conversions based on buffer precision.
	 * Tracks I/O timing in {@link #ioTime} metric.</p>
	 *
	 * @param buf Destination {@link FloatBuffer}
	 * @param offset Starting offset in buffer (in elements)
	 * @param length Number of floats to read
	 * @throws IllegalStateException if buffer has been released
	 */
	public void getContents(FloatBuffer buf, int offset, int length) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			MTL.getBufferContents32(getNativePointer(), buf, offset, length);
		} finally {
			ioTime.addEntry("getContents", System.nanoTime() - start);
		}
	}

	/**
	 * Writes double data from a {@link DoubleBuffer} to the buffer.
	 *
	 * <p><strong>Not supported.</strong> Metal buffers do not support double precision operations.</p>
	 *
	 * @param buf Source {@link DoubleBuffer}
	 * @param offset Starting offset in buffer (in elements)
	 * @param length Number of doubles to copy
	 * @throws UnsupportedOperationException always thrown
	 */
	public void setContents(DoubleBuffer buf, int offset, int length) {
		throw new UnsupportedOperationException("DoubleBuffer not supported");
	}

	/**
	 * Reads double data from the buffer into a {@link DoubleBuffer}.
	 *
	 * <p><strong>Not supported.</strong> Metal buffers do not support double precision operations.</p>
	 *
	 * @param buf Destination {@link DoubleBuffer}
	 * @param offset Starting offset in buffer (in elements)
	 * @param length Number of doubles to read
	 * @throws UnsupportedOperationException always thrown
	 */
	public void getContents(DoubleBuffer buf, int offset, int length) {
		throw new UnsupportedOperationException("DoubleBuffer not supported");
	}

	/**
	 * Returns the length of this buffer in bytes.
	 *
	 * @return Buffer length in bytes
	 * @throws IllegalStateException if buffer has been released
	 */
	public long length() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.bufferLength(getNativePointer());
	}

	/**
	 * Releases this buffer and frees its native Metal resources.
	 *
	 * <p>Must be called exactly once. Further calls will throw an exception.
	 * After release, the buffer cannot be used.</p>
	 *
	 * @throws IllegalStateException if buffer has already been released
	 */
	@Override
	public synchronized void release() {
		if (!isReleased()) {
			MTL.releaseBuffer(getNativePointer());
			super.release();
		} else {
			throw new IllegalStateException("Buffer already released");
		}
	}
}
