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

public interface DataContext<MEM> extends Named {
	Precision getPrecision();

	void init();

	default ComputeContext<MEM> getComputeContext() {
		return getComputeContexts().get(0);
	}

	List<ComputeContext<MEM>> getComputeContexts();

	List<MemoryProvider<? extends Memory>> getMemoryProviders();

	MemoryProvider<? extends Memory> getMemoryProvider(int size);

	MemoryProvider<? extends Memory> getKernelMemoryProvider();

	<T> T deviceMemory(Callable<T> exec);

	default <T> T sharedMemory(IntFunction<String> name, Callable<T> exec) {
		throw new UnsupportedOperationException();
	}

	<T> T computeContext(Callable<T> exec, ComputeRequirement... expectations);

	void destroy();
}
