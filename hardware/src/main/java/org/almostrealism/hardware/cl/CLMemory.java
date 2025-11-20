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
	private final cl_mem mem;
	private final long size;
	private final CLMemoryProvider provider;

	protected CLMemory(CLMemoryProvider provider, cl_mem mem, long size) {
		this.provider = provider;
		this.mem = mem;
		this.size = size;
	}

	protected cl_mem getMem() { return mem; }

	public long getSize() {
		return size;
	}

	@Override
	public long getContentPointer() { return mem.getNativePointer(); }

	@Override
	public CLMemoryProvider getProvider() { return provider; }
}
