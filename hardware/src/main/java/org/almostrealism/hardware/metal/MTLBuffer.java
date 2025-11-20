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

	public static TimingMetric ioTime = Hardware.console.timing("metalIO");

	static {
		ioTime.setThreshold(20.0);
	}

	public MTLBuffer(Precision precision, long nativePointer, boolean shared) {
		super(nativePointer);
		this.precision = precision;
		this.shared = shared;
	}

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

	public void setContents(IntBuffer buf, int offset, int length) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			MTL.setIntBufferContents32(getNativePointer(), buf, offset, length);
		} finally {
			ioTime.addEntry("setContents", System.nanoTime() - start);
		}
	}

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

	public void getContents(FloatBuffer buf, int offset, int length) {
		if (isReleased()) throw new IllegalStateException();

		long start = System.nanoTime();

		try {
			MTL.getBufferContents32(getNativePointer(), buf, offset, length);
		} finally {
			ioTime.addEntry("getContents", System.nanoTime() - start);
		}
	}

	public void setContents(DoubleBuffer buf, int offset, int length) {
		throw new UnsupportedOperationException("DoubleBuffer not supported");
	}

	public void getContents(DoubleBuffer buf, int offset, int length) {
		throw new UnsupportedOperationException("DoubleBuffer not supported");
	}

	public long length() {
		if (isReleased()) throw new IllegalStateException();
		return MTL.bufferLength(getNativePointer());
	}

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
