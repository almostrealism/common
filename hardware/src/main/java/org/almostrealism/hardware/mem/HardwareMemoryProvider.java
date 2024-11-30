/*
 * Copyright 2024 Michael Murray
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

import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.Callable;
import java.util.function.IntFunction;

public abstract class HardwareMemoryProvider<T extends Memory> implements MemoryProvider<T>, ConsoleFeatures {
	protected static ThreadLocal<IntFunction<String>> memoryName;

	static {
		memoryName = new ThreadLocal<>();
	}

	protected IntFunction<String> getMemoryName() {
		return memoryName.get();
	}

	public <V> V sharedMemory(IntFunction<String> name, Callable<V> exec) {
		IntFunction<String> currentName = memoryName.get();
		IntFunction<String> nextName = name;

		try {
			memoryName.set(nextName);
			return exec.call();
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			memoryName.set(currentName);
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
