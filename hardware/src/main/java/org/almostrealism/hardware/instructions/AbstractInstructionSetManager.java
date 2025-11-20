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

package org.almostrealism.hardware.instructions;

import io.almostrealism.code.ComputeContext;

/**
 * Abstract base class for {@link InstructionSetManager} implementations that provides {@link ComputeContext} management.
 *
 * <p>{@link AbstractInstructionSetManager} holds a reference to the {@link ComputeContext} used for
 * compilation and execution. Subclasses use this context to:</p>
 * <ul>
 *   <li><strong>Compile scopes:</strong> Transform {@link io.almostrealism.scope.Scope} to native code</li>
 *   <li><strong>Deliver instructions:</strong> Generate backend-specific {@link io.almostrealism.code.InstructionSet}</li>
 *   <li><strong>Execute operations:</strong> Run compiled code on the target hardware</li>
 * </ul>
 *
 * <h2>ComputeContext Types</h2>
 *
 * <p>Different {@link ComputeContext} implementations target different execution backends:</p>
 * <ul>
 *   <li><strong>{@link org.almostrealism.hardware.jni.NativeComputeContext}:</strong> JNI-based CPU execution</li>
 *   <li><strong>{@link org.almostrealism.hardware.cl.CLComputeContext}:</strong> OpenCL GPU/CPU execution</li>
 *   <li><strong>{@link org.almostrealism.hardware.metal.MetalComputeContext}:</strong> Metal GPU execution (Apple)</li>
 * </ul>
 *
 * <h2>Usage Pattern</h2>
 *
 * <pre>{@code
 * public class MyInstructionSetManager
 *         extends AbstractInstructionSetManager<ScopeSignatureExecutionKey> {
 *
 *     public MyInstructionSetManager(ComputeContext<?> context) {
 *         super(context);
 *     }
 *
 *     @Override
 *     public Execution getOperator(ScopeSignatureExecutionKey key) {
 *         // Use getComputeContext() to compile and deliver instructions
 *         InstructionSet instructions = getComputeContext().deliver(scope);
 *         return instructions.get(functionName, argCount);
 *     }
 *
 *     @Override
 *     public void destroy() {
 *         // Release resources
 *     }
 * }
 * }</pre>
 *
 * <h2>Subclass Responsibilities</h2>
 *
 * <p>Concrete subclasses MUST implement:</p>
 * <ul>
 *   <li><strong>getOperator(K key):</strong> Retrieve or compile operations by key</li>
 *   <li><strong>destroy():</strong> Release all resources when destroyed</li>
 * </ul>
 *
 * @param <K> The {@link ExecutionKey} type used for operation lookup
 * @see InstructionSetManager
 * @see ScopeInstructionsManager
 * @see io.almostrealism.code.ComputeContext
 */
public abstract class AbstractInstructionSetManager<K extends ExecutionKey>
											implements InstructionSetManager<K> {
	private ComputeContext<?> computeContext;

	public AbstractInstructionSetManager(ComputeContext<?> computeContext) {
		this.computeContext = computeContext;
	}

	/**
	 * Returns the {@link ComputeContext} used for compilation and execution.
	 *
	 * @return The compute context, never null
	 */
	public ComputeContext<?> getComputeContext() { return computeContext; }
}
