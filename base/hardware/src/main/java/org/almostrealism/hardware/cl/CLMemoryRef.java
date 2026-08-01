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

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.mem.NativeRef;
import org.jocl.cl_mem;

import java.lang.ref.ReferenceQueue;

/**
 * {@link NativeRef} for tracking {@link CLMemory} instances with garbage collection.
 *
 * <p>Retains a reference to the underlying OpenCL {@link cl_mem} buffer so that it can be
 * released when the {@link CLMemory} becomes unreachable. Because {@link NativeRef} is a
 * {@link java.lang.ref.PhantomReference}, the referent is no longer accessible once the
 * reference is enqueued; caching the {@link cl_mem} here is what allows
 * {@link CLMemoryProvider#deallocate(NativeRef)} to call {@code clReleaseMemObject} after
 * collection.</p>
 *
 * @see CLMemoryProvider
 * @see CLMemory
 */
public class CLMemoryRef extends NativeRef<CLMemory> {
	/** Retained OpenCL buffer handle, held until the GC notification arrives for release. */
	private final cl_mem mem;

	/**
	 * Creates a reference for tracking OpenCL memory lifecycle.
	 *
	 * @param memory         the {@link CLMemory} to track
	 * @param referenceQueue queue for receiving GC notifications
	 */
	public CLMemoryRef(CLMemory memory, ReferenceQueue<? super CLMemory> referenceQueue) {
		super(memory, referenceQueue);
		this.mem = memory.getMem();
	}

	/**
	 * Returns the OpenCL buffer handle to release.
	 *
	 * @return the {@link cl_mem} to free
	 */
	public cl_mem getMem() {
		return mem;
	}
}
