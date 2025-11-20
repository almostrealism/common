/*
 * Copyright 2022 Michael Murray
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

import io.almostrealism.relation.Producer;
import io.almostrealism.collect.TraversalPolicy;

import java.util.stream.IntStream;

/**
 * Utility class for creating {@link Producer}s that reference computation inputs (arguments).
 *
 * <p>{@link Input} provides factory methods to generate {@link PassThroughProducer} instances,
 * which act as placeholders for runtime arguments in computation graphs. These producers don't
 * perform any computation themselves - they simply pass through argument values provided at
 * evaluation time.</p>
 *
 * <h2>Core Concept: Input References</h2>
 *
 * <p>In Almost Realism's computation model, operations are built as graphs of {@link Producer}s.
 * Some producers perform computations, while others reference inputs that will be provided
 * when the graph is evaluated. {@link Input} creates these input reference producers.</p>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * // Build a computation that multiplies input[0] by input[1]
 * Producer<PackedCollection<?>> x = Input.value(1000, 0);  // References arg 0
 * Producer<PackedCollection<?>> y = Input.value(1000, 1);  // References arg 1
 * Producer<PackedCollection<?>> result = multiply(x, y);
 *
 * // Compile and evaluate with actual values
 * Evaluable<PackedCollection<?>> evaluable = result.get();
 * PackedCollection<?> output = evaluable.evaluate(actualX, actualY);
 * //                                              arg[0]   arg[1]
 * }</pre>
 *
 * <h2>Common Usage Patterns</h2>
 *
 * <h3>Single Input Reference</h3>
 * <pre>{@code
 * // Reference the first argument (index 0)
 * Producer<PackedCollection<?>> input = Input.value(1000, 0);
 *
 * // Build computation using that input
 * Producer<PackedCollection<?>> scaled = multiply(input, c(2.0));
 *
 * // Evaluate - provide actual data for arg 0
 * PackedCollection<?> data = new PackedCollection<>(1000);
 * PackedCollection<?> result = scaled.get().evaluate(data);
 * }</pre>
 *
 * <h3>Multiple Inputs</h3>
 * <pre>{@code
 * Producer<PackedCollection<?>> a = Input.value(shape, 0);
 * Producer<PackedCollection<?>> b = Input.value(shape, 1);
 * Producer<PackedCollection<?>> c = Input.value(shape, 2);
 *
 * // Computation: (a + b) * c
 * Producer<PackedCollection<?>> sum = add(a, b);
 * Producer<PackedCollection<?>> product = multiply(sum, c);
 *
 * // Evaluate with 3 arguments
 * result = product.get().evaluate(dataA, dataB, dataC);
 * }</pre>
 *
 * <h3>Generating Argument Arrays</h3>
 * <pre>{@code
 * // Create array of input references for args 0-9
 * Producer[] inputs = Input.generateArguments(1000, 0, 10);
 *
 * // Useful for variadic operations
 * Producer<PackedCollection<?>> sum = sum(inputs);  // Sums all 10 inputs
 *
 * // Evaluate with 10 actual arguments
 * result = sum.get().evaluate(
 *     data0, data1, data2, data3, data4,
 *     data5, data6, data7, data8, data9
 * );
 * }</pre>
 *
 * <h2>Argument Indexing</h2>
 *
 * <p>Argument indices must match the evaluation order:</p>
 * <ul>
 *   <li><strong>Index 0:</strong> First argument to {@code evaluate()}</li>
 *   <li><strong>Index 1:</strong> Second argument to {@code evaluate()}</li>
 *   <li><strong>Index n:</strong> (n+1)th argument to {@code evaluate()}</li>
 * </ul>
 *
 * <p><strong>Important:</strong> Argument indices must be continuous starting from 0.
 * Missing indices (e.g., 0, 2, 3 without 1) will cause runtime errors.</p>
 *
 * <h2>Shape and Memory Length</h2>
 *
 * <p>Input producers must specify the expected shape or memory length of the argument:</p>
 * <ul>
 *   <li><strong>{@link TraversalPolicy shape}:</strong> Specifies multi-dimensional structure
 *       (count, dimensions) for validation</li>
 *   <li><strong>{@code int memLength}:</strong> Shorthand for flat arrays - automatically
 *       creates a {@link TraversalPolicy} with the specified element count</li>
 * </ul>
 *
 * <p>These specifications are used for:
 * <ul>
 *   <li>Runtime validation - ensures provided arguments match expected sizes</li>
 *   <li>Kernel compilation - informs memory layout and iteration counts</li>
 *   <li>Type checking - validates computation graph consistency</li>
 * </ul>
 *
 * <h2>Integration with Hardware Acceleration</h2>
 *
 * <p>Input producers are compiled into kernel argument references:</p>
 * <pre>
 * Computation Graph:        Compiled Kernel:
 * Input.value(1000, 0) ->    __global double* arg0
 * Input.value(1000, 1) ->    __global double* arg1
 * multiply(0, 1)       ->    output[i] = arg0[i] * arg1[i]
 * </pre>
 *
 * <h2>Performance Notes</h2>
 *
 * <ul>
 *   <li>{@link Input} producers have zero runtime cost - they're placeholders
 *       resolved at compile time</li>
 *   <li>No memory allocation occurs when creating input references</li>
 *   <li>Argument passing uses direct memory pointers (zero-copy)</li>
 * </ul>
 *
 * @see PassThroughProducer
 * @see io.almostrealism.relation.Producer
 * @see TraversalPolicy
 */
