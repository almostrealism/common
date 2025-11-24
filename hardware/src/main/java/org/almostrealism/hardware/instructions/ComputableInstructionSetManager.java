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

/**
 * Extended {@link InstructionSetManager} that tracks output argument location for each operation.
 *
 * <p>{@link ComputableInstructionSetManager} adds metadata tracking for operations that write results
 * to specific output arguments. This is critical for:</p>
 * <ul>
 *   <li><strong>Argument routing:</strong> Knowing which argument receives the output</li>
 *   <li><strong>Memory management:</strong> Preallocating output buffers</li>
 *   <li><strong>Process trees:</strong> Connecting outputs to downstream inputs</li>
 * </ul>
 *
 * <h2>Output Argument Index</h2>
 *
 * <p>The output argument index identifies which parameter in the operation's argument list
 * receives the computed result:</p>
 *
 * <pre>{@code
 * // Operation signature: add(input1, input2, output)
 * // Output argument index = 2 (zero-based)
 *
 * ComputableInstructionSetManager<K> manager = ...;
 * int outputIndex = manager.getOutputArgumentIndex(key);  // Returns 2
 * }</pre>
 *
 * <h2>Output Offset</h2>
 *
 * <p>The output offset specifies the starting position within the output argument's memory region
 * where results are written. This enables multiple operations to write to different sections
 * of the same buffer:</p>
 *
 * <pre>{@code
 * // Two operations writing to different offsets in the same output buffer:
 * operation1.getOutputOffset(key1);  // Returns 0
 * operation2.getOutputOffset(key2);  // Returns 1024
 * }</pre>
 *
 * <h2>Usage in Process Trees</h2>
 *
 * <p>{@link ComputableInstructionSetManager} is essential for wiring together {@link io.almostrealism.compute.Process}
 * trees, where outputs from one operation feed into inputs of another:</p>
 *
 * <pre>{@code
 * // Build computation graph
 * Process<?, Matrix> matmul = matmul(a, b);
 * Process<?, Matrix> add = add(matmul, c);
 *
 * // Compile to operations
 * ComputableInstructionSetManager<K> manager = ...;
 *
 * // Get matmul output location
 * int matmulOutputIndex = manager.getOutputArgumentIndex(matmulKey);
 * int matmulOffset = manager.getOutputOffset(matmulKey);
 *
 * // Wire to add input
 * ArgumentList addArgs = ...;
 * addArgs.getArgument(0).setSource(matmulOutputIndex, matmulOffset);
 * }</pre>
 *
 * <h2>Implementation Example</h2>
 *
 * <pre>{@code
 * public class MyInstructionSetManager
 *         implements ComputableInstructionSetManager<MyKey> {
 *
 *     private Map<MyKey, Integer> outputIndices = new HashMap<>();
 *     private Map<MyKey, Integer> outputOffsets = new HashMap<>();
 *
 *     @Override
 *     public int getOutputArgumentIndex(MyKey key) {
 *         return outputIndices.getOrDefault(key, -1);
 *     }
 *
 *     @Override
 *     public int getOutputOffset(MyKey key) {
 *         return outputOffsets.getOrDefault(key, 0);
 *     }
 * }
 * }</pre>
 *
 * @param <K> The {@link ExecutionKey} type used for operation lookup
 * @see InstructionSetManager
 * @see ScopeInstructionsManager
 * @see org.almostrealism.hardware.arguments.ProcessArgumentMap
 */
public interface ComputableInstructionSetManager<K extends ExecutionKey> extends InstructionSetManager<K> {
	/**
	 * Returns the index of the output argument for the given operation.
	 *
	 * @param key The execution key identifying the operation
	 * @return The zero-based index of the output argument, or -1 if not set
	 */
	int getOutputArgumentIndex(K key);

	/**
	 * Returns the offset within the output argument where results are written.
	 *
	 * @param key The execution key identifying the operation
	 * @return The byte offset within the output argument's memory region
	 */
	int getOutputOffset(K key);
}
