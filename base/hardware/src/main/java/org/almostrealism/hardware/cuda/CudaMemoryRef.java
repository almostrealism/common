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

import org.almostrealism.hardware.mem.NativeRef;

import java.lang.ref.ReferenceQueue;

/**
 * Tracks a {@link CudaMemory} for release once it is no longer reachable, retaining the
 * {@link CUDeviceBuffer} so that the allocation can be freed after the {@link CudaMemory}
 * itself has been collected.
 */
public class CudaMemoryRef extends NativeRef<CudaMemory> {
	/** The allocation to free when the memory is released. */
	private final CUDeviceBuffer buffer;

	/**
	 * Creates a reference to the given memory.
	 *
	 * @param memory         the memory to track
	 * @param referenceQueue the queue notified when the memory becomes unreachable
	 */
	public CudaMemoryRef(CudaMemory memory, ReferenceQueue<? super CudaMemory> referenceQueue) {
		super(memory, referenceQueue);
		this.buffer = memory.getBuffer();
	}

	/** Returns the allocation to free. */
	public CUDeviceBuffer getBuffer() { return buffer; }
}
