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
import org.almostrealism.hardware.HardwareException;
import org.almostrealism.hardware.RAM;

public class NativeMemoryProvider implements MemoryProvider<RAM> {
	private Malloc malloc;
	private Free free;
	private NativeRead read;
	private NativeWrite write;

	private final int numberSize;
	private final long memoryMax;
	private long memoryUsed;

	public NativeMemoryProvider(long memoryMax) {
		this.numberSize = 8;
		this.memoryMax = memoryMax;
	}

	@Override
	public NativeMemory allocate(int size) {
		if (malloc == null) malloc = new Malloc();

		if (memoryUsed + (long) numberSize * size > memoryMax) {
			throw new HardwareException("Memory max reached");
		} else {
			memoryUsed += (long) numberSize * size;
			return new NativeMemory(this, malloc.apply(numberSize * size));
		}
	}

	@Override
	public void deallocate(int size, RAM mem) {
		if (free == null) free = new Free();

		free.apply(mem.getNativePointer());
		memoryUsed -= (long) size * numberSize;
	}

	@Override
	public void setMem(RAM mem, int offset, RAM source, int srcOffset, int length) {
		double value[] = new double[length];
		getMem(source, srcOffset, value, 0, length);
		setMem(mem, offset, value, 0, length);
	}

	@Override
	public void setMem(RAM mem, int offset, double[] source, int srcOffset, int length) {
		if (write == null) write = new NativeWrite();
		write.apply((NativeMemory) mem, offset, source, srcOffset, length);
	}

	@Override
	public void getMem(RAM mem, int sOffset, double[] out, int oOffset, int length) {
		if (read == null) read = new NativeRead();
		read.apply((NativeMemory) mem, sOffset, out, oOffset, length);
	}

	@Override
	public void destroy() {
		// TODO  Deallocate everything
	}
}
