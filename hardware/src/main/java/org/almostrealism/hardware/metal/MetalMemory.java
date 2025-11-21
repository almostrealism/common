/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.metal;

import org.almostrealism.hardware.mem.RAM;

/**
 * {@link RAM} backed by Metal {@link MTLBuffer}.
 *
 * <p>Wraps a Metal GPU buffer for use with {@link MetalMemoryProvider}.</p>
 *
 * @see MetalMemoryProvider
 * @see MTLBuffer
 */
public class MetalMemory extends RAM {
	private final MTLBuffer mem;
	private final long size;
	private final MetalMemoryProvider provider;

	/**
	 * Creates Metal memory wrapping a Metal buffer.
	 *
	 * @param provider The {@link MetalMemoryProvider} managing this memory
	 * @param mem The {@link MTLBuffer} providing GPU storage
	 * @param size Size in bytes
	 */
	protected MetalMemory(MetalMemoryProvider provider, MTLBuffer mem, long size) {
		this.provider = provider;
		this.mem = mem;
		this.size = size;
	}

	/**
	 * Returns the underlying Metal buffer.
	 *
	 * @return The {@link MTLBuffer} instance
	 */
	protected MTLBuffer getMem() { return mem; }

	/**
	 * Checks if the underlying Metal buffer is still active and not released.
	 *
	 * @return True if buffer exists and has not been released
	 */
	public boolean isActive() {
		return mem != null && !mem.isReleased();
	}

	/**
	 * Returns the size of this memory region in bytes.
	 *
	 * @return Memory size in bytes
	 */
	@Override
	public long getSize() { return size; }

	/**
	 * Returns the native pointer to the Metal buffer container.
	 *
	 * @return Native {@code id<MTLBuffer>} pointer
	 */
	@Override
	public long getContainerPointer() { return mem.getNativePointer(); }

	/**
	 * Returns the native pointer to the buffer's content memory.
	 *
	 * @return Native pointer to buffer contents
	 */
	@Override
	public long getContentPointer() { return mem.getContentPointer(); }

	/**
	 * Returns the memory provider managing this Metal memory.
	 *
	 * @return The {@link MetalMemoryProvider} instance
	 */
	@Override
	public MetalMemoryProvider getProvider() { return provider; }
}
