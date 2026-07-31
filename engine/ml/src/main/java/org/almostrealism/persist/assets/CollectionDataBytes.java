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

import org.almostrealism.hardware.mem.MemoryDataAdapter;
import org.almostrealism.protobuf.Collections;

/**
 * Root {@link org.almostrealism.hardware.MemoryData} whose backing store is a
 * protobuf {@link Collections.CollectionData} message rather than device or
 * heap memory. Collections delegate to an instance of this type to defer any
 * device allocation until the framework migrates the root at first kernel use.
 *
 * @see CollectionDataMemoryProvider
 * @see CollectionEncoder#decodeDeferred(Collections.CollectionData)
 */
public class CollectionDataBytes extends MemoryDataAdapter {
	/** The number of elements the backing message provides. */
	private final int memLength;

	/**
	 * Creates root memory data backed by the given protobuf message.
	 *
	 * @param data the message serving as the backing store
	 */
	public CollectionDataBytes(Collections.CollectionData data) {
		CollectionDataMemory mem = (CollectionDataMemory)
				CollectionDataMemoryProvider.getInstance().allocate(data);
		this.memLength = mem.getLength();
		init(mem);
	}

	/**
	 * Returns the number of elements the backing message provides.
	 */
	@Override
	public int getMemLength() { return memLength; }
}
