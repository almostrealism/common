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

package org.almostrealism.hardware;

import io.almostrealism.code.Computation;
import io.almostrealism.relation.Evaluable;

/**
 * Feature interface providing convenient methods for compiling {@link Computation} instances on hardware accelerators.
 *
 * <p>{@link ComputerFeatures} extends {@link HardwareFeatures} with high-level compilation methods that
 * automatically use the local {@link Hardware} instance and its {@link DefaultComputer}. This enables
 * classes to mix in compilation capabilities without directly referencing {@link Hardware}.</p>
 *
 * <h2>Usage Pattern</h2>
 *
 * <p>Classes implement this interface to gain access to compilation methods:</p>
 * <pre>{@code
 * public class MyProcessor implements ComputerFeatures {
 *     public void processData(PackedCollection input) {
 *         // Define computation
 *         Computation<PackedCollection> transform = c -> c.multiply(2.0);
 *
 *         // Compile and execute using inherited method
 *         Evaluable<PackedCollection> evaluable = compileProducer(transform);
 *         PackedCollection result = evaluable.evaluate(input);
 *     }
 *
 *     public void runProcess() {
 *         Computation<Void> process = c -> { return null; };
 *
 *         // Compile to Runnable
 *         Runnable runnable = compileRunnable(process);
 *         runnable.run();
 *     }
 * }
 * }</pre>
 *
 * <h2>Delegation to DefaultComputer</h2>
 *
 * <p>All methods delegate to Hardware.getLocalHardware().getComputer():</p>
 * <pre>{@code
 * // These are equivalent:
 * compileProducer(computation);
 * Hardware.getLocalHardware().getComputer().compileProducer(computation);
 * }</pre>
 *
 * <h2>Thread-Local Hardware</h2>
 *
 * <p>Since Hardware.getLocalHardware() is thread-local, compilations use the
 * hardware context configured for the current thread:</p>
 * <pre>{@code
 * // Thread 1: Uses default hardware
 * Evaluable eval1 = compileProducer(computation);
 *
 * // Thread 2: Could use different hardware if configured
 * Hardware.setLocalHardware(customHardware);
 * Evaluable eval2 = compileProducer(computation);  // Uses customHardware
 * }</pre>
 *
 * <h2>Integration with HardwareFeatures</h2>
 *
 * <p>Extends {@link HardwareFeatures}, inheriting all its convenience methods:</p>
 * <ul>
 *   <li>enableKernels() - Control kernel compilation</li>
 *   <li>enableDestinations() - Control destination tracking</li>
 *   <li>Access to common operations like {@code c()}, {@code v()}, etc.</li>
 * </ul>
 *
 * <h2>Common Patterns</h2>
 *
 * <h3>Compiling Producers</h3>
 * <pre>{@code
 * public class DataProcessor implements ComputerFeatures {
 *     public Evaluable<PackedCollection> createNormalizer() {
 *         return compileProducer(c -> c.divide(c.max()));
 *     }
 * }
 * }</pre>
 *
 * <h3>Compiling Runnables</h3>
 * <pre>{@code
 * public class BatchProcessor implements ComputerFeatures {
 *     public Runnable createBatchJob(List<PackedCollection> batches) {
 *         return compileRunnable(c -> batches.forEach(this::process));
 *     }
 * }
 * }</pre>
 *
 * @see HardwareFeatures
 * @see DefaultComputer
 * @see Hardware
 */
public interface ComputerFeatures extends HardwareFeatures {
	/**
	 * Compiles a {@link Computation} that returns void into a {@link Runnable}.
	 *
	 * <p>The compiled runnable executes the computation on the local hardware
	 * (GPU/accelerator if available, CPU otherwise).</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * Computation<Void> updateState = c -> {
	 *     state.add(delta);
	 *     return null;
	 * };
	 *
	 * Runnable update = compileRunnable(updateState);
	 * update.run();  // Executes on hardware
	 * }</pre>
	 *
	 * @param c The computation to compile
	 * @return A runnable that executes the compiled computation
	 */
	default Runnable compileRunnable(Computation<Void> c) {
		return Hardware.getLocalHardware().getComputer().compileRunnable(c);
	}

	/**
	 * Compiles a {@link Computation} that produces a result into an {@link Evaluable}.
	 *
	 * <p>The compiled evaluable can be invoked multiple times to produce results
	 * on the local hardware.</p>
	 *
	 * <p>Example:</p>
	 * <pre>{@code
	 * Computation<PackedCollection> multiply = c -> c.multiply(2.0);
	 *
	 * Evaluable<PackedCollection> doubler = compileProducer(multiply);
	 *
	 * PackedCollection input = PackedCollection.create(1000);
	 * PackedCollection result = doubler.evaluate(input);
	 * }</pre>
	 *
	 * @param c The computation to compile
	 * @param <T> The type of {@link MemoryData} produced
	 * @return An evaluable that produces results via the compiled computation
	 */
	default <T extends MemoryData> Evaluable<T> compileProducer(Computation<T> c) {
		return Hardware.getLocalHardware().getComputer().compileProducer(c);
	}
}
