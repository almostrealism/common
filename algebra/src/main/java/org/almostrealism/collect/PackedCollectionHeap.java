/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.collect;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A collection of {@link PackedCollection}s stored in a single {@link io.almostrealism.code.Memory} instance.
 */
public class PackedCollectionHeap {
	private List<PackedCollection> entries;
	private PackedCollection data;
	private int end;

	public PackedCollectionHeap(int totalSize) {
		entries = new ArrayList<>();
		data = new PackedCollection(totalSize);
	}

	public synchronized PackedCollection allocate(int count) {
		if (end + count > data.getMemLength()) {
			throw new IllegalArgumentException("No room remaining in PackedCollectionHeap");
		}

		PackedCollection allocated = new PackedCollection(new TraversalPolicy(count), 0, data, end);
		end = end + count;
		entries.add(allocated);
		return allocated;
	}

	public PackedCollection get(int index) { return entries.get(index); }

	public Stream<PackedCollection> stream() { return entries.stream(); }

	public synchronized void destroy() {
		entries.clear();
		end = 0;
		data.destroy();
	}
}
