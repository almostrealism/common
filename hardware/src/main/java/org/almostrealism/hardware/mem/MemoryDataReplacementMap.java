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

public class MemoryDataReplacementMap implements Destroyable {
	public static OperationProfile profile;

	private Map<MemoryDataRef, Supplier<MemoryData>> replacements;

	public MemoryDataReplacementMap() {
		this.replacements = new HashMap<>();
	}

	public void addReplacement(MemoryData original, MemoryData replacement) {
		MemoryDataRef ref = new MemoryDataRef(original);

		if (replacements.containsKey(ref)) {
			throw new IllegalArgumentException();
		}

		replacements.put(ref, new MemoryDataSupplier(replacement));
	}

	public void addReplacement(MemoryData original, MemoryData replacement, int pos) {
		addReplacement(original, new Bytes(original.getMemLength(), replacement, pos));
	}

	public void addReplacement(MemoryData original, Producer<MemoryData> replacement, int pos) {
		MemoryDataRef ref = new MemoryDataRef(original);

		if (replacements.containsKey(ref)) {
			throw new IllegalArgumentException();
		}

		replacements.put(ref, new MemoryDataSource(replacement, pos, original.getMemLength()));
	}

	public OperationList getPreprocess() {
		OperationList prep = new OperationList("MemoryDataReplacementMap Preprocess");
		replacements.forEach((original, replacement) -> {
			prep.add(new MemoryDataCopy("MemoryDataReplacementMap Preprocess", new MemoryDataSupplier(original), replacement, 0, 0, original.getMemLength()));
		});
		return prep;
	}

	public OperationList getPostprocess() {
		OperationList post = new OperationList("MemoryDataReplacementMap Postprocess");
		replacements.forEach((original, replacement) -> {
			post.add(new MemoryDataCopy("MemoryDataReplacementMap Postprocess", replacement, new MemoryDataSupplier(original), 0, 0, original.getMemLength()));
		});
		return post;
	}

	public boolean isEmpty() { return replacements.isEmpty(); }

	private class MemoryDataSupplier implements Supplier<MemoryData> {
		private MemoryData md;

		public MemoryDataSupplier(MemoryData md) { this.md = md; }

		public MemoryDataSupplier(MemoryDataRef ref) {
			this(ref.getMemoryData());
		}

		@Override
		public MemoryData get() { return md; }
	}

	private class MemoryDataSource implements Supplier<MemoryData> {
		private Producer<MemoryData> md;
		private int pos, len;

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
