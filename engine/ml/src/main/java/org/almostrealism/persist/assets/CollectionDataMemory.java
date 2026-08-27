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
 * Read-only {@link Memory} backed by protobuf collection data.
 *
 * <p>Values are served from the encoding on each read — nothing is
 * materialized on the host and nothing reaches a device until the framework
 * migrates this memory to a device provider at first kernel use.</p>
 *
 * <p>Where the encoding lives is the difference between the two forms of this.
 * A {@link Collections.CollectionData} that has already been parsed is on the
 * Java heap and stays there; collection data still in a file is read from the
 * file. The second is the one worth having, and the reason the first exists is
 * that a message already in hand should not have to be written out to be read
 * this way.</p>
 *
 * @see CollectionDataMemoryProvider
 */
public abstract class CollectionDataMemory implements Memory {
	/** The provider that manages this memory. */
	private final CollectionDataMemoryProvider provider;

	/**
	 * Creates memory managed by the given provider.
	 *
	 * @param provider the managing provider
	 */
	protected CollectionDataMemory(CollectionDataMemoryProvider provider) {
		this.provider = provider;
	}

	/** Returns the provider that manages this memory. */
	@Override
	public MemoryProvider getProvider() { return provider; }

	/** Returns the number of values available. */
	public abstract int getLength();

	/**
	 * Reads the value at the given position, widening to {@code double}
	 * whichever precision the data was encoded at.
	 *
	 * @param index the value position
	 * @return the value at that position
	 * @throws IllegalStateException if this memory has been destroyed
	 */
	protected abstract double valueAt(int index);

	/** Releases whatever this memory was reading through. */
	protected abstract void destroy();
}
