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

package org.almostrealism.hardware.instructions;

import io.almostrealism.code.Execution;
import io.almostrealism.lifecycle.Destroyable;

/**
 * Core abstraction for managing and caching compiled hardware operations.
 *
 * <p>{@link InstructionSetManager} provides a type-safe cache for {@link Execution} instances,
 * indexed by {@link ExecutionKey}. It handles the compilation lifecycle:</p>
 *
 * <ol>
 *   <li><strong>Compilation:</strong> Transform {@link io.almostrealism.scope.Scope} to native code</li>
 *   <li><strong>Caching:</strong> Store compiled operations for reuse</li>
 *   <li><strong>Retrieval:</strong> Return cached operations by key</li>
 *   <li><strong>Cleanup:</strong> Release resources when destroyed</li>
 * </ol>
 *
 * <h2>Type Parameter</h2>
 *
 * <p>The generic type {@code K extends ExecutionKey} allows different caching strategies:</p>
 * <ul>
 *   <li><strong>{@link DefaultExecutionKey}:</strong> Cache by function name and argument count</li>
 *   <li><strong>{@link ScopeSignatureExecutionKey}:</strong> Cache by operation signature</li>
 *   <li><strong>{@link ProcessTreePositionKey}:</strong> Cache by position in Process tree</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * // Create manager with Scope supplier
 * InstructionSetManager<ScopeSignatureExecutionKey> manager =
 *     new ScopeInstructionsManager<>(computeContext, () -> compiledScope, null);
 *
 * // Retrieve (compiles if not cached)
 * Execution operation = manager.getOperator(key);
 *
 * // Execute operation
 * MemoryData input = ...;
 * MemoryData output = ...;
 * operation.run(input, output);
 *
 * // Cleanup when done
 * manager.destroy();
 * }</pre>
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 *   <li><strong>{@link ScopeInstructionsManager}:</strong> Standard implementation with InstructionSet caching</li>
 *   <li><strong>{@link ComputationInstructionsManager}:</strong> Specialized for single-function scopes</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>Implementations MUST be thread-safe for concurrent {@code getOperator()} calls.
 * {@link ScopeInstructionsManager} uses {@code synchronized} to ensure only one compilation occurs:</p>
 *
 * <pre>{@code
 * @Override
 * public synchronized Execution getOperator(K key) {
 *     if (operators == null || operators.isDestroyed()) {
 *         operators = getComputeContext().deliver(getScope());
 *     }
 *     return operators.get(scopeName, arguments.size());
 * }
 * }</pre>
 *
 * <h2>Resource Management</h2>
 *
 * <p>As a {@link Destroyable}, managers MUST release all resources in {@code destroy()}:</p>
 * <ul>
 *   <li>Native memory (OpenCL buffers, CUDA allocations)</li>
 *   <li>Compiled libraries (.so files, .dylib files)</li>
 *   <li>Kernel caches and traversal generators</li>
 * </ul>
 *
 * @param <K> The {@link ExecutionKey} type used for operation lookup
 * @see ExecutionKey
 * @see ScopeInstructionsManager
 * @see ComputableInstructionSetManager
 */
public interface InstructionSetManager<K extends ExecutionKey> extends Destroyable {
	/**
	 * Retrieves a compiled {@link Execution} for the given key.
	 *
	 * <p>If the operation has not been compiled yet, implementations MUST compile it before returning.
	 * Subsequent calls with the same key SHOULD return the cached instance.</p>
	 *
	 * @param key The execution key identifying the operation
	 * @return The compiled execution, never null
	 * @throws org.almostrealism.hardware.HardwareException if compilation fails
	 */
	Execution getOperator(K key);
}
