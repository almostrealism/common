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

/**
 * Manages the hardware execution environment, including memory providers and compute contexts.
 *
 * <p>A {@code DataContext} is the root owner of memory and compute resources for a given
 * device or execution target. It provides access to one or more {@link ComputeContext}
 * instances and coordinates memory allocation across providers of different sizes.</p>
 *
 * @param <MEM> the memory buffer type used by this context's compute contexts
 *
 * @see ComputeContext
 * @see MemoryProvider
 * @see Memory
 */
public interface DataContext<MEM> extends Named {
	/**
	 * Returns the floating-point precision used by this data context.
	 *
	 * @return the precision (FP16, FP32, or FP64)
	 */
	Precision getPrecision();

	/**
	 * Initializes this data context, preparing it for use.
	 */
	void init();

	/**
	 * Returns the primary compute context, which is the first in the list.
	 *
	 * @return the primary compute context
	 */
	default ComputeContext<MEM> getComputeContext() {
		return getComputeContexts().get(0);
	}

	/**
	 * Returns all compute contexts managed by this data context.
	 *
	 * @return the list of compute contexts
	 */
	List<ComputeContext<MEM>> getComputeContexts();

	/**
	 * Returns all memory providers available in this data context.
	 *
	 * @return the list of memory providers
	 */
	List<MemoryProvider<? extends Memory>> getMemoryProviders();

	/**
	 * Returns the memory provider best suited for allocating the given number of elements.
	 *
	 * @param size the number of elements to allocate
	 * @return the appropriate memory provider
	 */
	MemoryProvider<? extends Memory> getMemoryProvider(int size);

	/**
	 * Returns the memory provider preferred for kernel (GPU-resident) allocations.
	 *
	 * @return the kernel memory provider
	 */
	MemoryProvider<? extends Memory> getKernelMemoryProvider();

	/**
	 * Executes the given callable within device memory scope.
	 *
	 * <p>Allocations made during the callable's execution will be directed to device memory.
	 *
	 * @param <T> the return type of the callable
	 * @param exec the callable to execute
	 * @return the result of the callable
	 */
	<T> T deviceMemory(Callable<T> exec);

	/**
	 * Executes the given callable within a shared (named) memory scope.
	 *
	 * <p>The name function provides a context-specific name for the shared memory region.
	 * This operation is optional; the default implementation throws {@link UnsupportedOperationException}.
	 *
	 * @param <T> the return type of the callable
	 * @param name a function from size to the name of the shared memory region
	 * @param exec the callable to execute
	 * @return the result of the callable
	 * @throws UnsupportedOperationException if shared memory is not supported
	 */
	default <T> T sharedMemory(IntFunction<String> name, Callable<T> exec) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Executes the given callable within a compute context that satisfies the specified requirements.
	 *
	 * @param <T> the return type of the callable
	 * @param exec the callable to execute
	 * @param expectations the compute requirements that must be satisfied
	 * @return the result of the callable
	 */
	<T> T computeContext(Callable<T> exec, ComputeRequirement... expectations);

	/**
	 * Releases all resources held by this data context.
	 */
	void destroy();
}
