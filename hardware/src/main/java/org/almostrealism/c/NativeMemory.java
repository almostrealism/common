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

package org.almostrealism.c;

import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.RAM;

public class NativeMemory extends RAM {
	private final MemoryProvider provider;
	private final long nativePointer;
	private final long size;

	public NativeMemory(MemoryProvider provider, long nativePointer, long size) {
		this.provider = provider;
		this.nativePointer = nativePointer;
		this.size = size;
	}

	@Override
	public MemoryProvider getProvider() { return provider; }

	@Override
	public long getContentPointer() {
		return nativePointer;
	}

	@Override
	public long getSize() { return size; }
}
