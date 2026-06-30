/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.code;

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Scope;

/**
 * {@link ComputeContext} represents an execution environment for computations in the
 * Almost Realism framework. It provides the bridge between high-level computation
 * descriptions ({@link Scope}s) and the underlying compute engine (CPU, GPU, etc.).
 *
 * <p>A {@link ComputeContext} is responsible for:
 * <ul>
 *   <li>Managing the associated {@link DataContext} for memory operations</li>
 *   <li>Providing language operations for code generation</li>
 *   <li>Compiling scopes into executable {@link InstructionSet}s</li>
 *   <li>Managing execution threading and scheduling</li>
 *   <li>Identifying the compute hardware type (CPU vs GPU)</li>
 * </ul>
 *
 * <p>Different implementations of this interface support various compute backends:
 * <ul>
 *   <li>CPU execution via native JNI</li>
 *   <li>GPU execution via OpenCL or Metal</li>
 *   <li>External process execution</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 * <pre>{@code
 * ComputeContext<?> context = ...; // Obtain from hardware provider
 * Scope<?> scope = computation.getScope(kernelContext);
 * InstructionSet instructions = context.deliver(scope);
 * // Use instructions to execute computation
 * }</pre>
 *
 * @param <MEM> the memory type used by this compute context (e.g., pointer type)
 *
 * @author Michael Murray
 * @see DataContext
 * @see InstructionSet
 * @see Scope
 */
public interface ComputeContext<MEM> {

	/**
	 * Returns the {@link DataContext} associated with this compute context.
	 * The data context manages memory allocation and data transfer operations.
	 *
	 * @return the data context for memory operations
	 */
	DataContext<MEM> getDataContext();

	/**
	 * Returns the {@link LanguageOperations} for code generation.
	 * Language operations provide primitives for generating code in the
	 * target language (e.g., C, OpenCL C, Metal).
	 *
	 * @return the language operations for this context
	 */
	LanguageOperations getLanguage();

	/**
	 * Delivers the specified {@link Scope} to the compute engine represented by this
	 * {@link ComputeContext}, compiling it into an executable {@link InstructionSet}.
	 *
	 * <p>The resulting {@link InstructionSet} can be used to obtain the actual compute
	 * functionality, with each entrypoint in the {@link Scope} represented as a unique
	 * {@link java.util.function.Consumer}.
	 *
	 * @param scope the scope containing the computation to compile
	 * @return an instruction set that can execute the computation
	 */
	InstructionSet deliver(Scope scope);

	/**
	 * Schedules a task to be executed later on the compute context's executor thread.
	 * This is useful for deferring operations that must run on a specific thread.
	 *
	 * @param runnable the task to execute
	 */
	void runLater(Runnable runnable);

	/**
	 * Checks if the current thread is the executor thread for this compute context.
	 * Some operations may only be valid when running on the executor thread.
	 *
	 * @return {@code true} if the current thread is the executor thread
	 */
	boolean isExecutorThread();

	/**
	 * Determines if this compute context targets CPU execution.
	 *
	 * @return {@code true} if this context executes on CPU, {@code false} for GPU or other accelerators
	 */
	boolean isCPU();

	/**
	 * Determines whether an assignment (copy) between the two given {@link Memory} regions should be
	 * performed as a direct memory copy (via {@link MemoryProvider#setMem}) rather than by running a
	 * compiled kernel program.
	 *
	 * <p>Returns {@code true} by default: most contexts have no reason to compile and dispatch a
	 * kernel for a plain copy, so a direct {@code setMem} is both simpler and faster. A context that
	 * benefits from expressing the copy as a kernel &mdash; for example to queue it onto a command
	 * buffer so it batches with the surrounding operations on memory the context owns &mdash; returns
	 * {@code false} for those regions, and the caller runs the compiled assignment instead. This lets
	 * a single tool ({@code Assignment}) be used everywhere while the context picks the mechanism that
	 * suits the actual memory at run time.</p>
	 *
	 * @param source      the memory being copied from
	 * @param destination the memory being copied into
	 * @return {@code true} to perform a direct {@code setMem} copy; {@code false} to run a kernel
	 */
	default boolean isDirectMemoryAssignment(Memory source, Memory destination) {
		return true;
	}

	/**
	 * Checks if profiling is enabled for this compute context.
	 * When profiling is enabled, execution metrics may be collected.
	 *
	 * @return {@code true} if profiling is enabled, {@code false} by default
	 */
	default boolean isProfiling() {
		return false;
	}

	/**
	 * Destroys this compute context and releases all associated resources.
	 * After calling this method, the context should not be used.
	 */
	void destroy();
}
