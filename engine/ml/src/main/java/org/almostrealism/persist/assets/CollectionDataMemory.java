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

package org.almostrealism.persist.assets;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.protobuf.Collections;

/**
 * Read-only {@link Memory} backed by a protobuf {@link Collections.CollectionData}
 * message. Values are served directly from the message on each read — nothing is
 * materialized on the host and nothing reaches a device until the framework
 * migrates this memory to a device provider at first kernel use.
 *
 * @see CollectionDataMemoryProvider
 */
public class CollectionDataMemory implements Memory {
	/** The provider that manages this memory. */
	private final CollectionDataMemoryProvider provider;

	/** The protobuf message serving as the backing store; {@code null} once destroyed. */
	private Collections.CollectionData data;

	/** The number of elements the message contains. */
	private final int length;

	/**
	 * Creates memory backed by the given protobuf message.
	 *
	 * @param provider the managing provider
	 * @param data     the message serving as the backing store
	 */
	protected CollectionDataMemory(CollectionDataMemoryProvider provider,
								   Collections.CollectionData data) {
		this.provider = provider;
		this.data = data;
		this.length = data.getDataCount() > 0 ? data.getDataCount() : data.getData32Count();
	}

	/**
	 * Returns the provider that manages this memory.
	 */
	@Override
	public MemoryProvider getProvider() { return provider; }

	/**
	 * Returns the number of elements available from the backing message.
	 */
	public int getLength() { return length; }

	/**
	 * Reads the element at the given position from the backing message,
	 * whichever precision the message was encoded at.
	 *
	 * @param index the element position
	 * @return the value at that position
	 * @throws IllegalStateException if the memory has been destroyed
	 */
	protected double valueAt(int index) {
		if (data == null) {
			throw new IllegalStateException("Memory has been destroyed");
		}

		return data.getDataCount() > 0 ? data.getData(index) : data.getData32(index);
	}

	/**
	 * Releases the reference to the backing message.
	 */
	protected void destroy() { this.data = null; }
}
