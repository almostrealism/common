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

package org.almostrealism.hardware.cl;

import org.almostrealism.hardware.mem.RAM;
import org.jocl.cl_mem;

public class CLMemory extends RAM {
	private final cl_mem mem;
	private final long size;
	private final CLMemoryProvider provider;

	protected CLMemory(CLMemoryProvider provider, cl_mem mem, long size) {
		this.provider = provider;
		this.mem = mem;
		this.size = size;
	}

	protected cl_mem getMem() { return mem; }

	public long getSize() {
		return size;
	}

	@Override
	public long getContentPointer() { return mem.getNativePointer(); }

	@Override
	public CLMemoryProvider getProvider() { return provider; }
}
