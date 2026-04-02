/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tracks memory replacements and generates copy operations for data synchronization.
 *
 * <p>Used by {@link MemoryDataArgumentMap} to track which {@link MemoryData} instances have
 * been replaced with aggregated alternatives, generating the necessary copy operations to
 * transfer data before and after kernel execution.</p>
 *
 * @see MemoryDataArgumentMap
 * @see MemoryDataCopy
 */
public class MemoryDataReplacementMap implements Destroyable {
	/** Optional operation profile used for timing replacement copy operations. */
	public static OperationProfile profile;

	/** Map from original memory references to their replacement data suppliers. */
	private Map<MemoryDataRef, Supplier<MemoryData>> replacements;

	/**
	 * Creates an empty replacement map.
	 */
	public MemoryDataReplacementMap() {
		this.replacements = new HashMap<>();
	}

	/**
	 * Registers a memory replacement, mapping the original {@link MemoryData} to a producer-backed replacement.
	 *
	 * @param original    The original memory data to be replaced
	 * @param replacement Producer that supplies the replacement data
	 * @param pos         Offset within the replacement data
	 * @throws IllegalArgumentException if the original is already registered
	 */
	public void addReplacement(MemoryData original, Producer<MemoryData> replacement, int pos) {
		MemoryDataRef ref = new MemoryDataRef(original);

		if (replacements.containsKey(ref)) {
			throw new IllegalArgumentException();
		}

		replacements.put(ref, new MemoryDataSource(replacement, pos, original.getMemLength()));
	}

	/**
	 * Returns an {@link OperationList} that copies data from original memory into each replacement before kernel execution.
	 *
	 * @return Pre-execution copy operations
	 */
	public OperationList getPreprocess() {
		OperationList prep = new OperationList("MemoryDataReplacementMap Preprocess");
		replacements.forEach((original, replacement) -> {
			prep.add(new MemoryDataCopy("MemoryDataReplacementMap Preprocess", new MemoryDataSupplier(original), replacement, 0, 0, original.getMemLength()));
		});
		return prep;
	}

	/**
	 * Returns an {@link OperationList} that copies results from each replacement back to original memory after kernel execution.
	 *
	 * @return Post-execution copy operations
	 */
	public OperationList getPostprocess() {
		OperationList post = new OperationList("MemoryDataReplacementMap Postprocess");
		replacements.forEach((original, replacement) -> {
			post.add(new MemoryDataCopy("MemoryDataReplacementMap Postprocess", replacement, new MemoryDataSupplier(original), 0, 0, original.getMemLength()));
		});
		return post;
	}

	public boolean isEmpty() { return replacements.isEmpty(); }

	/** Simple supplier that wraps a fixed {@link MemoryData} instance. */
	private class MemoryDataSupplier implements Supplier<MemoryData> {
		/** The memory data instance to supply. */
		private MemoryData md;

		/**
		 * Creates a supplier wrapping the given memory data.
		 *
		 * @param md Memory data to wrap
		 */
		public MemoryDataSupplier(MemoryData md) { this.md = md; }

		/**
		 * Creates a supplier wrapping the memory data from the given reference.
		 *
		 * @param ref Memory data reference to unwrap
		 */
		public MemoryDataSupplier(MemoryDataRef ref) {
			this(ref.getMemoryData());
		}

		@Override
		public MemoryData get() { return md; }
	}

	/** Supplier that evaluates a {@link Producer} and slices the result at a given offset. */
	private class MemoryDataSource implements Supplier<MemoryData> {
		/** Producer that yields the aggregate memory block. */
		private Producer<MemoryData> md;
		/** Offset within the aggregate block and total length of this slice. */
		private int pos, len;

		/**
		 * Creates a supplier for a slice of the producer's output.
		 *
		 * @param md  Producer yielding the aggregate memory block
		 * @param pos Offset into the aggregate block
		 * @param len Length of the slice
		 */
		public MemoryDataSource(Producer<MemoryData> md, int pos, int len) {
			this.md = md;
			this.pos = pos;
			this.len = len;
		}

		@Override
		public MemoryData get() {
			MemoryData mem = md.get().evaluate();
			return new Bytes(len, mem, pos);
		}
	}
}
