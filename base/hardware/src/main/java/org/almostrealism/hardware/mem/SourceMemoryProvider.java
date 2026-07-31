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
import io.almostrealism.code.MemoryProvider;

/**
 * Base class for read-only {@link MemoryProvider}s that treat data entering the
 * JVM from outside the system — a serialized message, an NIO buffer produced by
 * an external runtime, a mapped file — as its own kind of device.
 *
 * <p>Memory managed by a source provider holds no host arrays and no device
 * memory of its own: reads are served from the external source, and the first
 * time a kernel needs the data the framework migrates the root reservation to
 * the compute provider (small arguments are instead copied into the dispatch
 * aggregate per invocation, so host-only data never permanently occupies
 * device memory). Migration is one-way: writes into source-backed memory are
 * rejected rather than silently lost, and nothing ever writes back to the
 * external source.</p>
 *
 * <p>Subclasses supply the format-specific {@code Memory} handles through
 * their own factory methods; {@link #allocate(int)} is unsupported because a
 * source provider cannot create empty regions.</p>
 */
public abstract class SourceMemoryProvider implements MemoryProvider<Memory> {

	/**
	 * Returns the size of each number in bytes, as served to readers.
	 *
	 * @return 8 (values are read as FP64 regardless of the source's encoded precision)
	 */
	@Override
	public int getNumberSize() { return 8; }

	/**
	 * Unsupported: a source provider only wraps existing external data through
	 * its own factory methods; it cannot create empty regions.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public Memory allocate(int size) {
		throw new UnsupportedOperationException(getName() +
				" is a read-only source and cannot allocate empty memory");
	}

	/**
	 * Unsupported: source-backed memory is read-only, and migration to a
	 * device is one-way, so a write here could only be silently lost.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void setMem(Memory mem, int offset, Memory source, int srcOffset, int length) {
		throw new UnsupportedOperationException(getName() + " backed memory is read-only");
	}

	/**
	 * Unsupported: source-backed memory is read-only, and migration to a
	 * device is one-way, so a write here could only be silently lost.
	 *
	 * @throws UnsupportedOperationException always
	 */
	@Override
	public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		throw new UnsupportedOperationException(getName() + " backed memory is read-only");
	}

	/**
	 * Releases all resources held by this memory provider.
	 *
	 * <p>Nothing to release by default: each memory's source reference is
	 * dropped by {@link #deallocate} when its owner is destroyed.</p>
	 */
	@Override
	public void destroy() { }
}
