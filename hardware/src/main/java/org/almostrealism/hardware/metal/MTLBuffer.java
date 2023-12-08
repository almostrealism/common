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

public class MTLBuffer extends MTLObject {
	private Precision precision;

	public static TimingMetric ioTime = Hardware.console.metric("metalIO");

	public MTLBuffer(Precision precision, long nativePointer) {
		super(nativePointer);
		this.precision = precision;
	}

	public long getContentPointer() {
		try {
			long ptr = MTL.getContentPointer(getNativePointer());
			if (ptr == -1) throw new NullPointerException();
			return ptr;
		} catch (Exception e) {
			throw new HardwareException("Failed to obtain content pointer for MTLBuffer", e);
		}
	}

	public void setContents(int values[]) {
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
		long start = System.nanoTime();

		try {
			MTL.setIntBufferContents32(getNativePointer(), buf, offset, length);
		} finally {
			ioTime.addEntry("setContents", System.nanoTime() - start);
		}
	}

	public void setContents(FloatBuffer buf, int offset, int length) {
		long start = System.nanoTime();

		try {
			if (precision == Precision.FP16) {
				MTL.setBufferContents16(getNativePointer(), buf, offset, length);
			} else {
				MTL.setBufferContents32(getNativePointer(), buf, offset, length);
			}
		} finally {
			ioTime.addEntry("setContents", System.nanoTime() - start);
		}
	}

	public void getContents(FloatBuffer buf, int offset, int length) {
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
		return MTL.bufferLength(getNativePointer());
	}

	public void release() {
		MTL.releaseBuffer(getNativePointer());
	}
}
