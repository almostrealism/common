/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.relation.Countable;
import io.almostrealism.uml.Multiple;

/**
 * A collection-like interface for managing multiple {@link MemoryData} objects within
 * a single contiguous memory allocation.
 *
 * <p>{@link MemoryBank} provides efficient storage and access for arrays of structured
 * data (vectors, matrices, custom objects) by packing them into a single {@link io.almostrealism.code.Memory}
 * instance. This design minimizes memory allocation overhead and enables efficient batch
 * operations on hardware accelerators.</p>
 *
 * <h2>Core Concept</h2>
 *
 * <p>Instead of allocating separate {@link io.almostrealism.code.Memory} for each element:</p>
 * <pre>
 * BAD: Individual allocations
 * Vector v1 → Memory[100 doubles]
 * Vector v2 → Memory[100 doubles]
 * Vector v3 → Memory[100 doubles]
 * Total: 3 allocations, 3 GPU buffers
 * </pre>
 *
 * <p>{@link MemoryBank} packs elements contiguously:</p>
 * <pre>
 * GOOD: Single bank allocation
 * VectorBank → Memory[300 doubles] = [v1][v2][v3]
 *                      ↓     ↓     ↓
 *               offset 0   100   200
 * Total: 1 allocation, 1 GPU buffer
 * </pre>
 *
 * <h2>Benefits</h2>
 *
 * <ul>
 *   <li><strong>Memory Efficiency:</strong> Single allocation reduces overhead from multiple
 *       small allocations (especially important on GPU)</li>
 *   <li><strong>Cache Locality:</strong> Contiguous storage improves CPU cache performance</li>
 *   <li><strong>Batch Operations:</strong> Entire bank can be transferred to/from GPU in one
 *       operation</li>
 *   <li><strong>Zero-Copy Views:</strong> Elements are {@link MemoryData} views into the bank's
 *       memory - no copying required</li>
 * </ul>
 *
 * <h2>Type Safety</h2>
 *
 * <p>{@link MemoryBank} is parameterized by the element type {@code T extends MemoryData},
 * ensuring type-safe access:</p>
 * <pre>{@code
 * MemoryBank<Vector> vectors = Vector.bank(100);  // 100 vectors
 * Vector v = vectors.get(5);  // Type-safe: returns Vector
 * v.setX(10.0);  // Modifies bank's memory at offset 5
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Creating and Populating a Bank</h3>
 * <pre>{@code
 * // Create a bank of 1000 vectors
 * MemoryBank<Vector> vectors = Vector.bank(1000);
 *
 * // Populate with data
 * for (int i = 0; i < 1000; i++) {
 *     Vector v = vectors.get(i);
 *     v.setX(i * 1.0);
 *     v.setY(i * 2.0);
 *     v.setZ(i * 3.0);
 * }
 *
 * // Or use set() for bulk updates
 * Vector newVector = new Vector(1, 2, 3);
 * vectors.set(50, newVector);  // Copies data to bank at index 50
 * }</pre>
 *
 * <h3>Batch Processing with Hardware Acceleration</h3>
 * <pre>{@code
 * MemoryBank<Vector> input = Vector.bank(10000);
 * MemoryBank<Vector> output = Vector.bank(10000);
 *
 * // Entire bank transfers to GPU as single operation
 * Producer<PackedCollection<?>> inputProducer = cp(input);
 *
 * // Apply computation across all vectors
 * Producer<PackedCollection<?>> scaled = multiply(inputProducer, c(2.0));
 *
 * // Write results back to output bank
 * scaled.get().into(output.traverseEach()).evaluate();
 * }</pre>
 *
 * <h3>Memory Bank as Temporary Storage</h3>
 * <pre>{@code
 * // Reusable workspace for intermediate results
 * MemoryBank<Matrix> workspace = Matrix.bank(10, 4, 4);
 *
 * for (int iteration = 0; iteration < 100; iteration++) {
 *     // Compute intermediate matrices
 *     computeStep(input, workspace);
 *
 *     // Use workspace for next step
 *     processResults(workspace, output);
 *
 *     // No allocation/deallocation overhead
 * }
 * }</pre>
 *
 * <h2>Element Access</h2>
 *
 * <p>{@link MemoryBank} extends {@link Multiple}, providing indexed access:</p>
 * <ul>
 *   <li>{@link #get(int)} - Returns a {@link MemoryData} view at the specified index</li>
 *   <li>{@link #set(int, MemoryData)} - Copies data into the bank at the specified index</li>
 *   <li>{@link #getCount()} - Returns the number of elements in the bank</li>
 * </ul>
 *
 * <p><strong>Important:</strong> {@link #get(int)} returns a <em>view</em> into the bank's
 * memory, not a copy. Modifications to the returned object affect the bank's underlying memory.</p>
 *
 * <h2>Memory Layout</h2>
 *
 * <p>Elements are stored contiguously with no padding:</p>
 * <pre>
 * For MemoryBank&lt;T&gt; where each T has atomic length N:
 *
 * Bank Memory: [T0 element 0...N-1][T1 element 0...N-1][T2 element 0...N-1]...
 *                    ↓                     ↓                     ↓
 *              offset = 0            offset = N            offset = 2N
 *
 * get(0) → view [offset=0, length=N]
 * get(1) → view [offset=N, length=N]
 * get(2) → view [offset=2N, length=N]
 * </pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <h3>Caching Strategy</h3>
 * <p>Implementations may cache element views to avoid repeated object creation:
 * <ul>
 *   <li><strong>CacheLevel.ALL:</strong> All elements created upfront (high memory, fast access)</li>
 *   <li><strong>CacheLevel.ACCESSED:</strong> Elements created on-demand and cached (balanced)</li>
 *   <li><strong>CacheLevel.NONE:</strong> New view created each access (low memory, slower)</li>
 * </ul>
 *
 * <h3>Transfer Costs</h3>
 * <p>For GPU operations:
 * <ul>
 *   <li>Banks with 1000s of elements transfer efficiently as single GPU buffer</li>
 *   <li>Individual element allocation would require 1000s of separate transfers</li>
 *   <li>Prefer banks for batch operations; individual {@link MemoryData} for single items</li>
 * </ul>
 *
 * <h2>Integration with Hardware Acceleration</h2>
 *
 * <p>{@link MemoryBank} extends {@link MemoryData}, so it can be used anywhere
 * {@link MemoryData} is expected:
 * <ul>
 *   <li>As input to computations via {@link io.almostrealism.relation.Producer}s</li>
 *   <li>As output destination for accelerated operations</li>
 *   <li>For direct kernel argument passing</li>
 * </ul>
 *
 * <h2>Lifecycle Management</h2>
 *
 * <pre>{@code
 * MemoryBank<Vector> bank = Vector.bank(1000);
 * try {
 *     // Use bank
 * } finally {
 *     bank.destroy();  // Releases single underlying Memory
 * }
 * }</pre>
 *
 * <p><strong>Note:</strong> Destroying a {@link MemoryBank} invalidates all element views
 * obtained via {@link #get(int)}. Do not retain references to elements after destroying
 * the bank.</p>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link MemoryBank} is <strong>not thread-safe</strong>. Concurrent modifications
 * require external synchronization. However, read-only concurrent access to different
 * elements may be safe depending on the implementation.</p>
 *
 * <h2>Implementation Notes</h2>
 *
 * <p>The standard implementation is {@link org.almostrealism.hardware.mem.MemoryBankAdapter}
 * (deprecated). Modern code typically uses {@code PackedCollection} which implements
 * {@link MemoryBank} for multi-dimensional array storage.</p>
 *
 * <p>Custom implementations must ensure:
 * <ul>
 *   <li>Contiguous element layout (offset = index * atomicMemLength)</li>
 *   <li>Element views correctly delegate to bank's underlying {@link io.almostrealism.code.Memory}</li>
 *   <li>{@link #getMemLength()} returns total size (count * atomicMemLength)</li>
 * </ul>
 *
 * @param <T> The type of {@link MemoryData} stored in this bank
 *
 * @see MemoryData
 * @see org.almostrealism.hardware.mem.MemoryBankAdapter
 * @see io.almostrealism.uml.Multiple
 * @see Countable
 *
 * @author  Michael Murray
 */
