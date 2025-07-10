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
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NativeBufferMemoryProvider extends HardwareMemoryProvider<NativeBuffer> {
	private static Map<Class, NativeBufferAllocator> allocationAdapters = new HashMap<>();
	private static Map<Class, NativeBufferWriter> writeAdapters = new HashMap<>();

	private final Precision precision;
	private final long memoryMax;
	private final boolean shared;

	private long memoryUsed;

	private List<NativeBuffer> allocated;

	public NativeBufferMemoryProvider(Precision precision, long memoryMax) {
		this(precision, memoryMax, true);
	}

	public NativeBufferMemoryProvider(Precision precision, long memoryMax, boolean shared) {
		this.precision = precision;
		this.memoryMax = memoryMax;
		this.shared = shared;
		this.allocated = new ArrayList<>();
	}

	@Override
	public String getName() { return "NIO"; }

	public Precision getPrecision() { return precision; }

	@Override
	public int getNumberSize() { return getPrecision().bytes(); }

	@Override
	protected NativeBuffer fromReference(NativeRef<NativeBuffer> reference) {
		throw new UnsupportedOperationException();
	}

	@Override
	public synchronized NativeBuffer allocate(int size) {
		if (memoryUsed + (long) getNumberSize() * size > memoryMax) {
			throw new HardwareException("Memory max reached");
		} else {
			memoryUsed += (long) getNumberSize() * size;
			NativeBuffer mem = NativeBuffer.create(this, size,
					shared ? getMemoryName().apply(size) : null);
			allocated.add(mem);
			return mem;
		}
	}

	@Override
	public synchronized void deallocate(int size, NativeBuffer mem) {
		if (!allocated.contains(mem)) return;

		memoryUsed -= (long) size * getNumberSize();
		allocated.remove(mem);
		mem.getDeallocationListeners().forEach(l -> l.accept(mem));
	}

	public synchronized boolean remove(NativeBuffer mem) {
		return allocated.remove(mem);
	}

	@Override
	public NativeBuffer reallocate(Memory mem, int offset, int length) {
		if (allocationAdapters.containsKey(mem.getClass())) {
			NativeBuffer buf = allocationAdapters.get(mem.getClass()).allocate(mem, offset, length);
			if (buf != null) return buf;
		}

		NativeBuffer newMem = allocate(length);
		setMem(newMem, 0, mem, offset, length);
		return newMem;
	}

	@Override
	public synchronized void setMem(NativeBuffer mem, int offset, Memory source, int srcOffset, int length) {
		if (!allocated.contains(mem))
			throw new HardwareException(mem + " not available");

		if (source instanceof NativeBuffer) {
			NativeBuffer sourceBuffer = (NativeBuffer) source;
			if (mem.getBuffer() instanceof DoubleBuffer && sourceBuffer.getBuffer() instanceof DoubleBuffer) {
				DoubleBuffer buffer = (DoubleBuffer) mem.getBuffer();
				buffer.put(offset, (DoubleBuffer) sourceBuffer.getBuffer(), srcOffset, length);
			} else if (mem.getBuffer() instanceof FloatBuffer && sourceBuffer.getBuffer() instanceof FloatBuffer) {
				FloatBuffer buffer = (FloatBuffer) mem.getBuffer();
				buffer.put(offset, (FloatBuffer) sourceBuffer.getBuffer(), srcOffset, length);
			} else if (mem.getBuffer() instanceof ShortBuffer && sourceBuffer.getBuffer() instanceof ShortBuffer) {
				ShortBuffer buffer = (ShortBuffer) mem.getBuffer();
				buffer.put(offset, (ShortBuffer) sourceBuffer.getBuffer(), srcOffset, length);
			} else {
				throw new HardwareException("Unsupported precision");
			}

			mem.sync();
		} else if (writeAdapters.containsKey(source.getClass())) {
			writeAdapters.get(source.getClass()).setMem(mem, offset, source, srcOffset, length);
		} else {
			double value[] = new double[length];
			source.getProvider().getMem(source, srcOffset, value, 0, length);
			setMem(mem, offset, value, 0, length);
		}
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

		mem.sync();
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

		mem.sync();
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

	public static <T extends Memory> void registerAdapter(Class<T> cls, NativeBufferAllocator<T> allocator) {
		allocationAdapters.put(cls, allocator);
	}

	public static <T extends Memory> void registerAdapter(Class<T> cls, NativeBufferWriter<T> writer) {
		writeAdapters.put(cls, writer);
	}
}
