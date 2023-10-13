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

package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Countable;
import org.almostrealism.hardware.MemoryData;

public class Bytes extends MemoryDataAdapter implements Countable {
	private final int atomicLength;
	private final int memLength;

	public Bytes(int memLength) {
		this(memLength, memLength);
	}

	public Bytes(int memLength, int atomicLength) {
		if (atomicLength == 0) {
			throw new IllegalArgumentException();
		}

		if (memLength % atomicLength != 0) {
			throw new IllegalArgumentException("Memory length must be a multiple of atomic length");
		}

		this.atomicLength = atomicLength;
		this.memLength = memLength;
		init();
	}

	public Bytes(int memLength, MemoryData delegate, int delegateOffset) {
		this.atomicLength = memLength;
		this.memLength = memLength;
		setDelegate(delegate, delegateOffset);
	}

	@Override
	public int getCount() { return getMemLength() / getAtomicMemLength(); }

	@Override
	public int getAtomicMemLength() { return atomicLength; }

	@Override
	public int getMemLength() {
		return memLength;
	}
}
