/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.nio;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.HardwareException;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

public class NativeBufferMemoryProvider implements MemoryProvider<NativeBuffer> {
	private final Precision precision;
	private final long memoryMax;
	private long memoryUsed;

	private List<NativeBuffer> allocated;

	public NativeBufferMemoryProvider(Precision precision, long memoryMax) {
		this.precision = precision;
		this.memoryMax = memoryMax;
		this.allocated = new ArrayList<>();
	}

	@Override
	public int getNumberSize() { return precision.bytes(); }

	@Override
	public synchronized NativeBuffer allocate(int size) {
		if (memoryUsed + (long) getNumberSize() * size > memoryMax) {
			throw new HardwareException("Memory max reached");
		} else {
			memoryUsed += (long) getNumberSize() * size;
			NativeBuffer mem = new NativeBuffer(this, buffer(size));
			allocated.add(mem);
			return mem;
		}
	}

	@Override
	public synchronized void deallocate(int size, NativeBuffer mem) {
		if (!allocated.contains(mem)) return;

		memoryUsed -= (long) size * getNumberSize();
		allocated.remove(mem);
	}

	@Override
	public synchronized void setMem(NativeBuffer mem, int offset, Memory source, int srcOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");
		double value[] = new double[length];
		source.getProvider().getMem(source, srcOffset, value, 0, length);
		setMem(mem, offset, value, 0, length);
	}

	@Override
	public synchronized void setMem(NativeBuffer mem, int offset, double[] source, int srcOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");

		if (mem.getBuffer() instanceof DoubleBuffer) {
			DoubleBuffer buffer = (DoubleBuffer) mem.getBuffer();
			buffer.position(offset);
			buffer.put(source, srcOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer) {
			FloatBuffer buffer = (FloatBuffer) mem.getBuffer();
			buffer.position(offset);
			for (int i = 0; i < length; i++) {
				buffer.put((float) source[srcOffset + i]);
			}
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}


	@Override
	public void setMem(NativeBuffer mem, int offset, float[] source, int srcOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");

		if (mem.getBuffer() instanceof DoubleBuffer) {
			DoubleBuffer buffer = (DoubleBuffer) mem.getBuffer();
			buffer.position(offset);
			for (int i = 0; i < length; i++) {
				buffer.put(source[srcOffset + i]);
			}
		} else if (mem.getBuffer() instanceof FloatBuffer) {
			FloatBuffer buffer = (FloatBuffer) mem.getBuffer();
			buffer.position(offset);
			buffer.put(source, srcOffset, length);
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}

	@Override
	public synchronized void getMem(NativeBuffer mem, int sOffset, double[] out, int oOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");

		if (mem.getBuffer() instanceof DoubleBuffer) {
			DoubleBuffer buffer = (DoubleBuffer) mem.getBuffer();
			buffer.position(sOffset);
			buffer.get(out, oOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer) {
			FloatBuffer buffer = (FloatBuffer) mem.getBuffer();
			buffer.position(sOffset);
			for (int i = 0; i < length; i++) {
				out[oOffset + i] = buffer.get();
			}
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}

	@Override
	public void getMem(NativeBuffer mem, int sOffset, float[] out, int oOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");

		if (mem.getBuffer() instanceof DoubleBuffer) {
			DoubleBuffer buffer = (DoubleBuffer) mem.getBuffer();
			buffer.position(sOffset);
			for (int i = 0; i < length; i++) {
				out[oOffset + i] = (float) buffer.get();
			}
		} else if (mem.getBuffer() instanceof FloatBuffer) {
			FloatBuffer buffer = (FloatBuffer) mem.getBuffer();
			buffer.position(sOffset);
			buffer.get(out, oOffset, length);
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}

	@Override
	public synchronized void destroy() {
		allocated.clear();
		memoryUsed = 0;
	}

	private Buffer buffer(int len) {
		if (precision == Precision.FP16) {
			ByteBuffer bufferByte = ByteBuffer.allocateDirect(len * 2).order(ByteOrder.nativeOrder());
			return bufferByte.asShortBuffer();
		} else if (precision == Precision.FP32) {
			return ByteBuffer.allocateDirect(len * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		} else if (precision == Precision.FP64) {
			return ByteBuffer.allocateDirect(len * 8).order(ByteOrder.nativeOrder()).asDoubleBuffer();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}
}
