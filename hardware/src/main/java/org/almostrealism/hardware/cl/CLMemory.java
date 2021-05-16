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

import org.almostrealism.hardware.RAM;
import org.jocl.cl_mem;

public class CLMemory extends RAM {
	private final cl_mem mem;
	private final CLMemoryProvider provider;

	protected CLMemory(cl_mem mem, CLMemoryProvider provider) {
		this.mem = mem;
		this.provider = provider;
	}

	protected cl_mem getMem() { return mem; }

	@Override
	public long getNativePointer() { return mem.getNativePointer(); }

	@Override
	public CLMemoryProvider getProvider() { return provider; }
}
