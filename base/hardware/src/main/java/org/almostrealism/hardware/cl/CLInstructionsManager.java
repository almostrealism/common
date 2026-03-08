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

package org.almostrealism.hardware.cl;

import io.almostrealism.code.ComputeContext;
import io.almostrealism.code.Execution;
import org.almostrealism.hardware.instructions.AbstractInstructionSetManager;
import org.almostrealism.hardware.instructions.DefaultExecutionKey;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link org.almostrealism.hardware.instructions.InstructionSetManager} for OpenCL backend.
 *
 * <p>Manages {@link CLOperator} instances indexed by {@link DefaultExecutionKey} (function name + arg count),
 * with thread-local caching for thread-safe execution.</p>
 *
 * <h2>Basic Usage</h2>
 *
 * <pre>{@code
 * CLInstructionsManager manager = new CLInstructionsManager(computeContext, MyClass.class);
 *
 * // Get operator for function
 * DefaultExecutionKey key = new DefaultExecutionKey("matmul", 3);
 * CLOperator op = (CLOperator) manager.getOperator(key);
 *
 * // Execute
 * op.accept(args);
 * }</pre>
 *
 * @see CLOperator
 * @see CLComputeContext
 * @see org.almostrealism.hardware.instructions.InstructionSetManager
 */
public class CLInstructionsManager extends AbstractInstructionSetManager<DefaultExecutionKey> {
	/** Global cache mapping function names to thread-local CLOperator instances. */
	private static final Map<String, ThreadLocal<CLOperator>> operators = new HashMap<>();

	/** The class containing the source operations managed by this instance. */
	private Class<?> sourceClass;

	/**
	 * Creates a new CLInstructionsManager for managing OpenCL operators.
	 *
	 * @param computeContext  the compute context for OpenCL execution
	 * @param sourceClass     the class containing the source operations
	 */
	public CLInstructionsManager(ComputeContext<?> computeContext, Class<?> sourceClass) {
		super(computeContext);
		this.sourceClass = sourceClass;
	}

	/**
	 * Returns the OpenCL compute context, cast to {@link CLComputeContext}.
	 *
	 * @return the CLComputeContext for this manager
	 */
	public CLComputeContext getComputeContext() {
		return (CLComputeContext) super.getComputeContext();
	}

	/**
	 * Returns the source class containing the operations managed by this instance.
	 *
	 * @return the source class
	 */
	public Class<?> getSourceClass() { return sourceClass; }

	/**
	 * Returns the OpenCL operator for the given execution key.
	 * Creates a new thread-local operator if one does not exist.
	 *
	 * @param key  the execution key identifying the function name and argument count
	 * @return the CLOperator for the specified key
	 */
	@Override
	public Execution getOperator(DefaultExecutionKey key) {
		// TODO  This needs to be by class in addition to function, as function names may collide
		synchronized (CLInstructionsManager.class) {
			if (operators.get(key.getFunctionName()) == null) {
				operators.put(key.getFunctionName(), new ThreadLocal<>());
			}

			if (operators.get(key.getFunctionName()).get() == null) {
				operators.get(key.getFunctionName()).set(getComputeContext()
						.getFunctions().getOperators(getSourceClass()).get(key.getFunctionName(), key.getArgsCount()));
			}
		}

		return operators.get(key.getFunctionName()).get();
	}
}
