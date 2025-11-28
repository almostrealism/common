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

package org.almostrealism.collect.computations;

import io.almostrealism.collect.ArithmeticSequenceExpression;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.IntStream;

/**
 * A computation that generates arithmetic sequences (linear sequences) with configurable
 * initial value and rate of change.
 *
 * <p>This class extends {@link TraversableExpressionComputation} to produce sequences of
 * values that follow the pattern: {@code value[i] = initial + i * rate}. It is commonly
 * used for generating index sequences, positional encodings, and other linearly-varying
 * values in computational graphs.</p>
 *
 * <h2>Arithmetic Sequence Formula</h2>
 * <p>For a sequence with initial value {@code a0} and rate {@code r}, the i-th element is:</p>
 * <pre>
 * ai = a0 + i x r
 * </pre>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li><strong>Index Sequences:</strong> [0, 1, 2, 3, ...] for array indexing</li>
 *   <li><strong>Position Encodings:</strong> Linearly-varying positional information</li>
 *   <li><strong>Range Generation:</strong> [start, start+step, start+2*step, ...]</li>
 *   <li><strong>Testing/Debugging:</strong> Predictable test data patterns</li>
 * </ul>
 *
 * <h2>Fixed Count vs Dynamic Count</h2>
 * <p>The {@link #fixedCount} parameter controls shape behavior:</p>
 * <ul>
 *   <li><strong>Fixed Count (true):</strong> Sequence length is determined by shape at construction time</li>
 *   <li><strong>Dynamic Count (false):</strong> Sequence length can be determined at runtime</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Simple index sequence [0, 1, 2, ...]:</strong></p>
 * <pre>{@code
 * ArithmeticSequenceComputation<PackedCollection> indices =
 *     new ArithmeticSequenceComputation<>(shape(10), 0.0);
 * PackedCollection result = indices.get().evaluate();
 * // Result: [0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0]
 * }</pre>
 *
 * <p><strong>Sequence starting at 10 with step 2:</strong></p>
 * <pre>{@code
 * ArithmeticSequenceComputation<PackedCollection> seq =
 *     new ArithmeticSequenceComputation<>(shape(5), true, 10.0, 2.0);
 * PackedCollection result = seq.get().evaluate();
 * // Result: [10.0, 12.0, 14.0, 16.0, 18.0]
 * }</pre>
 *
 * <p><strong>Negative rate (descending sequence):</strong></p>
 * <pre>{@code
 * ArithmeticSequenceComputation<PackedCollection> descending =
 *     new ArithmeticSequenceComputation<>(shape(6), true, 100.0, -10.0);
 * PackedCollection result = descending.get().evaluate();
 * // Result: [100.0, 90.0, 80.0, 70.0, 60.0, 50.0]
 * }</pre>
 *
 * <p><strong>Scaling a sequence:</strong></p>
 * <pre>{@code
 * ArithmeticSequenceComputation<PackedCollection> original =
 *     new ArithmeticSequenceComputation<>(shape(4), 1.0);
 * ArithmeticSequenceComputation<PackedCollection> scaled = original.multiply(5.0);
 * // Original: [1.0, 2.0, 3.0, 4.0]
 * // Scaled:   [5.0, 10.0, 15.0, 20.0]
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Memory:</strong> O(n) where n is the sequence length</li>
 *   <li><strong>Computation:</strong> O(n) generation, can be optimized in kernel code</li>
 *   <li><strong>Kernel Optimization:</strong> Simple arithmetic enables efficient GPU/CPU kernels</li>
 *   <li><strong>Direct Evaluation:</strong> Falls back to Java stream for non-kernel execution</li>
 * </ul>
 *
 * <h2>Implementation Notes</h2>
 * <p>The {@link #getExpression(TraversableExpression...)} method uses
 * {@link ArithmeticSequenceExpression} with a fixed rate of 1, as the general rate scaling
 * can be handled through the {@link #multiply(double)} method.</p>
 *
 * @param <T> The type of {@link PackedCollection} this computation produces
 *
 * @see TraversableExpressionComputation
 * @see ArithmeticSequenceExpression
 * @see org.almostrealism.collect.CollectionFeatures#integers(int)
 *
 * @author Michael Murray
 */
public class ArithmeticSequenceComputation<T extends PackedCollection> extends TraversableExpressionComputation<T> {
	/**
	 * Whether the sequence length is fixed at construction time (true) or
	 * can be determined dynamically at runtime (false).
	 */
	private boolean fixedCount;

	/**
	 * The initial value (first element) of the arithmetic sequence.
	 */
	private double initial;

	/**
	 * The rate of change (step size) between consecutive elements.
	 */
	private double rate;

	/**
	 * Constructs a single-element arithmetic sequence with the specified initial value.
	 *
	 * <p>This constructor creates a sequence of length 1 with dynamic count (not fixed),
	 * containing only the initial value. The rate defaults to 1, though it's not used
	 * for a single-element sequence.</p>
	 *
	 * @param initial The value of the single element
	 */
	public ArithmeticSequenceComputation(double initial) {
		this(new TraversalPolicy(1), false, initial);
	}

