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

package org.almostrealism.hardware.jvm;

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;

public class JVMMemoryProvider implements MemoryProvider<Memory> {
	public JVMMemoryProvider() { }

	@Override
	public String getName() { return "JVM"; }

	@Override
	public int getNumberSize() { return 8; }

	@Override
	public Memory allocate(int size) {
		if (size <= 0)
			throw new IllegalArgumentException();
		return new JVMMemory(this, size);
	}

	@Override
	public void deallocate(int size, Memory mem) {
		((JVMMemory) mem).destroy();
	}

	@Override
	public void setMem(Memory mem, int offset, Memory source, int srcOffset, int length) {
		JVMMemory src = (JVMMemory) source;
		JVMMemory dest = (JVMMemory) mem;
		for (int i = 0; i < length; i++) {
			dest.data[offset + i] = src.data[srcOffset + i];
		}
	}

	@Override
	public void setMem(Memory mem, int offset, double[] source, int srcOffset, int length) {
		JVMMemory dest = (JVMMemory) mem;
		for (int i = 0; i < length; i++) {
			dest.data[offset + i] = source[srcOffset + i];
		}
	}

	@Override
	public void getMem(Memory mem, int sOffset, double[] out, int oOffset, int length) {
		JVMMemory src = (JVMMemory) mem;
		for (int i = 0; i < length; i++) {
			if ((sOffset + i) >= src.data.length) {
				throw new ArrayIndexOutOfBoundsException();
			} else if ((oOffset + i) >= out.length) {
				throw new ArrayIndexOutOfBoundsException();
			}

			out[oOffset + i] = src.data[sOffset + i];
		}
	}

	@Override
	public void destroy() {
		// TODO  Destroy all JVMMemory
	}
}
