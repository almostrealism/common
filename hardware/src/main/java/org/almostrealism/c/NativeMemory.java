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
import org.almostrealism.hardware.mem.RAM;

/**
 * {@link RAM} implementation backed by JNI-allocated native memory.
 *
 * <p>{@link NativeMemory} wraps a native memory pointer allocated via JNI malloc,
 * providing access to CPU memory for native code execution.</p>
 *
 * @see NativeMemoryProvider
 * @see RAM
 */
public class NativeMemory extends RAM {
	/** The memory provider that allocated this buffer. */
	private final MemoryProvider provider;

	/** The native pointer to the allocated memory. */
	private final long nativePointer;

	/** The size of this memory allocation in bytes. */
	private final long size;

	/**
	 * Creates a new NativeMemory wrapping a native memory pointer.
	 *
	 * @param provider      the memory provider that allocated this buffer
	 * @param nativePointer the native pointer to the allocated memory
	 * @param size          the size of the allocation in bytes
	 */
	public NativeMemory(MemoryProvider provider, long nativePointer, long size) {
		this.provider = provider;
		this.nativePointer = nativePointer;
		this.size = size;
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
}
