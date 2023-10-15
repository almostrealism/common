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

package org.almostrealism.hardware;

import io.almostrealism.code.Execution;
import io.almostrealism.code.Memory;
import io.almostrealism.code.MemoryProvider;
import io.almostrealism.relation.Named;

import java.util.List;

public abstract class HardwareOperator implements Execution, KernelWork, Named {
	public static boolean enableLog;
	public static boolean enableVerboseLog;
	public static boolean enableDimensionMasks = true;
	public static boolean enableAtomicDimensionMasks = true;

	public abstract List<MemoryProvider<? extends Memory>> getSupportedMemory();

	protected abstract String getHardwareName();

	protected MemoryData[] prepareArguments(int argCount, Object[] args) {
		MemoryData data[] = new MemoryData[argCount];

		for (int i = 0; i < argCount; i++) {
			if (args[i] == null) {
				throw new NullPointerException("argument " + i + " to function " + getName());
			}

			if (!(args[i] instanceof MemoryData)) {
				throw new IllegalArgumentException("argument " + i + " (" +
						args[i].getClass().getSimpleName() + ") to function " +
						getName() + " is not a MemoryData");
			}

			data[i] = (MemoryData) args[i];
			reassignMemory(data[i]);
		}

		return data;
	}

	private void reassignMemory(MemoryData data) {
		List<MemoryProvider<? extends Memory>> supported = getSupportedMemory();
		if (supported.isEmpty())
			throw new RuntimeException("No memory providers are supported by " + getName());

		MemoryProvider<? extends Memory> provider = data.getMem().getProvider();

		if (supported.contains(provider)) {
			// Memory is supported by the operation,
			// and will not have to be moved
			return;
		}

		// Memory is not supported by the operation,
		// and the entire reservation that it is part
		// of will have to be reallocated
		MemoryData root = data.getRootDelegate();
		int size = root.getMemLength() * provider.getNumberSize();

		if (enableVerboseLog)
			System.out.println("Hardware[" + getHardwareName() + "]: Reallocating " + size + " bytes");

		Memory mem = supported.get(0).reallocate(root.getMem(), root.getOffset(), root.getMemLength());
		root.reassign(mem);
	}

	public static void verboseLog(Runnable r) {
		boolean log = enableVerboseLog;
		enableVerboseLog = true;
		r.run();
		enableVerboseLog = log;
	}

	public static void disableDimensionMasks(Runnable r) {
		boolean masks = enableDimensionMasks;
		enableDimensionMasks = false;
		r.run();
		enableDimensionMasks = masks;
	}
}
