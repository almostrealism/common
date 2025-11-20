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

import org.almostrealism.io.Describable;

import java.util.Arrays;
import java.util.Objects;

/**
 * {@link ExecutionKey} implementation that identifies operations by their position in a {@link io.almostrealism.compute.Process} tree.
 *
 * <p>{@link ProcessTreePositionKey} represents a path through a hierarchical computation graph,
 * where each integer in the position array identifies the child index at that level of the tree.</p>
 *
 * <h2>Tree Navigation</h2>
 *
 * <p>Positions are represented as integer arrays, where each element is a child index:</p>
 *
 * <pre>
 * []        - Root operation
 * [0]       - First child of root
 * [0, 1]    - Second child of first child
 * [1, 0, 2] - Third child of first child of second child of root
 * </pre>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Build computation graph
 * Process<?, Matrix> a = constant(matrixA);
 * Process<?, Matrix> b = constant(matrixB);
 * Process<?, Matrix> c = constant(matrixC);
 * Process<?, Matrix> ab = matmul(a, b);     // Position [0]
 * Process<?, Matrix> result = add(ab, c);   // Position [] (root)
 *
 * // Create keys for each operation
 * ProcessTreePositionKey rootKey = new ProcessTreePositionKey();      // []
 * ProcessTreePositionKey matmulKey = new ProcessTreePositionKey(0);   // [0]
 *
 * // Retrieve operations
 * InstructionSetManager<ProcessTreePositionKey> manager = ...;
 * Execution addOp = manager.getOperator(rootKey);
 * Execution matmulOp = manager.getOperator(matmulKey);
 * }</pre>
 *
 * <h2>Tree Traversal</h2>
 *
 * <p>{@link ProcessTreePositionKey#append(int)} creates child keys for depth-first traversal:</p>
 *
 * <pre>{@code
 * ProcessTreePositionKey root = new ProcessTreePositionKey();
 * ProcessTreePositionKey child0 = root.append(0);  // [0]
 * ProcessTreePositionKey child1 = root.append(1);  // [1]
 * ProcessTreePositionKey grandchild = child0.append(2);  // [0, 2]
 * }</pre>
 *
 * <h2>Use with ProcessTreeInstructionsManager</h2>
 *
 * <p>This key type is primarily used with {@link ProcessTreeInstructionsManager} (now deprecated)
 * for traversing and compiling Process trees:</p>
 *
 * <pre>{@code
 * ProcessTreeInstructionsManager manager = new ProcessTreeInstructionsManager();
 *
 * // Extract all operations from process tree
 * manager.traverseAll(argumentList, (key, operation) -> {
 *     System.out.println("Position: " + key.describe());
 *     // Compile and cache operation at this position
 * });
 * }</pre>
 *
 * <h2>Equality and Hashing</h2>
 *
 * <p>Two {@link ProcessTreePositionKey} instances are equal if their position arrays are
 * element-wise equal:</p>
 *
 * <pre>{@code
 * ProcessTreePositionKey key1 = new ProcessTreePositionKey(0, 1, 2);
 * ProcessTreePositionKey key2 = new ProcessTreePositionKey(0, 1, 2);
 * ProcessTreePositionKey key3 = new ProcessTreePositionKey(0, 1);
 *
 * key1.equals(key2);  // true  - same position
 * key1.equals(key3);  // false - different depth
 * }</pre>
 *
 * @deprecated This key type is associated with the deprecated {@link ProcessTreeInstructionsManager}.
 *             Modern code should use {@link ScopeSignatureExecutionKey} instead.
 * @see ExecutionKey
 * @see ProcessTreeInstructionsManager
 * @see io.almostrealism.compute.Process
 */
public class ProcessTreePositionKey implements ExecutionKey, Describable {
	private final int[] position;

	public ProcessTreePositionKey(int... position) {
		this.position = position;
	}

	public int[] getPosition() {
		return position;
	}

	/**
	 * Creates a new key representing a child at the given index.
	 *
	 * @param i The child index to append to this position
	 * @return A new key with the child index appended
	 */
	public ProcessTreePositionKey append(int i) {
		int[] newPosition = Arrays.copyOf(position, position.length + 1);
		newPosition[position.length] = i;
		return new ProcessTreePositionKey(newPosition);
	}

	@Override
	public String describe() {
		return Arrays.toString(position);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof ProcessTreePositionKey that)) return false;
		return Objects.deepEquals(position, that.position);
	}

	@Override
	public int hashCode() {
		return Arrays.hashCode(position);
	}
}
