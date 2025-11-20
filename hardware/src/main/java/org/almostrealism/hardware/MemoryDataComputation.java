/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.Computation;

/**
 * A {@link Computation} that produces {@link MemoryData} with a known, fixed memory length.
 *
 * <p>{@link MemoryDataComputation} extends the base {@link Computation} interface with a requirement
 * to declare the memory length of the output. This information is critical for:
 * <ul>
 *   <li><strong>Memory allocation:</strong> Pre-allocating output buffers of the correct size</li>
 *   <li><strong>Kernel compilation:</strong> Determining iteration counts and buffer sizes</li>
 *   <li><strong>Validation:</strong> Verifying that operations produce outputs matching expected sizes</li>
 * </ul>
 *
 * <h2>Core Concept: Static Memory Size Declaration</h2>
 *
 * <p>Unlike general {@link Computation}s where output size may vary, {@link MemoryDataComputation}
 * requires the memory length to be known at computation construction time:</p>
 *
 * <pre>{@code
 * // Computation that always produces 1000 doubles
 * MemoryDataComputation<PackedCollection<?>> fixedSize = new MemoryDataComputation<>() {
 *     @Override
 *     public int getMemLength() {
 *         return 1000;  // Always 1000 elements
 *     }
 *
 *     @Override
 *     public Evaluable<PackedCollection<?>> get() {
 *         return args -> {
 *             PackedCollection<?> result = new PackedCollection<>(1000);
 *             // ... compute result ...
 *             return result;
 *         };
 *     }
 * };
 * }</pre>
 *
 * <h2>Usage in Hardware Acceleration</h2>
 *
 * <p>The memory length is used during kernel compilation to allocate appropriate buffers:</p>
 * <pre>
 * Compilation:
 * getMemLength() = 1000 -> allocate output buffer: 1000 * sizeof(double)
 *                       -> kernel iteration count: 1000
 * </pre>
 *
 * <h3>Example with Vector Operations</h3>
 * <pre>{@code
 * public class VectorAddition implements MemoryDataComputation<Vector> {
 *     @Override
 *     public int getMemLength() {
 *         return 3;  // Vector has 3 components (x, y, z)
 *     }
 *
 *     @Override
 *     public Evaluable<Vector> get() {
 *         return args -> {
 *             Vector a = (Vector) args[0];
 *             Vector b = (Vector) args[1];
 *             return new Vector(a.getX() + b.getX(),
 *                              a.getY() + b.getY(),
 *                              a.getZ() + b.getZ());
 *         };
 *     }
 * }
 * }</pre>
 *
 * <h2>Relationship with MemoryData</h2>
 *
 * <p>The memory length should match the {@link MemoryData#getMemLength()} of the produced output:</p>
 * <pre>{@code
 * MemoryDataComputation<T> computation = ...;
 * T result = computation.get().evaluate(args);
 *
 * assert result.getMemLength() == computation.getMemLength();  // Should be true
 * }</pre>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Fixed-Size Array Operations</h3>
 * <pre>{@code
 * // Operation that always produces arrays of 256 elements
 * MemoryDataComputation<PackedCollection<?>> fftOp =
 *     new MemoryDataComputation<>() {
 *         @Override
 *         public int getMemLength() { return 256; }
 *
 *         @Override
 *         public Evaluable<PackedCollection<?>> get() {
 *             return args -> performFFT((PackedCollection<?>) args[0]);
 *         }
 *     };
 * }</pre>
 *
 * <h3>Structured Data Types</h3>
 * <pre>{@code
 * // Matrix operation (4x4 = 16 elements)
 * MemoryDataComputation<Matrix> matrixOp = new MemoryDataComputation<>() {
 *     @Override
 *     public int getMemLength() { return 16; }
 *
 *     @Override
 *     public Evaluable<Matrix> get() {
 *         return args -> computeMatrix((Matrix) args[0], (Matrix) args[1]);
 *     }
 * };
 * }</pre>
 *
 * <h2>Integration with AcceleratedOperation</h2>
 *
 * <p>Many accelerated operations implement this interface to declare their output size:</p>
 * <pre>{@code
 * public class ScalarMultiply extends AcceleratedComputationOperation<PackedCollection<?>>
 *         implements MemoryDataComputation<PackedCollection<?>> {
 *
 *     private final int length;
 *
 *     public ScalarMultiply(int length, Producer<PackedCollection<?>> input, double scalar) {
 *         super(input);
 *         this.length = length;
 *     }
 *
 *     @Override
 *     public int getMemLength() {
 *         return length;  // Output has same length as input
 *     }
 * }
 * }</pre>
 *
 * <h2>Memory Length vs Count</h2>
 *
 * <p>For {@link MemoryBank}s, distinguish between:
 * <ul>
 *   <li><strong>Memory Length:</strong> Total doubles in the entire bank (count * atomicMemLength)</li>
 *   <li><strong>Count:</strong> Number of elements in the bank</li>
 * </ul>
 *
 * <pre>{@code
 * // Bank of 100 vectors, each vector is 3 doubles
 * MemoryBank<Vector> bank = Vector.bank(100);
 * bank.getCount() == 100           // Number of vectors
 * bank.getMemLength() == 300       // Total doubles (100 * 3)
 * }</pre>
 *
 * <h2>Validation</h2>
 *
 * <p>Implementations should ensure consistency between declared and actual sizes. Mismatches
 * can lead to:
 * <ul>
 *   <li>Buffer overruns during kernel execution</li>
 *   <li>Incorrect memory allocations</li>
 *   <li>Data corruption</li>
 * </ul>
 *
 * @param <T> The type of {@link MemoryData} produced by this computation
 *
 * @see Computation
 * @see MemoryData
 * @see AcceleratedOperation
 */
public interface MemoryDataComputation<T extends MemoryData> extends Computation<T> {
	/**
	 * Returns the memory length (in doubles) of the {@link MemoryData} produced by this computation.
	 *
	 * <p>This value must be constant for the lifetime of the computation and should match
	 * {@code result.getMemLength()} for any result produced by {@code get().evaluate()}.</p>
	 *
	 * @return The number of double values in the output
	 */
	int getMemLength();
}
