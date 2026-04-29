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

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.mem.RAM;
import org.jocl.cl_mem;

/**
 * {@link RAM} implementation backed by OpenCL {@link cl_mem} buffer.
 *
 * <p>{@link CLMemory} wraps an OpenCL memory object ({@link cl_mem}) allocated
 * by {@link CLMemoryProvider}, providing access to GPU/CPU device memory.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * CLMemoryProvider provider = ...;
 * CLMemory mem = provider.allocate(1024);  // Allocate 1024 elements
 *
 * // Access OpenCL memory object
 * cl_mem clMem = mem.getMem();
 *
 * // Get size in bytes
 * long bytes = mem.getSize();
 *
 * // Get native pointer
 * long ptr = mem.getContentPointer();
 * }</pre>
 *
 * @see CLMemoryProvider
 * @see RAM
 */
public class CLMemory extends RAM {
	/** The underlying OpenCL memory object handle. */
	private final cl_mem mem;

	/** The size of this memory allocation in bytes. */
	private final long size;

	/** The memory provider that allocated this buffer. */
	private final CLMemoryProvider provider;

	/** True once {@code clReleaseMemObject} has been invoked for the underlying handle. */
	private volatile boolean released;

	/**
	 * Creates a new CLMemory wrapping an OpenCL memory buffer.
	 *
	 * @param provider  the memory provider that allocated this buffer
	 * @param mem       the OpenCL memory object handle
	 * @param size      the size of the allocation in bytes
	 */
	protected CLMemory(CLMemoryProvider provider, cl_mem mem, long size) {
		this.provider = provider;
		this.mem = mem;
		this.size = size;
	}

	/**
	 * Returns whether the underlying OpenCL memory object has already been released.
	 *
	 * @return true once {@code clReleaseMemObject} has been called for this buffer
	 */
	public boolean isReleased() { return released; }

	/**
	 * Atomically claims this buffer for release. Returns true to the first caller
	 * (which is then responsible for calling {@code clReleaseMemObject}); subsequent
	 * callers receive false and must skip the native release call.
	 *
	 * <p>If the native release fails, the caller should invoke {@link #unclaimReleased()}
	 * so that a future deallocation attempt can retry. Otherwise the buffer stays
	 * marked released but the underlying {@code cl_mem} handle and the provider's
	 * tracking metadata leak permanently.</p>
	 *
	 * @return true if this caller is responsible for releasing the underlying handle
	 */
	public synchronized boolean tryClaimReleased() {
		if (released) return false;
		released = true;
		return true;
	}

	/**
	 * Reverses a previous successful claim of {@link #tryClaimReleased()} when the
	 * native release call failed. Restores this buffer to an unreleased state so
	 * that a subsequent deallocation attempt can retry.
	 */
	public synchronized void unclaimReleased() {
		released = false;
	}

	@Override
	public boolean isActive() { return !released; }

	/**
	 * Returns the underlying OpenCL memory object handle.
	 *
	 * @return the OpenCL memory object
	 */
	protected cl_mem getMem() { return mem; }

	/**
	 * Returns the size of this memory allocation in bytes.
	 *
	 * @return the size in bytes
	 */
	@Override
	public long getSize() {
		return size;
	}

	/**
	 * Returns the native pointer to the OpenCL memory object.
	 *
	 * @return the native pointer value
	 */
	@Override
	public long getContentPointer() { return mem.getNativePointer(); }

	/**
	 * Returns the memory provider that allocated this buffer.
	 *
	 * @return the CLMemoryProvider for this memory
	 */
	@Override
	public CLMemoryProvider getProvider() { return provider; }
}
