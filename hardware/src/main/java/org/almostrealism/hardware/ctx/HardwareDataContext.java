/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.hardware.ctx;

import io.almostrealism.code.DataContext;
import io.almostrealism.code.MemoryProvider;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.RAM;
import org.almostrealism.hardware.mem.HardwareMemoryProvider;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.concurrent.Callable;
import java.util.function.IntFunction;

public abstract class HardwareDataContext implements DataContext<MemoryData>, ConsoleFeatures {
	private final String name;
	private final long maxReservation;

	private MemoryProvider<? extends RAM> sharedRam;

	protected static ThreadLocal<IntFunction<MemoryProvider<?>>> memoryProvider;

	static {
		memoryProvider = new ThreadLocal<>();
	}

	public HardwareDataContext(String name, long maxReservation) {
		this.name = name;
		this.maxReservation = maxReservation;
	}

	@Override
	public String getName() {
		return name;
	}

	public long getMaxReservation() {
		return maxReservation;
	}

	protected IntFunction<MemoryProvider<?>> getMemoryProviderSupply() {
		return memoryProvider.get();
	}

	protected MemoryProvider<RAM> getSharedMemoryProvider() {
		return (MemoryProvider<RAM>) Hardware.getLocalHardware().getNativeBufferMemoryProvider();
	}

	@Override
	public <T> T sharedMemory(IntFunction<String> name, Callable<T> exec) {
		if (sharedRam == null) {
			sharedRam = getSharedMemoryProvider();
		}

		IntFunction<MemoryProvider<?>> currentProvider = memoryProvider.get();
		IntFunction<MemoryProvider<?>> nextProvider = s -> sharedRam;

		try {
			memoryProvider.set(nextProvider);
			return ((HardwareMemoryProvider<?>) sharedRam).sharedMemory(name, exec);
		} catch (RuntimeException e) {
			throw e;
		} catch (Exception e) {
			throw new RuntimeException(e);
		} finally {
			memoryProvider.set(currentProvider);
		}
	}

	@Override
	public Console console() { return Hardware.console; }
}
