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

package org.almostrealism.hardware.mem;

import io.almostrealism.code.Memory;

/**
 * A {@link Bytes} root bound to {@link Memory} owned by another
 * {@link org.almostrealism.hardware.MemoryData}.
 *
 * <p>The view never deallocates the memory it was created around: neither an explicit
 * {@link #destroy()} nor the finalizer path frees it, so collecting the view cannot free
 * memory still in use by its owner. If the view is later moved to another provider (as
 * {@link org.almostrealism.hardware.HardwareOperator} does for an argument whose provider it
 * does not support), the replacement memory it receives is its own, and destroying the view
 * then frees that replacement like any other {@link Bytes}.</p>
 *
 * @see org.almostrealism.hardware.MemoryData#detachedView()
 */
public class BytesView extends Bytes {
	/**
	 * Creates a view of memory owned elsewhere.
	 *
	 * @param mem       the memory to view
	 * @param memLength the size of the memory in bytes
	 */
	public BytesView(Memory mem, int memLength) {
		super(mem, memLength, false);
	}
}
