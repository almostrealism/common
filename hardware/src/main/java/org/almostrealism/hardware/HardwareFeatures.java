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
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.ProducerFeatures;
import org.almostrealism.hardware.computations.Loop;
import org.almostrealism.hardware.computations.Periodic;
import org.almostrealism.hardware.mem.Bytes;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * A feature interface providing convenient factory methods and utilities for hardware-accelerated
 * computation operations.
 *
 * <p>{@link HardwareFeatures} serves as the primary entry point for creating and composing
 * hardware-accelerated operations in the Almost Realism framework. It extends multiple feature
 * interfaces to provide a unified API for:
 * <ul>
 *   <li>Memory operations ({@link MemoryDataFeatures})</li>
 *   <li>Logging and debugging ({@link ConsoleFeatures})</li>
 *   <li>Hardware-specific optimizations and delegation</li>
 * </ul>
 *
 * <h2>Feature Interface Pattern</h2>
 *
 * <p>{@link HardwareFeatures} follows the "feature interface" pattern used throughout Almost
 * Realism. Classes implement this interface to gain access to all its methods via mixins:</p>
 * <pre>{@code
 * public class MyProcessor implements HardwareFeatures {
 *     public void processData() {
 *         // All HardwareFeatures methods available directly
 *         Producer<PackedCollection> data = cp(myCollection);
 *         Producer<PackedCollection> result = multiply(data, c(2.0));
 *         result.get().evaluate();
 *     }
 * }
 * }</pre>
 *
 * <h2>Core Capabilities</h2>
 *
 * <h3>Instruction Caching and Reuse</h3>
 * <p>The {@link #instruct(String, Function, Producer[])} method enables caching of compiled
 * operations. When the same operation is invoked multiple times, the framework can reuse
 * previously compiled kernels, significantly reducing overhead:</p>
 * <pre>{@code
 * // First call: compiles kernel and caches it under "scale_operation"
 * Producer<T> result1 = instruct("scale_operation",
 *     args -> multiply(args[0], c(2.0)),
 *     inputData);
 *
 * // Subsequent calls: reuses cached kernel (much faster)
 * Producer<T> result2 = instruct("scale_operation",
 *     args -> multiply(args[0], c(2.0)),
 *     otherData);
 * }</pre>
 *
 * <p><strong>Key Benefits:</strong>
 * <ul>
 *   <li>Eliminates repeated kernel compilation overhead</li>
 *   <li>Consistent performance for repeated operations</li>
 *   <li>Automatic optimization based on instruction history</li>
 * </ul>
 *
 * <h3>Producer Delegation</h3>
 * <p>The {@link #delegate(Producer, Producer)} method creates delegated producers that can
 * be substituted at execution time. This enables optimization techniques like:
 * <ul>
 *   <li><strong>Constant Propagation:</strong> Replacing computed values with constants</li>
 *   <li><strong>Common Subexpression Elimination:</strong> Reusing computed intermediate results</li>
 *   <li><strong>Memory Layout Optimization:</strong> Adapting to preferred hardware formats</li>
 * </ul>
 *
 * <h3>Hardware-Accelerated Looping</h3>
 * <p>The {@link #loop(Computation, int)} method (aliased as {@link #lp(Computation, int)})
 * creates efficient iteration constructs that execute on hardware accelerators:</p>
 * <pre>{@code
 * // Execute computation 1000 times on GPU
 * Computation<Void> step = ...;
 * Supplier<Runnable> loop = loop(step, 1000);
 * loop.get().run();  // Entire loop executes as single kernel dispatch
 * }</pre>
 *
 * <p>The implementation automatically selects between:
 * <ul>
 *   <li><strong>{@link Loop}:</strong> For true {@link Computation}s, executes as hardware kernel</li>
 *   <li><strong>Java Loop:</strong> For {@link OperationList}s that cannot be kernelized,
 *       falls back to CPU iteration</li>
 * </ul>
 *
 * <h2>Integration with Other Features</h2>
 *
 * <h3>ProducerFeatures</h3>
 * <p>Provides methods for creating and manipulating producers:</p>
 * <ul>
 *   <li>{@code c(double)} - Create constant producers</li>
 *   <li>{@code v(shape, index)} - Create pass-through producers (dynamic inputs)</li>
 *   <li>{@code p(MemoryData)} - Wrap memory data as producers</li>
 *   <li>{@code multiply}, {@code add}, {@code subtract} - Arithmetic operations</li>
 * </ul>
 *
 * <h3>MemoryDataFeatures</h3>
 * <p>Provides memory-related operations:</p>
 * <ul>
 *   <li>{@code a(memLength, result, value)} - Assignment computations</li>
 *   <li>{@code copy(source, target, length)} - Memory copy operations</li>
 * </ul>
 *
 * <h3>ConsoleFeatures</h3>
 * <p>Provides logging utilities:</p>
 * <ul>
 *   <li>{@code log(String)} - Log messages</li>
 *   <li>{@code warn(String)} - Warning messages</li>
 *   <li>{@code verboseLog(Runnable)} - Conditional verbose logging</li>
 * </ul>
 *
 * <h2>Output Monitoring</h2>
 *
 * <p>When the {@code AR_HARDWARE_OUTPUT_MONITORING} environment variable is enabled,
 * the framework tracks and logs output values for debugging:</p>
 * <pre>
 * export AR_HARDWARE_OUTPUT_MONITORING=true
 * </pre>
 *
 * <p>This can help diagnose issues with hardware computations producing unexpected results.</p>
 *
 * <h2>Singleton Access</h2>
 *
 * <p>For cases where interface implementation is not desired, use {@link #getInstance()}:</p>
 * <pre>{@code
 * HardwareFeatures hw = HardwareFeatures.getInstance();
 * Producer<?> result = hw.multiply(hw.c(2.0), hw.c(3.0));
 * }</pre>
 *
 * <p>However, the mixin pattern (implementing the interface) is generally preferred for cleaner code.</p>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Building Computation Graphs</h3>
 * <pre>{@code
 * public class DataProcessor implements HardwareFeatures {
 *     public Producer<PackedCollection> process(Producer<PackedCollection> input) {
 *         // Chain operations using feature methods
 *         Producer<?> scaled = multiply(input, c(2.0));
 *         Producer<?> offset = add(scaled, c(10.0));
 *         Producer<?> clamped = max(offset, c(0.0));
 *         return clamped;
 *     }
 * }
 * }</pre>
 *
 * <h3>Cached Instructions for Repeated Operations</h3>
 * <pre>{@code
 * public class FilterBank implements HardwareFeatures {
 *     private static final String FILTER_KEY = "gaussian_blur_3x3";
 *
 *     public Producer<PackedCollection> blur(Producer<PackedCollection> image) {
 *         return instruct(FILTER_KEY,
 *             args -> applyConvolution(args[0], getGaussianKernel()),
 *             image);
 *     }
 * }
 * }</pre>
 *
 * <h3>Iterative Algorithms</h3>
 * <pre>{@code
 * public class GradientDescent implements HardwareFeatures {
 *     public void optimize(int iterations) {
 *         Computation<Void> step = createGradientStep();
 *
 *         // Execute gradient descent steps on GPU
 *         Supplier<Runnable> training = loop(step, iterations);
 *         training.get().run();
 *     }
 * }
 * }</pre>
 *
 * <h2>Performance Considerations</h2>
 *
 * <ul>
 *   <li><strong>Instruction Caching:</strong> Use {@link #instruct} for operations that repeat
 *       with different data - saves kernel compilation time</li>
 *   <li><strong>Loop Optimization:</strong> {@link #loop} is much more efficient than Java
 *       loops when the computation can be kernelized</li>
 *   <li><strong>Delegation Overhead:</strong> Delegated producers add minimal runtime overhead
 *       but enable powerful optimizations</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 *
 * <p>{@link HardwareFeatures} methods are generally thread-safe for creating operations,
 * but the underlying {@link Hardware} context and computations themselves may have
 * thread-specific state. Consult individual operation documentation for thread safety details.</p>
 *
 * @see ProducerFeatures
 * @see MemoryDataFeatures
 * @see ConsoleFeatures
 * @see Hardware
 * @see DefaultComputer
 */
public interface HardwareFeatures extends MemoryDataFeatures, ConsoleFeatures {
	boolean outputMonitoring = SystemUtils.isEnabled("AR_HARDWARE_OUTPUT_MONITORING").orElse(false);

	default Supplier<Runnable> loop(Computation<Void> c, int iterations) {
		if (c instanceof OperationList && !((OperationList) c).isComputation()) {
			return () -> {
				Runnable r = ((OperationList) c).get();
				return () -> IntStream.range(0, iterations).forEach(i -> r.run());
			};
		} else {
			return new Loop(c, iterations);
		}
	}

	default Supplier<Runnable> lp(Computation<Void> c, int iterations) { return loop(c, iterations); }

	/**
	 * Creates a periodic operation that executes the given computation once
	 * every {@code period} invocations.
	 *
	 * <p>If the computation is a compilable {@link Computation}, a
	 * {@link Periodic} is created that generates counter-based conditional
	 * execution in compiled code. Otherwise, a Java-based fallback is used
	 * with a {@link PackedCollection} counter.</p>
	 *
	 * @param c      the computation to execute periodically
	 * @param period the number of invocations between executions
	 * @return a supplier that produces the periodic runnable
	 *
	 * @see Periodic
	 */
	default Supplier<Runnable> periodic(Computation<Void> c, int period) {
		if (c instanceof OperationList && !((OperationList) c).isComputation()) {
			MemoryData counter = new Bytes(1);
			return () -> {
				Runnable r = ((OperationList) c).get();
				return () -> {
					double count = counter.toDouble(0) + 1;
					if (count >= period) {
						r.run();
						count = 0;
					}
					counter.setMem(0, count);
				};
			};
		} else {
			return new Periodic(c, period);
		}
	}

	static HardwareFeatures getInstance() {
		return new HardwareFeatures() { };
	}
}
