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

package org.almostrealism.hardware.mem;

import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.function.BiFunction;
import java.util.function.IntFunction;

public class MemoryBankProvider<T extends MemoryData> implements IntFunction<MemoryBank<T>>, ConsoleFeatures {
	private BiFunction<MemoryBank<T>, Integer, MemoryBank<T>> supplier;
	private MemoryBank<T> last;
	private int lastSize;

	public MemoryBankProvider(IntFunction<MemoryBank<T>> supplier) {
		this((v, i) -> supplier.apply(i));
	}

	public MemoryBankProvider(BiFunction<MemoryBank<T>, Integer, MemoryBank<T>> supplier) {
		this.supplier = supplier;
	}

	public MemoryBank<T> apply(int size) {
		if (lastSize == size && last != null && last.getMem() != null) {
			return last;
		}

		if (Hardware.enableVerbose)
			log("Creating a new MemoryBank with size " + size);

		last = supplier.apply(last, size);
		lastSize = size;
		return last;
	}

	public void destroy() {
		if (last != null) last.destroy();
		lastSize = 0;
	}

	@Override
	public Console console() { return Hardware.console; }
}
