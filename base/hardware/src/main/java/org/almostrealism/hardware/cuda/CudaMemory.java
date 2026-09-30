/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.hardware.cuda;

import org.almostrealism.hardware.mem.RAM;

/**
 * Memory allocated by a {@link CudaMemoryProvider}, backed by a {@link CUDeviceBuffer}.
 *
 * <p>The container pointer is the allocation's device address, which uniquely identifies
 * the allocation for bookkeeping. For a managed allocation the device address is also a
 * valid host address, so the content pointer can be handed to host code. For a device
 * allocation the content pointer is the same device address: it identifies the memory
 * (for example to the kernel memory guard) but must not be dereferenced on the host.</p>
 */
public class CudaMemory extends RAM {
	/** The provider that allocated this memory. */
	private final CudaMemoryProvider provider;

	/** The underlying allocation. */
	private final CUDeviceBuffer buffer;

	/**
	 * Wraps an allocation.
	 *
	 * @param provider the provider that allocated it
	 * @param buffer   the allocation
	 */
	protected CudaMemory(CudaMemoryProvider provider, CUDeviceBuffer buffer) {
		this.provider = provider;
		this.buffer = buffer;
	}

	/** Returns the underlying allocation. */
	public CUDeviceBuffer getBuffer() { return buffer; }

	@Override
	public boolean isActive() {
		return !buffer.isReleased();
	}

	@Override
	public long getSize() { return buffer.getSize(); }

	@Override
	public long getContainerPointer() { return buffer.getNativePointer(); }

	@Override
	public long getContentPointer() { return buffer.getNativePointer(); }

	@Override
	public CudaMemoryProvider getProvider() { return provider; }
}
