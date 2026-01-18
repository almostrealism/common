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

import org.almostrealism.hardware.MemoryData;

import java.util.Objects;

/**
 * Identity-based reference holder for {@link MemoryData} instances.
 *
 * <p>{@link MemoryDataRef} wraps a {@link MemoryData} instance and provides identity-based
 * equality semantics. Two {@code MemoryDataRef} instances are equal if and only if they
 * reference the same {@link MemoryData} object (using {@code ==}).</p>
 *
 * <h2>Identity vs Content Equality</h2>
 *
 * <pre>{@code
 * MemoryData data1 = new Bytes(100);
 * MemoryData data2 = new Bytes(100);
 *
 * // MemoryData may have content-based equality
 * data1.equals(data2)  // May be true (same content)
 *
 * // MemoryDataRef has identity-based equality
 * MemoryDataRef ref1 = new MemoryDataRef(data1);
 * MemoryDataRef ref2 = new MemoryDataRef(data1);  // Same instance
 * MemoryDataRef ref3 = new MemoryDataRef(data2);  // Different instance
 *
 * ref1.equals(ref2)  // true  (same MemoryData instance)
 * ref1.equals(ref3)  // false (different MemoryData instances)
 * }</pre>
 *
 * <h2>Use Case: Identity-Based Collections</h2>
 *
 * <p>Useful for collections that need to track specific {@link MemoryData} instances
 * rather than content:</p>
 * <pre>{@code
 * Set<MemoryDataRef> tracked = new HashSet<>();
 *
 * MemoryData temp1 = allocate(100);
 * MemoryData temp2 = allocate(100);
 *
 * tracked.add(new MemoryDataRef(temp1));
 * tracked.add(new MemoryDataRef(temp1));  // Duplicate (same instance)
 * tracked.add(new MemoryDataRef(temp2));  // Different instance
 *
 * tracked.size()  // 2 (temp1 and temp2)
 * }</pre>
 *
 * @see MemoryData
 */
public class MemoryDataRef {
	private MemoryData md;

	public MemoryDataRef(MemoryData md) {
		this.md = md;
	}

	public MemoryData getMemoryData() { return md; }

	public int getMemLength() {
		return md.getMemLength();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MemoryDataRef)) return false;
		MemoryDataRef that = (MemoryDataRef) o;
		return md == that.md;
	}

	@Override
	public int hashCode() {
		return Objects.hash(md);
	}
}
