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

package org.almostrealism.hardware;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.computations.Assignment;
import org.almostrealism.hardware.mem.MemoryDataCopy;

import java.util.function.Supplier;

/**
 * Feature interface providing convenience methods for {@link MemoryData} operations like assignment and copying.
 *
 * <p>{@link MemoryDataFeatures} offers high-level methods to create {@link Assignment} and {@link MemoryDataCopy}
 * operations without verbose constructor calls. These methods are mixed into classes that work with memory operations.</p>
 *
 * <h2>Assignment Operations</h2>
 *
 * <p>The {@link #a} method creates {@link Assignment} operations for updating memory in-place:</p>
 * <pre>{@code
 * public class Example implements MemoryDataFeatures {
 *     public void updateMemory() {
 *         Producer<PackedCollection<?>> destination = ...;
 *         Producer<PackedCollection<?>> newValue = ...;
 *
 *         // Create assignment: destination = newValue
 *         Assignment<PackedCollection<?>> assignment = a(1000, destination, newValue);
 *         assignment.get().run();  // Execute assignment
 *     }
 * }
 * }</pre>
 *
 * <h2>Copy Operations</h2>
 *
 * <p>The {@link #copy} methods create memory copy operations with two implementation strategies:</p>
 * <ul>
 *   <li><b>{@link MemoryDataCopy} (default):</b> Direct memory-to-memory copy via memcpy-style operations</li>
 *   <li><b>{@link Assignment} (when enableAssignmentCopy=true):</b> Copy via kernel-based assignment</li>
 * </ul>
 *
 * <h3>Direct Copy (Default)</h3>
 * <pre>{@code
 * public class Example implements MemoryDataFeatures {
 *     public void copyData() {
 *         Supplier<MemoryData> source = () -> sourceMemory;
 *         Supplier<MemoryData> target = () -> targetMemory;
 *
 *         // Create copy operation (uses MemoryDataCopy)
 *         Supplier<Runnable> copyOp = copy(source, target, 1000);
 *         copyOp.get().run();  // Execute copy
 *     }
 * }
 * }</pre>
 *
 * <h3>Assignment-Based Copy</h3>
 * <pre>{@code
 * // Enable assignment-based copying
 * MemoryDataFeatures.enableAssignmentCopy = true;
 *
 * // Now copy() creates Assignment instead of MemoryDataCopy
 * Supplier<Runnable> copyOp = copy(source, target, 1000);
 * // Uses Assignment (kernel-based) instead of direct memory copy
 * }</pre>
 *
 * <h2>Producer vs Supplier Overloads</h2>
 *
 * <p>Methods accept both {@link Producer} and {@link Supplier}:</p>
 * <pre>{@code
 * // Using Producers (preferred for computation graphs)
 * Producer<PackedCollection<?>> src = ...;
 * Producer<PackedCollection<?>> dst = ...;
 * Supplier<Runnable> copy1 = copy(src, dst, 1000);
 *
 * // Using Suppliers (for direct memory references)
 * Supplier<MemoryData> srcMem = () -> memory1;
 * Supplier<MemoryData> dstMem = () -> memory2;
 * Supplier<Runnable> copy2 = copy(srcMem, dstMem, 1000);
 * }</pre>
 *
 * <h2>Named Copy Operations</h2>
 *
 * <p>Copy operations can be named for debugging and profiling:</p>
 * <pre>{@code
 * Supplier<Runnable> copyOp = copy(
 *     "copyInputToGPU",
 *     source,
 *     target,
 *     1000
 * );
 * // Name appears in logs and profiling data
 * }</pre>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Batch Assignment</h3>
 * <pre>{@code
 * public class Batch implements MemoryDataFeatures {
 *     public void updateAll(List<Producer<PackedCollection<?>>> destinations,
 *                           Producer<PackedCollection<?>> value) {
 *         for (Producer<PackedCollection<?>> dest : destinations) {
 *             a(1000, dest, value).get().run();
 *         }
 *     }
 * }
 * }</pre>
 *
 * <h3>Pipeline with Copies</h3>
 * <pre>{@code
 * // Stage 1: Copy input to GPU
 * copy("cpuToGpu", cpuMemory, gpuMemory, size).get().run();
 *
 * // Stage 2: Process on GPU
 * processOnGPU(gpuMemory);
 *
 * // Stage 3: Copy result back to CPU
 * copy("gpuToCpu", gpuMemory, cpuMemory, size).get().run();
 * }</pre>
 *
 * @see Assignment
 * @see MemoryDataCopy
 */
