/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.audio.filter;

import org.almostrealism.collect.PackedCollection;

/**
 * Default implementation of {@link BiquadFilterData} using a PackedCollection for storage.
 *
 * @see BiquadFilterData
 * @see BiquadFilterCell
 */
public class DefaultBiquadFilterData implements BiquadFilterData {
	private final PackedCollection storage;

	public DefaultBiquadFilterData() {
		this.storage = new PackedCollection(SIZE);
		resetState();
	}

	public DefaultBiquadFilterData(PackedCollection delegate, int offset) {
		this.storage = delegate.range(shape(SIZE), offset);
		resetState();
	}

	@Override
	public PackedCollection get(int index) {
		return storage.range(shape(1), index);
	}

	/**
	 * Returns the underlying storage collection.
	 */
	public PackedCollection getStorage() {
		return storage;
	}

	/**
	 * Creates a bank of biquad filter data for multiple voices.
	 */
	public static PackedCollection bank(int count) {
		return new PackedCollection(count * SIZE);
	}

	/**
	 * Creates a BiquadFilterData view into a bank at the specified index.
	 */
	public static DefaultBiquadFilterData fromBank(PackedCollection bank, int index) {
		return new DefaultBiquadFilterData(bank, index * SIZE);
	}
}