public interface MemoryBank<T extends MemoryData> extends MemoryData, Multiple<T>, Countable {
	/**
	 * Copies the data from the specified {@link MemoryData} into the bank at the given index.
	 *
	 * <p>This method performs a <strong>data copy</strong>, not a reference assignment. The
	 * source {@link MemoryData} remains independent after this operation.</p>
	 *
	 * <p><strong>Behavior:</strong></p>
	 * <pre>{@code
	 * MemoryBank<Vector> bank = Vector.bank(10);
	 * Vector v = new Vector(1, 2, 3);
	 *
	 * bank.set(5, v);  // Copies v's data to bank at index 5
	 *
	 * v.setX(99);  // Does NOT affect bank.get(5)
	 * }</pre>
	 *
	 * <p><strong>Memory Transfer:</strong></p>
	 * <p>The method copies {@code value.getMemLength()} doubles from the source to:</p>
	 * <pre>
	 * Destination offset = bank.getOffset() + (index * atomicMemLength)
	 * </pre>
	 *
	 * <p><strong>Validation:</strong></p>
	 * <ul>
	 *   <li>Index must be in range [0, count)</li>
	 *   <li>Source length must match bank's atomic element length</li>
	 * </ul>
	 *
	 * @param index The index in the bank (0-based)
	 * @param value The {@link MemoryData} whose contents will be copied
	 * @throws IndexOutOfBoundsException if index is negative or >= count
	 * @throws IllegalArgumentException if value length doesn't match atomic length
	 */
	void set(int index, T value);
}
