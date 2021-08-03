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

public class NativeMemoryProvider implements MemoryProvider<RAM> {
	private final Malloc malloc;
	private final NativeRead read;
	private final NativeWrite write;

	public NativeMemoryProvider() {
		this.malloc = new Malloc();
		this.read = new NativeRead();
		this.write = new NativeWrite();
	}

	@Override
	public RAM allocate(int size) {
		return new NativeMemory(this, malloc.apply(8 * size));
	}

	@Override
	public void deallocate(int size, RAM mem) {

	}

	@Override
	public void setMem(RAM mem, int offset, RAM source, int srcOffset, int length) {
		double value[] = new double[length];
		getMem(source, 0, value, 0, length);
		setMem(mem, offset, value, 0, length);
	}

	@Override
	public void setMem(RAM mem, int offset, double[] source, int srcOffset, int length) {
		write.apply((NativeMemory) mem, offset, source, srcOffset, length);
	}

	@Override
	public void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
		read.apply((NativeMemory) mem, sOffset, out, oOffset, length);
	}
}
