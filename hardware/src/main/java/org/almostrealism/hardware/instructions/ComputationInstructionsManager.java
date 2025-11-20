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

package org.almostrealism.hardware.instructions;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import io.almostrealism.scope.Scope;

import java.util.function.Supplier;

/**
 * Specialized {@link ScopeInstructionsManager} for multi-function {@link Scope} instances using {@link DefaultExecutionKey}.
 *
 * <p>{@link ComputationInstructionsManager} extends {@link ScopeInstructionsManager} to support scopes with
 * multiple callable functions, where each function is identified by name and argument count.</p>
 *
 * <h2>Use Case</h2>
 *
 * <p>This manager is designed for {@link Scope} instances that contain multiple functions,
 * allowing dynamic selection at runtime:</p>
 *
 * <pre>{@code
 * // Scope with multiple functions:
 * // - add(a, b)
 * // - mul(a, b)
 * // - matmul(a, b, c)
 *
 * ComputationInstructionsManager manager =
 *     new ComputationInstructionsManager(computeContext, () -> scope);
 *
 * // Select operations by name and argument count
 * Execution addOp = manager.getOperator(new DefaultExecutionKey("add", 2));
 * Execution mulOp = manager.getOperator(new DefaultExecutionKey("mul", 2));
 * Execution matmulOp = manager.getOperator(new DefaultExecutionKey("matmul", 3));
 * }</pre>
 *
 * <h2>Key Differences from ScopeInstructionsManager</h2>
 *
 * <ul>
 *   <li><strong>Key type:</strong> Fixed to {@link DefaultExecutionKey} (function name + arg count)</li>
 *   <li><strong>Lookup method:</strong> Overrides {@code getOperator()} to use {@code InstructionSet.get(name, count)}</li>
 *   <li><strong>No access listener:</strong> Constructor always passes {@code null} for access listener</li>
 * </ul>
 *
 * <h2>When to Use</h2>
 *
 * <p>Use {@link ComputationInstructionsManager} when:</p>
 * <ul>
 *   <li>Your {@link Scope} contains multiple functions</li>
 *   <li>You need to select operations dynamically by name and argument count</li>
 *   <li>You want to share a single compiled {@link InstructionSet} across multiple operations</li>
 * </ul>
 *
 * <p>For single-function scopes or signature-based caching, use {@link ScopeInstructionsManager}
 * with {@link ScopeSignatureExecutionKey} instead.</p>
 *
 * <h2>Current Status</h2>
 *
 * <p><strong>NOTE:</strong> This class may be unnecessary in practice, as multi-function scopes are
 * primarily used in OpenCL (which has a dedicated {@link InstructionSetManager}). Most operations
 * compile to single-function scopes.</p>
 *
 * @see ScopeInstructionsManager
 * @see DefaultExecutionKey
 * @see io.almostrealism.code.InstructionSet
 */
public class ComputationInstructionsManager extends ScopeInstructionsManager<DefaultExecutionKey> {

	public ComputationInstructionsManager(ComputeContext<?> computeContext,
										  Supplier<Scope<?>> scope) {
		super(computeContext, scope, null);
	}

	@Override
	public synchronized Execution getOperator(DefaultExecutionKey key) {
		return getInstructionSet().get(key.getFunctionName(), key.getArgsCount());
	}
}