public class Input {
	/**
	 * Private constructor prevents instantiation - this is a utility class.
	 */
	private Input() { }

	/**
	 * Creates a {@link Producer} that references a computation argument with a specific shape.
	 *
	 * <p>This is the most general form, allowing specification of multi-dimensional shapes
	 * for structured data (vectors, matrices, tensors).</p>
	 *
	 * <h3>Example:</h3>
	 * <pre>{@code
	 * // Reference a 10x100 matrix at argument index 0
	 * TraversalPolicy matrixShape = new TraversalPolicy(10, 100);
	 * Producer<PackedCollection<?>> matrix = Input.value(matrixShape, 0);
	 * }</pre>
	 *
	 * @param shape The expected shape of the argument
	 * @param argIndex The argument index (0-based, must match evaluation order)
	 * @param <T> The type of data this producer will reference
	 * @return A {@link PassThroughProducer} referencing the specified argument
	 */
	public static <T> Producer<T> value(TraversalPolicy shape, int argIndex) {
		return new PassThroughProducer(shape, argIndex);
	}

	/**
	 * Creates a {@link Producer} that references a flat array argument.
	 *
	 * <p>Convenience method for single-dimensional data. Automatically creates a
	 * {@link TraversalPolicy} with the specified element count.</p>
	 *
	 * <h3>Example:</h3>
	 * <pre>{@code
	 * // Reference a flat array of 1000 elements at argument index 0
	 * Producer<PackedCollection<?>> data = Input.value(1000, 0);
	 * }</pre>
	 *
	 * @param memLength The number of elements in the argument
	 * @param argIndex The argument index (0-based, must match evaluation order)
	 * @param <T> The type of data this producer will reference
	 * @return A {@link PassThroughProducer} referencing the specified argument
	 */
	public static <T> Producer<T> value(int memLength, int argIndex) {
		return new PassThroughProducer(new TraversalPolicy(memLength), argIndex);
	}

	/**
	 * Generates an array of {@link Producer}s referencing sequential argument indices.
	 *
	 * <p>Useful for variadic operations or when building computations that consume
	 * multiple similar inputs (e.g., summing many arrays, batch processing).</p>
	 *
	 * <h3>Example:</h3>
	 * <pre>{@code
	 * // Generate references for args 0-4 (5 total)
	 * Producer[] inputs = Input.generateArguments(1000, 0, 5);
	 * // Returns: [Input.value(1000, 0), Input.value(1000, 1), ..., Input.value(1000, 4)]
	 *
	 * // Use with variadic sum operation
	 * Producer<PackedCollection<?>> total = sum(inputs);
	 *
	 * // Evaluate with 5 arguments
	 * result = total.get().evaluate(data0, data1, data2, data3, data4);
	 * }</pre>
	 *
	 * <h3>Offset Example:</h3>
	 * <pre>{@code
	 * // Generate references starting at arg 2
	 * Producer[] args = Input.generateArguments(500, 2, 3);
	 * // Returns: [Input.value(500, 2), Input.value(500, 3), Input.value(500, 4)]
	 *
	 * // When evaluating, provide all arguments including 0 and 1
	 * result.get().evaluate(arg0, arg1, arg2, arg3, arg4);
	 * //                    skip  skip  ↑use  ↑use  ↑use
	 * }</pre>
	 *
	 * @param memLength The number of elements in each argument
	 * @param first The starting argument index (inclusive)
	 * @param count The number of argument references to generate
	 * @return An array of {@link Producer}s referencing arguments [first, first+count)
	 */
	public static Producer[] generateArguments(int memLength, int first, int count) {
		return IntStream.range(0, count).mapToObj(i -> value(memLength, first + i)).toArray(Producer[]::new);
	}
}
