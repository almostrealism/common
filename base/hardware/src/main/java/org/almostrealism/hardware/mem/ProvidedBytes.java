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
 * Root {@link org.almostrealism.hardware.MemoryData} over an externally
 * provided {@link Memory} handle, deferring any device allocation until the
 * framework migrates the root at first kernel use.
 *
 * <p>This is the delegation target for collections whose backing store is a
 * {@linkplain SourceMemoryProvider source provider} — a serialized message,
 * an NIO buffer, a mapped file. Constructing a collection over an instance of
 * this type touches no device memory at all; migration and per-dispatch
 * aggregation are handled by the framework exactly as for any other root
 * reservation.</p>
 *
 * @see SourceMemoryProvider
 */
public class ProvidedBytes extends MemoryDataAdapter {
	/** The number of elements the provided memory contains. */
	private final int memLength;

	/**
	 * Creates root memory data over the given memory handle.
	 *
	 * @param mem the externally provided memory to serve as backing store
	 * @param memLength the number of elements the memory contains
	 */
	public ProvidedBytes(Memory mem, int memLength) {
		this.memLength = memLength;
		init(mem);
	}

	/**
	 * Returns the number of elements the provided memory contains.
	 */
	@Override
	public int getMemLength() { return memLength; }
}
