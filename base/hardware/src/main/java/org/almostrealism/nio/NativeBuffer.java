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
import org.almostrealism.hardware.mem.DirectMemory;
import org.almostrealism.hardware.mem.KernelMemoryGuard;

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
 * {@link ByteBuffer#allocateDirect} or via shared memory ({@link NativeMemoryProvider#mapSharedMemory}).
 * The raw native pointer to the buffer is exposed for kernel argument passing.</p>
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
	/** Whether the backing region came from outside this provider rather than being allocated by it. */
	private final boolean foreign;
	/** Listeners notified when this buffer is deallocated. */
	private List<Consumer<NativeBuffer>> deallocationListeners;

	/**
	 * Creates a native buffer backed by the given direct buffers, allocated by the provider.
	 *
	 * @param provider       Memory provider that owns this buffer
	 * @param rootBuffer     Root byte buffer for capacity and mapping operations
	 * @param buffer         Typed view used for kernel argument passing
	 * @param sharedLocation Shared memory path, or null for private allocation
	 */
	protected NativeBuffer(NativeMemoryProvider provider,
						   ByteBuffer rootBuffer, Buffer buffer,
						   String sharedLocation) {
		this(provider, rootBuffer, buffer, sharedLocation, false);
	}

	/**
	 * Creates a native buffer backed by the given direct buffers.
	 *
	 * @param provider       Memory provider that manages this buffer
	 * @param rootBuffer     Root byte buffer for capacity and mapping operations
	 * @param buffer         Typed view used for kernel argument passing
	 * @param sharedLocation Shared memory path, or null for private allocation
	 * @param foreign        Whether the region came from outside the provider, in which case the
	 *                       provider neither counts it against its reservation nor releases it
	 */
	protected NativeBuffer(NativeMemoryProvider provider,
						   ByteBuffer rootBuffer, Buffer buffer,
						   String sharedLocation, boolean foreign) {
		if (!rootBuffer.isDirect() || !buffer.isDirect())
			throw new UnsupportedOperationException();
		this.provider = provider;
		this.rootBuffer = rootBuffer;
		this.buffer = buffer;
		this.sharedLocation = sharedLocation;
		this.foreign = foreign;
		this.deallocationListeners = new ArrayList<>();
	}

	/**
	 * Returns whether the backing region came from outside the provider.
	 *
	 * <p>A foreign buffer is one the provider was handed rather than one it allocated — see
	 * {@link NativeMemoryProvider#wrap(ByteBuffer, int)}. The provider tracks it so that the
	 * ordinary lifecycle applies, but does not count its bytes against its reservation and never
	 * frees or unmaps it: whatever produced the region still owns it. This instance holds the
	 * region strongly through {@link #getRootBuffer()}, which is what keeps the JVM's cleaner from
	 * reclaiming a direct buffer while a kernel is still reading it.</p>
	 *
	 * @return true if this buffer wraps a region the provider did not allocate
	 */
	public boolean isForeign() { return foreign; }

	@Override
	public MemoryProvider getProvider() { return provider; }

	@Override
	public long getContentPointer() { return provider.pointerForBuffer(buffer); }

	public Buffer getBuffer() { return buffer; }

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
		KernelMemoryGuard.warnIfActivelyReferenced(
				getContentPointer(), getAllocationStackTrace(), "NativeBuffer");

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
		ByteBuffer bufferByte = buffer(provider, len * provider.getPrecision().bytes(), sharedLocation);
		return new NativeBuffer(provider, bufferByte, provider.view(bufferByte), sharedLocation);
	}
}
