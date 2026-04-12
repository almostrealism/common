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
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.mem.RAM;

import java.io.File;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * JNI-backed direct memory buffer used as the native RAM representation for CPU-side hardware execution.
 *
 * <p>Each {@link NativeBuffer} wraps a Java NIO direct {@link Buffer} allocated either via
 * {@link ByteBuffer#allocateDirect} or via shared memory ({@link NIO#mapSharedMemory}).
 * The raw native pointer to the buffer is exposed for kernel argument passing.</p>
 *
 * <p>When the buffer is backed by shared memory, {@link #destroy()} unmaps the region.
 * Deallocation listeners are called by {@link NativeBufferMemoryProvider} after GC cleanup.</p>
 *
 * @see NativeBufferMemoryProvider
 * @see NIO
 */
public class NativeBuffer extends RAM implements Destroyable {
	/** The memory provider that allocated this buffer. */
	private final NativeBufferMemoryProvider provider;
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
	protected NativeBuffer(NativeBufferMemoryProvider provider,
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
	public MemoryProvider<NativeBuffer> getProvider() { return provider; }

	@Override
	public long getContentPointer() { return NIO.pointerForBuffer(buffer); }

	public Buffer getBuffer() { return buffer; }

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
			NIO.syncSharedMemory(rootBuffer, rootBuffer.capacity());
		}
	}

	@Override
	public void destroy() {
		if (sharedLocation != null) {
			NIO.unmapSharedMemory(rootBuffer, rootBuffer.capacity());
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
	 * @param bytes          Number of bytes to allocate
	 * @param sharedLocation Shared memory path, or null for private allocation
	 * @return Direct byte buffer backed by private or shared memory
	 */
	protected static ByteBuffer buffer(int bytes, String sharedLocation) {
		if (sharedLocation != null) {
			ByteBuffer buffer = NIO.mapSharedMemory(sharedLocation, bytes)
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
	public static NativeBuffer create(NativeBufferMemoryProvider provider, int len, String sharedLocation) {
		if (provider.getPrecision() == Precision.FP16) {
			ByteBuffer bufferByte = buffer(len * 2, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asShortBuffer(), sharedLocation);
		} else if (provider.getPrecision() == Precision.FP32) {
			ByteBuffer bufferByte = buffer(len * 4, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asFloatBuffer(), sharedLocation);
		} else if (provider.getPrecision() == Precision.FP64) {
			ByteBuffer bufferByte = buffer(len * 8, sharedLocation);
			return new NativeBuffer(provider, bufferByte, bufferByte.asDoubleBuffer(), sharedLocation);
		} else {
			throw new HardwareException("Unsupported precision");
		}
	}
}
