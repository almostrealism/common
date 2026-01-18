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

import java.util.Objects;

/**
 * {@link ExecutionKey} implementation that identifies operations by function name and argument count.
 *
 * <p>{@link DefaultExecutionKey} provides a simple caching strategy based on the compiled function's
 * signature. Two operations with the same name and argument count are considered identical.</p>
 *
 * <h2>Use Cases</h2>
 *
 * <ul>
 *   <li><strong>Multi-function scopes:</strong> When a {@link io.almostrealism.scope.Scope} contains multiple
 *       functions that can be called by name and argument count</li>
 *   <li><strong>Dynamic dispatch:</strong> Selecting operations at runtime based on argument count</li>
 *   <li><strong>Overloading:</strong> Supporting multiple versions of the same operation with different arities</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create keys for different operations
 * DefaultExecutionKey add2 = new DefaultExecutionKey("add", 2);   // add(a, b)
 * DefaultExecutionKey add3 = new DefaultExecutionKey("add", 3);   // add(a, b, c)
 * DefaultExecutionKey mul2 = new DefaultExecutionKey("mul", 2);   // mul(a, b)
 *
 * // Retrieve operations from manager
 * InstructionSetManager<DefaultExecutionKey> manager = ...;
 * Execution addOp = manager.getOperator(add2);
 * Execution mulOp = manager.getOperator(mul2);
 * }</pre>
 *
 * <h2>Equality Semantics</h2>
 *
 * <p>Two {@link DefaultExecutionKey} instances are equal if and only if they have the same
 * function name and argument count:</p>
 *
 * <pre>{@code
 * DefaultExecutionKey key1 = new DefaultExecutionKey("matmul", 3);
 * DefaultExecutionKey key2 = new DefaultExecutionKey("matmul", 3);
 * DefaultExecutionKey key3 = new DefaultExecutionKey("matmul", 2);
 *
 * key1.equals(key2);  // true  - same name and count
 * key1.equals(key3);  // false - different count
 * }</pre>
 *
 * <h2>Limitations</h2>
 *
 * <p>{@link DefaultExecutionKey} cannot distinguish between operations with different argument types
 * or implementations. For more precise caching, consider {@link ScopeSignatureExecutionKey}.</p>
 *
 * @see ExecutionKey
 * @see InstructionSetManager
 * @see ComputationInstructionsManager
 */
public class DefaultExecutionKey implements ExecutionKey {
	/** The name of the function this key identifies. */
	private String functionName;

	/** The number of arguments expected by the function. */
	private int argsCount;

	/**
	 * Creates a new execution key for a function with the specified name and argument count.
	 *
	 * @param functionName the name of the function to identify
	 * @param argsCount    the number of arguments expected by the function
	 */
	public DefaultExecutionKey(String functionName, int argsCount) {
		this.functionName = functionName;
		this.argsCount = argsCount;
	}

	/**
	 * Returns the function name.
	 *
	 * @return the function name
	 */
	public String getFunctionName() { return functionName; }

	/**
	 * Returns the argument count.
	 *
	 * @return the argument count
	 */
	public int getArgsCount() { return argsCount; }

	/**
	 * Compares this key to another object for equality.
	 * Two keys are equal if they have the same function name and argument count.
	 *
	 * @param o the object to compare
	 * @return true if the objects are equal, false otherwise
	 */
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof DefaultExecutionKey that)) return false;
		return argsCount == that.argsCount && Objects.equals(functionName, that.functionName);
	}

	/**
	 * Returns a hash code based on the function name and argument count.
	 *
	 * @return the hash code
	 */
	@Override
	public int hashCode() {
		return Objects.hash(functionName, argsCount);
	}
}
