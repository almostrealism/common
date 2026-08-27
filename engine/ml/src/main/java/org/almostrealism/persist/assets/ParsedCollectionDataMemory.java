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

import org.almostrealism.protobuf.Collections;

/**
 * Read-only memory backed by a {@link Collections.CollectionData} that has
 * already been parsed.
 *
 * <p>The values are on the Java heap, inside the message, and stay there for
 * as long as this holds it. What this saves is the copy — they are read where
 * they already are rather than materialized again — and the device allocation,
 * which never happens if no kernel asks. What it cannot save is the parse,
 * which happened before this existed. For that the data has to be read from
 * where it was written: see {@link MappedCollectionDataMemory}.</p>
 *
 * @see CollectionDataMemoryProvider
 */
public class ParsedCollectionDataMemory extends CollectionDataMemory {
	/** The message serving as the backing store; {@code null} once destroyed. */
	private Collections.CollectionData data;

	/** The number of values the message contains. */
	private final int length;

	/**
	 * Creates memory backed by the given message.
	 *
	 * @param provider the managing provider
	 * @param data     the message serving as the backing store
	 */
	protected ParsedCollectionDataMemory(CollectionDataMemoryProvider provider,
										 Collections.CollectionData data) {
		super(provider);
		this.data = data;
		this.length = data.getDataCount() > 0 ?
				data.getDataCount() : data.getData32Count();
	}

	@Override
	public int getLength() { return length; }

	@Override
	protected double valueAt(int index) {
		if (data == null) {
			throw new IllegalStateException("Memory has been destroyed");
		}

		return data.getDataCount() > 0 ? data.getData(index) : data.getData32(index);
	}

	/** Releases the reference to the backing message. */
	@Override
	protected void destroy() { this.data = null; }
}
