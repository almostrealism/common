/*
 * Copyright 2021 Michael Murray
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

import java.util.concurrent.Callable;

public interface DataContext<MEM> {
	ComputeContext<MEM> getComputeContext();

	MemoryProvider<? extends Memory> getMemoryProvider(int size);

	MemoryProvider<? extends Memory> getKernelMemoryProvider();

	<T> T computeContext(Callable<T> exec, ComputeRequirement... expectations);

	void destroy();
}