public interface MemoryDataFeatures {
	/**
	 * Controls whether {@link #copy} methods use {@link Assignment} (true) or {@link MemoryDataCopy} (false).
	 *
	 * <p>Default: false (uses MemoryDataCopy for direct memory operations)</p>
	 */
	boolean enableAssignmentCopy = false;

	/**
	 * Creates an {@link Assignment} operation that assigns the value producer's output to the result producer.
	 *
	 * @param memLength The memory length in elements
	 * @param result The destination producer
	 * @param value The source value producer
	 * @param <T> The type of {@link MemoryData}
	 * @return An assignment operation that can be compiled and executed
	 */
	default <T extends MemoryData> Assignment<T> a(int memLength,
												   Producer<T> result,
												   Producer<T> value) {
		return new Assignment<>(memLength, result, value);
	}

	/**
	 * Creates a memory copy operation from source to target.
	 *
	 * <p>Uses {@link MemoryDataCopy} by default, or {@link Assignment} if {@link #enableAssignmentCopy} is true.</p>
	 *
	 * @param source Supplier providing the source memory
	 * @param target Supplier providing the target memory
	 * @param length Number of elements to copy
	 * @return A supplier that produces a runnable copy operation
	 */
	default Supplier<Runnable> copy(Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		return copy(null, source, target, length);
	}

	/**
	 * Creates a named memory copy operation from source to target.
	 *
	 * <p>Uses {@link MemoryDataCopy} by default, or {@link Assignment} if {@link #enableAssignmentCopy} is true.
	 * The name is used for debugging and profiling.</p>
	 *
	 * @param name Optional name for the copy operation (for debugging/profiling)
	 * @param source Supplier providing the source memory
	 * @param target Supplier providing the target memory
	 * @param length Number of elements to copy
	 * @return A supplier that produces a runnable copy operation
	 */
	default Supplier<Runnable> copy(String name, Supplier<MemoryData> source, Supplier<MemoryData> target, int length) {
		if (enableAssignmentCopy) {
			return new Assignment(length,
					() -> (Evaluable<MemoryData>) args -> target.get(),
					() -> (Evaluable<MemoryData>) args -> source.get());
		} else {
			return new MemoryDataCopy(name, source, target, length);
		}
	}

	/**
	 * Creates a memory copy operation from source producer to target producer.
	 *
	 * <p>Uses {@link MemoryDataCopy} by default, or {@link Assignment} if {@link #enableAssignmentCopy} is true.</p>
	 *
	 * @param source Producer that outputs the source memory
	 * @param target Producer that outputs the target memory
	 * @param length Number of elements to copy
	 * @return A supplier that produces a runnable copy operation
	 */
	default Supplier<Runnable> copy(Producer<? extends MemoryData> source,
									Producer<? extends MemoryData> target, int length) {
		return copy(null, source, target, length);
	}

	/**
	 * Creates a named memory copy operation from source producer to target producer.
	 *
	 * <p>Uses {@link MemoryDataCopy} by default, or {@link Assignment} if {@link #enableAssignmentCopy} is true.
	 * The name is used for debugging and profiling.</p>
	 *
	 * @param name Optional name for the copy operation (for debugging/profiling)
	 * @param source Producer that outputs the source memory
	 * @param target Producer that outputs the target memory
	 * @param length Number of elements to copy
	 * @return A supplier that produces a runnable copy operation
	 */
	default Supplier<Runnable> copy(String name, Producer<? extends MemoryData> source,
									Producer<? extends MemoryData> target, int length) {
		if (enableAssignmentCopy) {
			return new Assignment(length, target, source);
		} else {
			return new MemoryDataCopy(name, source.get()::evaluate, target.get()::evaluate, length);
		}
	}
}
