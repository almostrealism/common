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

package org.almostrealism.nio;

import io.almostrealism.code.Memory;
import io.almostrealism.code.Precision;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.hardware.mem.NativeRef;

import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Memory provider for CPU-side native direct buffers, backed by JNI-allocated {@link NativeBuffer} instances.
 *
 * <p>Manages allocation of typed NIO direct buffers with FP16, FP32, or FP64 precision.
 * Supports optional shared-memory naming to enable zero-copy data transfer between processes.</p>
 *
 * <p>Allocation is bounded by {@code memoryMax}; exceeding this limit throws a
 * {@link org.almostrealism.hardware.HardwareException}. Custom allocators and writers can be registered
 * via {@link #registerAdapter(Class, NativeBufferAllocator)} and
 * {@link #registerAdapter(Class, NativeBufferWriter)} to support cross-provider memory access.</p>
 *
 * @see NativeBuffer
 * @see NIO
 * @see HardwareMemoryProvider
 */
public class NativeBufferMemoryProvider extends HardwareMemoryProvider<NativeBuffer> {
	/** Registered custom allocators for adapting foreign memory types. */
	private static final Map<Class, NativeBufferAllocator> allocationAdapters = new HashMap<>();
	/** Registered custom writers for adapting foreign memory types. */
	private static final Map<Class, NativeBufferWriter> writeAdapters = new HashMap<>();

	/** Numeric precision for element storage in this provider's buffers. */
	private final Precision precision;
	/** Maximum number of bytes that may be allocated by this provider at any one time. */
	private final long memoryMax;
	/** If true, new allocations use named shared memory when a name is available. */
	private final boolean shared;

	/** Total bytes currently allocated across all active buffers in this provider. */
	private long memoryUsed;

	/**
	 * Creates a native buffer provider with shared memory enabled.
	 *
	 * @param precision Numeric precision for buffer elements
	 * @param memoryMax Maximum bytes that may be allocated
	 */
	public NativeBufferMemoryProvider(Precision precision, long memoryMax) {
		this(precision, memoryMax, true);
	}

	/**
	 * Creates a native buffer provider with optional shared memory support.
	 *
	 * @param precision Numeric precision for buffer elements
	 * @param memoryMax Maximum bytes that may be allocated
	 * @param shared    If true, use named shared memory when a memory name is available
	 */
	public NativeBufferMemoryProvider(Precision precision, long memoryMax, boolean shared) {
		this.precision = precision;
		this.memoryMax = memoryMax;
		this.shared = shared;
	}

	@Override
	public String getName() { return "NIO"; }

	public Precision getPrecision() { return precision; }

	@Override
	public int getNumberSize() { return getPrecision().bytes(); }

	@Override
	public synchronized NativeBuffer allocate(int size) {
		if (memoryUsed + (long) getNumberSize() * size > memoryMax) {
			throw new HardwareException("Memory max reached");
		} else {
			memoryUsed += (long) getNumberSize() * size;
			NativeBuffer mem = NativeBuffer.create(this, size,
					shared ? getMemoryName().apply(size) : null);
			return mem;
		}
	}

	@Override
	protected NativeRef<NativeBuffer> nativeRef(NativeBuffer ram) {
		return new NativeBufferRef(ram, getReferenceQueue());
	}

	@Override
	public synchronized void deallocate(NativeRef<NativeBuffer> ref) {
		memoryUsed -= ref.getSize();

		if (ref instanceof NativeBufferRef bufferRef) {
			if (bufferRef.getSharedLocation() != null && bufferRef.getRootBuffer() != null) {
				NIO.unmapSharedMemory(bufferRef.getRootBuffer(), bufferRef.getRootBuffer().capacity());
			}

			bufferRef.getDeallocationListeners().forEach(l -> l.accept(null));
		}
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
		if (source instanceof NativeBuffer sourceBuffer) {
			if (mem.getBuffer() instanceof DoubleBuffer buffer && sourceBuffer.getBuffer() instanceof DoubleBuffer) {
				buffer.put(offset, (DoubleBuffer) sourceBuffer.getBuffer(), srcOffset, length);
			} else if (mem.getBuffer() instanceof FloatBuffer buffer && sourceBuffer.getBuffer() instanceof FloatBuffer) {
				buffer.put(offset, (FloatBuffer) sourceBuffer.getBuffer(), srcOffset, length);
			} else if (mem.getBuffer() instanceof ShortBuffer buffer && sourceBuffer.getBuffer() instanceof ShortBuffer) {
				buffer.put(offset, (ShortBuffer) sourceBuffer.getBuffer(), srcOffset, length);
			} else {
				throw new HardwareException("Unsupported precision");
			}

			mem.sync();
		} else if (writeAdapters.containsKey(source.getClass())) {
			writeAdapters.get(source.getClass()).setMem(mem, offset, source, srcOffset, length);
		} else {
			double[] value = new double[length];
			source.getProvider().getMem(source, srcOffset, value, 0, length);
			setMem(mem, offset, value, 0, length);
		}
	}

	@Override
	public synchronized void setMem(NativeBuffer mem, int offset, double[] source, int srcOffset, int length) {
		if (mem.getBuffer() instanceof DoubleBuffer buffer) {
			buffer.position(offset);
			buffer.put(source, srcOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer buffer) {
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
		if (mem.getBuffer() instanceof DoubleBuffer buffer) {
			buffer.position(offset);
			for (int i = 0; i < length; i++) {
				buffer.put(source[srcOffset + i]);
			}
		} else if (mem.getBuffer() instanceof FloatBuffer buffer) {
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
		if (mem.getBuffer() instanceof DoubleBuffer buffer) {
			buffer.position(sOffset);
			buffer.get(out, oOffset, length);
		} else if (mem.getBuffer() instanceof FloatBuffer buffer) {
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
		if (mem.getBuffer() instanceof DoubleBuffer buffer) {
			buffer.position(sOffset);
			for (int i = 0; i < length; i++) {
				out[oOffset + i] = (float) buffer.get();
			}
		} else if (mem.getBuffer() instanceof FloatBuffer buffer) {
			buffer.position(sOffset);
			buffer.get(out, oOffset, length);
		} else if (mem.getBuffer() instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}

	/**
	 * Registers a custom allocator that can create a {@link NativeBuffer} from a foreign memory type.
	 *
	 * @param <T>       Foreign memory type
	 * @param cls       Class of the foreign memory type
	 * @param allocator Allocator that converts the foreign memory to a native buffer
	 */
	public static <T extends Memory> void registerAdapter(Class<T> cls, NativeBufferAllocator<T> allocator) {
		allocationAdapters.put(cls, allocator);
	}

	/**
	 * Registers a custom writer that can copy data from a foreign memory type into a {@link NativeBuffer}.
	 *
	 * @param <T>    Foreign memory type
	 * @param cls    Class of the foreign memory type
	 * @param writer Writer that copies from the foreign memory into a native buffer
	 */
	public static <T extends Memory> void registerAdapter(Class<T> cls, NativeBufferWriter<T> writer) {
		writeAdapters.put(cls, writer);
	}
}