	/**
	 * Constructs an arithmetic sequence with the specified shape, starting at the
	 * initial value with a rate of 1.
	 *
	 * <p>This constructor creates a fixed-count sequence (length determined by shape)
	 * with unit steps: {@code [initial, initial+1, initial+2, ...]}.</p>
	 *
	 * @param shape The {@link TraversalPolicy} defining the sequence length and shape
	 * @param initial The starting value of the sequence
	 */
	public ArithmeticSequenceComputation(TraversalPolicy shape, double initial) {
		this(shape, true, initial, 1);
	}

	/**
	 * Constructs an arithmetic sequence with the specified shape and initial value,
	 * using a rate of 1.
	 *
	 * <p>This constructor allows specification of whether the count is fixed or dynamic.
	 * The sequence has unit steps: {@code [initial, initial+1, initial+2, ...]}.</p>
	 *
	 * @param shape The {@link TraversalPolicy} defining the sequence length and shape
	 * @param fixedCount Whether the sequence length is fixed (true) or dynamic (false)
	 * @param initial The starting value of the sequence
	 */
	public ArithmeticSequenceComputation(TraversalPolicy shape, boolean fixedCount, double initial) {
		this(shape, fixedCount, initial, 1);
	}

	/**
	 * Constructs an arithmetic sequence with full control over all parameters.
	 *
	 * <p>This is the primary constructor that accepts all configuration parameters.
	 * The resulting sequence follows the formula: {@code value[i] = initial + i * rate}.</p>
	 *
	 * @param shape The {@link TraversalPolicy} defining the sequence length and shape
	 * @param fixedCount Whether the sequence length is fixed (true) or dynamic (false)
	 * @param initial The starting value of the sequence
	 * @param rate The step size between consecutive elements
	 */
	public ArithmeticSequenceComputation(TraversalPolicy shape, boolean fixedCount, double initial, double rate) {
		super("linearSeq", shape);
		this.fixedCount = fixedCount;
		this.initial = initial;
		this.rate = rate;
	}

	/**
	 * Creates a new arithmetic sequence by scaling this sequence by the specified factor.
	 *
	 * <p>Both the initial value and the rate are multiplied by the factor, resulting in
	 * a sequence where every element is {@code factor} times the corresponding element
	 * in the original sequence:</p>
	 * <pre>
	 * original[i] = initial + i x rate
	 * scaled[i]   = (initial x factor) + i x (rate x factor)
	 *             = factor x (initial + i x rate)
	 *             = factor x original[i]
	 * </pre>
	 *
	 * @param factor The scaling factor to apply
	 * @return A new {@link ArithmeticSequenceComputation} with scaled values
	 */
	@Override
	public ArithmeticSequenceComputation<T> multiply(double factor) {
		return new ArithmeticSequenceComputation<>(getShape(), fixedCount, initial * factor, rate * factor);
	}

	/**
	 * Returns whether this sequence has a fixed count determined at construction time.
	 *
	 * <p>When {@code true}, the sequence length is determined by the {@link TraversalPolicy}
	 * shape at construction. When {@code false}, the length can be determined dynamically
	 * at runtime based on the output buffer size.</p>
	 *
	 * @return {@code true} if the count is fixed, {@code false} if dynamic
	 */
	@Override
	public boolean isFixedCount() { return fixedCount; }

	/**
	 * Creates the expression for generating arithmetic sequence values.
	 *
	 * <p>Returns an {@link ArithmeticSequenceExpression} with the configured initial value
	 * and a rate of 1. Note that the actual rate scaling is handled through the
	 * {@link #multiply(double)} method rather than in the expression itself.</p>
	 *
	 * @param args Array of {@link TraversableExpression}s (not used for sequence generation)
	 * @return An {@link ArithmeticSequenceExpression} for kernel code generation
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new ArithmeticSequenceExpression(getShape(), initial, 1);
	}

	/**
	 * Returns an {@link Evaluable} that directly computes the arithmetic sequence using Java streams.
	 *
	 * <p>This method provides a fallback implementation for direct evaluation outside of
	 * kernel execution contexts. It generates the sequence using {@link IntStream} with
	 * the formula {@code value[i] = initial + i * rate}.</p>
	 *
	 * <p><strong>Warning:</strong> This method logs a warning when used, as direct evaluation
	 * bypasses kernel optimization and hardware acceleration. It should primarily be used
	 * for testing or in contexts where kernel compilation is not available.</p>
	 *
	 * @return An {@link Evaluable} that computes the arithmetic sequence directly
	 */
	public Evaluable<T> get() {
		return args -> {
			warn("Direct evaluation of arithmetic sequence");
			return (T) pack(IntStream.range(0, getShape().getTotalSize())
					.mapToDouble(i -> initial + i * rate).toArray());
		};
	}

	/**
	 * Generates a computation with the specified child processes.
	 *
	 * <p>For arithmetic sequences, there are no child processes (the sequence is
	 * self-generating), so this method simply returns this computation unchanged.</p>
	 *
	 * @param children List of child {@link Process} instances (ignored)
	 * @return This computation (arithmetic sequences have no children to update)
	 */
	@Override
	public CollectionProducerParallelProcess<T> generate(List<Process<?, ?>> children) {
		return this;
	}
}
