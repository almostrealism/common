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

package org.almostrealism.nio;

import io.almostrealism.code.MemoryProvider;
import io.almostrealism.code.Precision;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.DirectMemory;
import org.almostrealism.hardware.mem.KernelMemoryGuard;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JNI-backed direct memory buffer used as the native RAM representation for CPU-side hardware execution.
 *
 * <p>Each {@link NativeBuffer} wraps a Java NIO direct {@link Buffer} allocated either via
 * {@link ByteBuffer#allocateDirect} or via shared memory ({@link NativeMemoryProvider#mapSharedMemory}).
 * The raw native pointer to the buffer is exposed for kernel argument passing.</p>
 *
 * <p>Host-side element access ({@link #read}, {@link #write}, {@link #copyFrom} and
 * {@link #toDouble(int)}) is performed by the buffer itself through its typed view, so the
 * provider's bulk {@code getMem}/{@code setMem} operations delegate here rather than inspecting
 * the view.</p>
 *
 * <p>When the buffer is backed by shared memory, {@link #destroy()} unmaps the region.
 * Deallocation listeners are called by {@link NativeMemoryProvider} after GC cleanup.</p>
 *
 * @see NativeMemoryProvider
 */
public class NativeBuffer extends DirectMemory implements Destroyable {
	/** The memory provider that allocated this buffer. */
	private final NativeMemoryProvider provider;
	/** Root byte buffer used for shared memory mapping and capacity queries. */
	private final ByteBuffer rootBuffer;
	/** Typed view of the buffer (float, double, or short depending on precision). */
	private final Buffer buffer;
	/** Shared memory path, or null if this buffer uses private memory. */
	private final String sharedLocation;
	/** Listeners notified when this buffer is deallocated. */
	private List<Consumer<NativeBuffer>> deallocationListeners;

	/**
	 * Creates a native buffer backed by the given direct buffers.
	 *
	 * @param provider       Memory provider that owns this buffer
	 * @param rootBuffer     Root byte buffer for capacity and mapping operations
	 * @param buffer         Typed view used for kernel argument passing
	 * @param sharedLocation Shared memory path, or null for private allocation
	 */
	protected NativeBuffer(NativeMemoryProvider provider,
						   ByteBuffer rootBuffer, Buffer buffer,
						   String sharedLocation) {
		if (!rootBuffer.isDirect() || !buffer.isDirect())
			throw new UnsupportedOperationException();
		this.provider = provider;
		this.rootBuffer = rootBuffer;
		this.buffer = buffer;
		this.sharedLocation = sharedLocation;
		this.deallocationListeners = new ArrayList<>();
	}

	@Override
	public MemoryProvider getProvider() { return provider; }

	@Override
	public long getContentPointer() { return provider.pointerForBuffer(buffer); }

	public Buffer getBuffer() { return buffer; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>When this buffer holds its elements in a {@link DoubleBuffer} or {@link FloatBuffer},
	 * the value is read straight from it, avoiding the one-element array the array-mediated read
	 * allocates. The read is absolute, so it neither depends on nor moves the buffer position
	 * the provider's bulk reads and writes use. FP16 buffers, backed by a {@link ShortBuffer},
	 * fall back to the array-mediated read, preserving its behavior.</p>
	 */
	@Override
	public double toDouble(int offset) {
		if (buffer instanceof DoubleBuffer typed) {
			return typed.get(offset);
		} else if (buffer instanceof FloatBuffer typed) {
			return typed.get(offset);
		}

		return super.toDouble(offset);
	}

	/**
	 * Copies elements from another buffer of the same precision into this one, then flushes
	 * shared memory.
	 *
	 * @param offset    position in this buffer of the first element written
	 * @param source    buffer to copy from, holding elements of the same precision
	 * @param srcOffset position in {@code source} of the first element read
	 * @param length    number of elements to copy
	 * @throws HardwareException if the two buffers do not hold the same precision
	 */
	public void copyFrom(int offset, NativeBuffer source, int srcOffset, int length) {
		if (buffer instanceof DoubleBuffer typed && source.buffer instanceof DoubleBuffer sourceTyped) {
			typed.put(offset, sourceTyped, srcOffset, length);
		} else if (buffer instanceof FloatBuffer typed && source.buffer instanceof FloatBuffer sourceTyped) {
			typed.put(offset, sourceTyped, srcOffset, length);
		} else if (buffer instanceof ShortBuffer typed && source.buffer instanceof ShortBuffer sourceTyped) {
			typed.put(offset, sourceTyped, srcOffset, length);
		} else {
			throw new HardwareException("Unsupported precision");
		}

		sync();
	}

	/**
	 * Writes values into this buffer, converting them to its precision, then flushes shared memory.
	 *
	 * @param offset    position in this buffer of the first element written
	 * @param source    values to write
	 * @param srcOffset position in {@code source} of the first value written
	 * @param length    number of values to write
	 * @throws UnsupportedOperationException if this buffer holds FP16 elements
	 */
	public void write(int offset, double[] source, int srcOffset, int length) {
		if (buffer instanceof DoubleBuffer typed) {
			typed.position(offset);
			typed.put(source, srcOffset, length);
		} else if (buffer instanceof FloatBuffer typed) {
			typed.position(offset);
			for (int i = 0; i < length; i++) {
				typed.put((float) source[srcOffset + i]);
			}
		} else if (buffer instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}

		sync();
	}

	/**
	 * Reads elements of this buffer into an array.
	 *
	 * @param offset  position in this buffer of the first element read
	 * @param out     array receiving the values
	 * @param oOffset position in {@code out} of the first value stored
	 * @param length  number of elements to read
	 * @throws UnsupportedOperationException if this buffer holds FP16 elements
	 */
	public void read(int offset, double[] out, int oOffset, int length) {
		if (buffer instanceof DoubleBuffer typed) {
			typed.position(offset);
			typed.get(out, oOffset, length);
		} else if (buffer instanceof FloatBuffer typed) {
			typed.position(offset);
			for (int i = 0; i < length; i++) {
				out[oOffset + i] = typed.get();
			}
		} else if (buffer instanceof ShortBuffer) {
			throw new UnsupportedOperationException();
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns the root {@link ByteBuffer} backing this buffer; the typed view returned by
	 * {@link #getBuffer()} shares the same storage.</p>
	 */
	@Override
	public ByteBuffer asByteBuffer() { return rootBuffer; }

	/**
	 * Returns the root {@link ByteBuffer} backing this native buffer.
	 *
	 * <p>Used by {@link NativeBufferRef} to cache the buffer for post-GC
	 * shared memory unmapping.</p>
	 *
	 * @return The root byte buffer
	 */
	public ByteBuffer getRootBuffer() { return rootBuffer; }

	/**
	 * Returns the shared memory location path, if this buffer is backed by shared memory.
	 *
	 * <p>Used by {@link NativeBufferRef} to cache the path for post-GC
	 * shared memory unmapping.</p>
	 *
	 * @return The shared location path, or {@code null} if not shared
	 */
	public String getSharedLocation() { return sharedLocation; }

	/**
	 * Synchronizes the shared memory buffer, flushing any pending changes back to the region.
	 *
	 * <p>Has no effect if this buffer is not backed by shared memory.</p>
	 */
	public void sync() {
		if (sharedLocation != null) {
			provider.syncSharedMemory(rootBuffer, rootBuffer.capacity());
		}
	}

	@Override
	public void destroy() {
		// Diagnostic: warn if a kernel is still actively using this buffer.
		// We proceed with unmap regardless — blocking or silently deferring
		// explicit destroy is as dangerous as complex finalizer logic.
		Hardware hardware = Hardware.getLocalHardware();
		KernelMemoryGuard guard = hardware == null ? null : hardware.getKernelMemoryGuard();
		if (guard != null) {
			guard.warnIfReserved("NativeBuffer", getContainerPointer(), getAllocationStackTrace());
		}

		if (sharedLocation != null) {
			provider.unmapSharedMemory(rootBuffer, rootBuffer.capacity());
		}
	}

	@Override
	public long getSize() {
		return provider.getNumberSize() * (long) buffer.capacity();
	}

	/**
	 * Registers a listener to be called when this buffer is deallocated.
	 *
	 * @param listener Consumer called with null at deallocation time
	 */
	public void addDeallocationListener(Consumer<NativeBuffer> listener) {
		deallocationListeners.add(listener);
	}

	/**
	 * Returns the list of deallocation listeners registered for this buffer.
	 *
	 * @return List of deallocation listeners
	 */
	public List<Consumer<NativeBuffer>> getDeallocationListeners() {
		return deallocationListeners;
	}

	/**
	 * Allocates or maps a direct {@link ByteBuffer} for the given size.
	 *
	 * @param provider       Provider whose shared-memory operations map a named region
	 * @param bytes          Number of bytes to allocate
	 * @param sharedLocation Shared memory path, or null for private allocation
	 * @return Direct byte buffer backed by private or shared memory
	 */
	protected static ByteBuffer buffer(NativeMemoryProvider provider, int bytes, String sharedLocation) {
		if (sharedLocation != null) {
			ByteBuffer buffer = provider.mapSharedMemory(sharedLocation, bytes)
					.order(ByteOrder.nativeOrder());
			Runtime.getRuntime().addShutdownHook(
					new Thread(() -> new File(sharedLocation).delete()));
			return buffer;
		} else {
			return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
		}
	}

	/**
	 * Creates a typed native buffer with the precision determined by the given provider.
	 *
	 * @param provider       Provider that determines the number precision
	 * @param len            Number of elements to allocate
	 * @param sharedLocation Shared memory path, or null for private allocation
	 * @return New {@link NativeBuffer} wrapping a typed direct buffer
	 * @throws HardwareException If the provider's precision is not supported
	 */
	public static NativeBuffer create(NativeMemoryProvider provider, int len, String sharedLocation) {
		if (provider.getPrecision() == Precision.FP16) {
			ByteBuffer bufferByte = buffer(provider, len * 2, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asShortBuffer(), sharedLocation);
		} else if (provider.getPrecision() == Precision.FP32) {
			ByteBuffer bufferByte = buffer(provider, len * 4, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asFloatBuffer(), sharedLocation);
		} else if (provider.getPrecision() == Precision.FP64) {
			ByteBuffer bufferByte = buffer(provider, len * 8, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asDoubleBuffer(), sharedLocation);
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}
}
