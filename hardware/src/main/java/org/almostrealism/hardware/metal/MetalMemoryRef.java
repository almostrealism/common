/*
 * Copyright 2025 Michael Murray
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

import org.almostrealism.hardware.mem.NativeRef;

import java.lang.ref.ReferenceQueue;

/**
 * {@link NativeRef} for tracking {@link MetalMemory} instances with garbage collection.
 *
 * <p>Retains reference to the underlying {@link MTLBuffer} to enable deallocation
 * when the {@link MetalMemory} becomes unreachable.</p>
 *
 * @see MetalMemoryProvider
 * @see MetalMemory
 */
public class MetalMemoryRef extends NativeRef<MetalMemory> {
	private MTLBuffer buffer;

	/**
	 * Creates a reference for tracking Metal memory lifecycle.
	 *
	 * <p>Retains the {@link MTLBuffer} to enable deallocation when the
	 * {@link MetalMemory} is garbage collected.</p>
	 *
	 * @param memory The {@link MetalMemory} to track
	 * @param referenceQueue Queue for receiving GC notifications
	 */
	public MetalMemoryRef(MetalMemory memory, ReferenceQueue<? super MetalMemory> referenceQueue) {
		super(memory, referenceQueue);
		this.buffer = memory.getMem();
	}

	/**
	 * Returns the Metal buffer for deallocation.
	 *
	 * @return The {@link MTLBuffer} to release
	 */
	public MTLBuffer getBuffer() {
		return buffer;
	}
}
