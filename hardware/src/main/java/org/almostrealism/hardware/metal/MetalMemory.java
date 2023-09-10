/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware.metal;

import org.almostrealism.hardware.RAM;

public class MetalMemory extends RAM {
	private final MTLBuffer mem;
	private final long size;
	private final MetalMemoryProvider provider;

	protected MetalMemory(MetalMemoryProvider provider, MTLBuffer mem, long size) {
		this.provider = provider;
		this.mem = mem;
		this.size = size;
	}

	protected MTLBuffer getMem() { return mem; }

	public long getSize() {
		return size;
	}

	@Override
	public long getNativePointer() { return mem.getNativePointer(); }

	@Override
	public MetalMemoryProvider getProvider() { return provider; }
}