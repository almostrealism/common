/*
 * Copyright 2021 Michael Murray
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
import org.almostrealism.hardware.mem.DirectMemory;

import java.nio.ByteBuffer;

/**
 * {@link DirectMemory} backed by JNI-allocated ({@link Malloc}) native memory.
 *
 * <p>The native pointer is the single source of truth for where this memory lives. The
 * {@link #asByteBuffer() direct-buffer view} is derived from that pointer on demand — through a
 * runtime-compiled {@code NewDirectByteBuffer} operation ({@link NativeBufferView}) obtained from the
 * owning provider — so the memory is host-accessible for bulk transfers without any prebuilt native
 * library, and there is no second address that could disagree with the pointer.</p>
 *
 * @see NativeMemoryProvider
 * @see DirectMemory
 */
public class NativeMemory extends DirectMemory {
	/** The provider that allocated this memory and derives its buffer view. */
	private final NativeMemoryProvider provider;

	/** The native pointer to the allocated memory; the sole source of truth for its location. */
	private final long nativePointer;

	/** The size of this memory allocation in bytes. */
	private final long size;

	/** Cached non-owning direct-buffer view, derived from {@link #nativePointer} on first use. */
	private ByteBuffer byteBuffer;

	/**
	 * Creates a new NativeMemory over a native pointer.
	 *
	 * @param provider      the provider that allocated this memory
	 * @param nativePointer the native pointer to the allocated memory
	 * @param size          the size of the allocation in bytes
	 */
	public NativeMemory(NativeMemoryProvider provider, long nativePointer, long size) {
		this.provider = provider;
		this.nativePointer = nativePointer;
		this.size = size;
	}

	/** {@inheritDoc} */
	@Override
	public MemoryProvider getProvider() { return provider; }

	/** {@inheritDoc} */
	@Override
	public long getContentPointer() { return nativePointer; }

	/** {@inheritDoc} */
	@Override
	public long getSize() { return size; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>The buffer is derived from {@link #getContentPointer() the native pointer} on first use
	 * (via the owning {@link NativeMemoryProvider}) and cached, so the pointer remains the only
	 * source of truth for this memory's location.</p>
	 */
	@Override
	public synchronized ByteBuffer asByteBuffer() {
		if (byteBuffer == null) {
			byteBuffer = provider.viewBuffer(nativePointer, size);
		}

		return byteBuffer;
	}
}
