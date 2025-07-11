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

import java.lang.ref.ReferenceQueue;

public class NativeRef<T extends RAM> extends MemoryReference<T> {
	private long address;
	private long size;

	public NativeRef(T ref, ReferenceQueue<? super T> queue) {
		super(ref, queue);
		this.address = ref.getContainerPointer();
		this.size = ref.getSize();
		setAllocationStackTrace(ref.getAllocationStackTrace());
	}

	public long getAddress() { return address; }

	public long getSize() { return size; }

	@Override
	public boolean equals(Object obj) {
		return (obj instanceof NativeRef<?> other) &&
				other.address == address &&
				other.size == size;
	}

	@Override
	public int hashCode() {
		return (Long.hashCode(address) * 31) + Long.hashCode(size);
	}
}
