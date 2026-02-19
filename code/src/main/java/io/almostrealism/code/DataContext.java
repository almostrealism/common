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

package io.almostrealism.code;

import io.almostrealism.compute.ComputeRequirement;
import io.almostrealism.uml.Named;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.IntFunction;

/** The DataContext interface. */
public interface DataContext<MEM> extends Named {
	/** Performs the getPrecision operation. */
	Precision getPrecision();

	/** Performs the init operation. */
	void init();

	default ComputeContext<MEM> getComputeContext() {
		return getComputeContexts().get(0);
	}

	/** Performs the getComputeContexts operation. */
	List<ComputeContext<MEM>> getComputeContexts();

	/** Performs the getMemoryProviders operation. */
	List<MemoryProvider<? extends Memory>> getMemoryProviders();

	/** Performs the getMemoryProvider operation. */
	MemoryProvider<? extends Memory> getMemoryProvider(int size);

	/** Performs the getKernelMemoryProvider operation. */
	MemoryProvider<? extends Memory> getKernelMemoryProvider();

	/** Performs the deviceMemory operation. */
	<T> T deviceMemory(Callable<T> exec);

	/** Performs the sharedMemory operation. */
	default <T> T sharedMemory(IntFunction<String> name, Callable<T> exec) {
		throw new UnsupportedOperationException();
	}

	/** Performs the computeContext operation. */
	<T> T computeContext(Callable<T> exec, ComputeRequirement... expectations);

	/** Performs the destroy operation. */
	void destroy();
}
