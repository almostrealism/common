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
 * Default implementation of {@link ADSREnvelopeData} using a PackedCollection for storage.
 *
 * @see ADSREnvelopeData
 * @see ADSREnvelope
 */
public class DefaultADSREnvelopeData implements ADSREnvelopeData {
	private final PackedCollection storage;

	/**
	 * Creates a new ADSR envelope data storage with default parameters.
	 */
	public DefaultADSREnvelopeData() {
		this.storage = new PackedCollection(SIZE);
		reset();
	}

	/**
	 * Creates a new ADSR envelope data storage backed by an existing collection.
	 *
	 * @param delegate the backing collection
	 * @param offset the offset into the delegate collection
	 */
	public DefaultADSREnvelopeData(PackedCollection delegate, int offset) {
		this.storage = delegate.range(shape(SIZE), offset);
		reset();
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
	 * Creates a bank of ADSR envelope data for multiple voices.
	 */
	public static PackedCollection bank(int count) {
		return new PackedCollection(count * SIZE);
	}

	/**
	 * Creates an ADSREnvelopeData view into a bank at the specified index.
	 */
	public static DefaultADSREnvelopeData fromBank(PackedCollection bank, int index) {
		return new DefaultADSREnvelopeData(bank, index * SIZE);
	}
}
