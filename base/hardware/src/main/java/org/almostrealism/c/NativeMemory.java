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

package org.almostrealism.c;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.mem.ByteBufferMemory;

import java.nio.ByteBuffer;

/**
 * {@link ByteBufferMemory} implementation backed by JNI-allocated native memory.
 *
 * <p>{@link NativeMemory} wraps a native memory pointer allocated via JNI malloc, providing access
 * to CPU memory for native code execution. Its {@link #getByteBuffer() ByteBuffer view} is produced
 * by a runtime-compiled {@code NewDirectByteBuffer} operation ({@link NativeBufferView}) over that
 * pointer, so the memory is host-accessible for bulk transfers without any prebuilt native library.</p>
 *
 * @see org.almostrealism.nio.NativeMemoryProvider
 * @see ByteBufferMemory
 */
public class NativeMemory extends ByteBufferMemory {
	/** The memory provider that allocated this buffer. */
	private final MemoryProvider provider;

	/** The native pointer to the allocated memory. */
	private final long nativePointer;

	/** The size of this memory allocation in bytes. */
	private final long size;

	/** A non-owning direct buffer view over the allocation, for host-accessible bulk transfers. */
	private final ByteBuffer byteBuffer;

	/**
	 * Creates a new NativeMemory wrapping a native memory pointer.
	 *
	 * @param provider      the memory provider that allocated this buffer
	 * @param nativePointer the native pointer to the allocated memory
	 * @param size          the size of the allocation in bytes
	 * @param byteBuffer    a non-owning direct buffer view over the whole allocation
	 */
	public NativeMemory(MemoryProvider provider, long nativePointer, long size, ByteBuffer byteBuffer) {
		this.provider = provider;
		this.nativePointer = nativePointer;
		this.size = size;
		this.byteBuffer = byteBuffer;
	}

	/** {@inheritDoc} */
	@Override
	public MemoryProvider getProvider() { return provider; }

	/** {@inheritDoc} */
	@Override
	public long getContentPointer() {
		return nativePointer;
	}

	/** {@inheritDoc} */
	@Override
	public long getSize() { return size; }

	/** {@inheritDoc} */
	@Override
	public ByteBuffer getByteBuffer() { return byteBuffer; }
}
