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

import io.almostrealism.collect.Algebraic;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducerParallelProcess;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A computation that performs element-wise addition of multiple {@link PackedCollection}s.
 *
 * <p>This class extends {@link TransitiveDeltaExpressionComputation} to provide efficient
 * element-wise addition operations with automatic differentiation support. It sums all input
 * collections element-by-element, producing an output collection of the same shape.</p>
 *
 * <h2>Mathematical Operation</h2>
 * <p>For input collections A, B, C, ..., the computation produces:</p>
 * <pre>
 * result[i] = A[i] + B[i] + C[i] + ...
 * </pre>
 * <p>where i ranges over all elements in the collections.</p>
 *
 * <h2>Broadcasting and Shape Compatibility</h2>
 * <p>All input collections must be compatible with the specified output shape. The computation
 * supports broadcasting rules where smaller dimensions can be automatically expanded to match
 * larger ones. The output shape is specified at construction time.</p>
 *
 * <h2>Automatic Differentiation</h2>
 * <p>The gradient of addition with respect to any input is simply 1 (or the identity operation).
 * Because this class extends {@link TransitiveDeltaExpressionComputation}, gradients flow
 * transitively through all input arguments during backpropagation:</p>
 * <pre>
 * d(A + B + C)/dA = 1
 * d(A + B + C)/dB = 1
 * d(A + B + C)/dC = 1
 * </pre>
 *
 * <h2>Usage Examples</h2>
 *
 * <p><strong>Adding two vectors:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(100); // 100-element vectors
 * CollectionProducer a = c(new PackedCollection(shape));
 * CollectionProducer b = c(new PackedCollection(shape));
 *
 * CollectionAddComputation<PackedCollection> add =
 *     new CollectionAddComputation<>(shape, a, b);
 *
 * PackedCollection result = add.get().evaluate();
 * }</pre>
 *
 * <p><strong>Adding multiple collections:</strong></p>
 * <pre>{@code
 * TraversalPolicy shape = shape(10, 10); // 10x10 matrices
 * CollectionProducer[] matrices = new CollectionProducer[5];
 * // ... initialize matrices ...
 *
 * CollectionAddComputation<PackedCollection> sumAll =
 *     new CollectionAddComputation<>(shape, matrices);
 *
 * PackedCollection sum = sumAll.get().evaluate();
 * }</pre>
 *
 * <h2>Performance Characteristics</h2>
 * <ul>
 *   <li><strong>Complexity:</strong> O(n) where n is the total number of elements</li>
 *   <li><strong>Memory:</strong> Output collection of size matching the input shape</li>
 *   <li><strong>Parallelization:</strong> Fully parallelizable across all elements</li>
 *   <li><strong>Hardware Acceleration:</strong> Compiles to efficient GPU/CPU kernels</li>
 * </ul>
 *
 * @see TransitiveDeltaExpressionComputation
 * @see CollectionMinusComputation
 * @see CollectionProductComputation
 * @see org.almostrealism.collect.CollectionFeatures#add(List)
 *
 * @author Michael Murray
 */
public class CollectionAddComputation extends TransitiveDeltaExpressionComputation {

	/**
	 * Cached result of {@link #isRowMonomial()}, computed lazily on first access. This
	 * computation's operands are fixed at construction ({@link #generate(List)} always
	 * returns a new instance rather than mutating this one), so the structural property
	 * can be safely memoized rather than re-derived by walking the operand subgraph on
	 * every call - which matters because the check recurses into each operand's own
	 * {@code isRowMonomial()}/{@code isZero()}, and without memoization a shared operand
	 * reachable through multiple paths in a DAG (common where a computation feeds more
	 * than one downstream consumer) would be re-evaluated once per path.
	 */
	private Boolean rowMonomial;

	/**
	 * Constructs a new addition computation with default name "add".
	 *
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer}s to be added together
	 */
	public CollectionAddComputation(TraversalPolicy shape, Producer<PackedCollection>... arguments) {
		this("add", shape, arguments);
	}

	/**
	 * Constructs a new addition computation with a custom name.
	 * This constructor allows subclasses or specialized versions to use different names
	 * for debugging and profiling purposes.
	 *
	 * @param name The name identifier for this computation
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param arguments Variable number of input {@link Producer}s to be added together
	 */
	protected CollectionAddComputation(String name, TraversalPolicy shape,
									   Producer<PackedCollection>... arguments) {
		super(name, shape, arguments);
	}

	/**
	 * Generates the expression that sums all input arguments element-wise.
	 * This method creates a {@link CollectionExpression} that adds together all input
	 * traversable expressions (excluding the destination at index 0).
	 *
	 * <p>The expression is created using the {@link #sum(TraversalPolicy, TraversableExpression[])}
	 * method from the parent interface, which produces an efficient element-wise summation.</p>
	 *
	 * @param args Array of {@link TraversableExpression}s where args[0] is the destination
	 *             and args[1..n] are the input collections to be added
	 * @return A {@link CollectionExpression} that computes the element-wise sum
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return sum(getShape(), Stream.of(args).skip(1).toArray(TraversableExpression[]::new));
	}

	/**
	 * Generates a new addition computation with the specified child processes.
	 * This method is used for computation graph optimization and parallel processing,
	 * creating a new instance with different input sources while maintaining the
	 * same addition semantics.
	 *
	 * <p>The method extracts producer arguments from the child processes (skipping
	 * the destination at index 0) and creates a new addition computation using the
	 * factory method {@link #add(List)}.</p>
	 *
	 * @param children List of child {@link Process} instances to use as inputs
	 * @return A new {@link CollectionProducerParallelProcess} that performs addition
	 *         on the child processes
	 * @see org.almostrealism.collect.CollectionFeatures#add(List)
	 */
	@Override
	public CollectionProducerParallelProcess generate(List<Process<?, ?>> children) {
		List<Producer<?>> args = children.stream().skip(1)
				.map(p -> (Producer<?>) p).collect(Collectors.toList());
		return (CollectionProducerParallelProcess) add(args);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The emitted expression is {@code c_1 + c_2 + … + c_N}, concatenating each
	 * operand's full emission into the output. Width is the operand count
	 * ({@code getChildren().size() - 1} excludes the destination at index 0). For a
	 * pairwise {@code add(a, b)} this returns {@code 2}; for a 6-way sum folded via
	 * the variadic factory, it returns {@code 6}.</p>
	 */
	@Override
	public long getExpansionWidth() {
		int operands = getChildren().size() - 1;
		return Math.max(1L, operands);
	}

	/**
	 * Determines if this element-wise sum preserves a row-monomial structure from one of
	 * its operands.
	 *
	 * <p>Unlike a Hadamard product, a sum of two row-monomial operands is not row-monomial
	 * in general - two different non-zero positions in the same row would add to a row with
	 * two non-zero entries. The property survives only in the degenerate case where every
	 * operand except one is algebraically zero, so the sum reduces to that one operand. This
	 * is exactly the shape produced by the product-rule {@link CollectionProductComputation#delta(Producer)}
	 * of a computation (such as convolution) multiplying a row-monomial Jacobian by a factor
	 * that does not depend on the differentiation target: the other product-rule term has a
	 * zero derivative and contributes a zero addend here.</p>
	 *
	 * <p>Recognition is based on the sole non-zero operand's declared property, not on how it is
	 * aligned into this sum. A shape-changing wrapper can therefore leave the flag {@code true}
	 * even when the aligned row no longer has at most one non-zero entry: a {@code reshape} that
	 * merges rows delegates the flag through unchanged, and a broadcast that repeats a lower-rank
	 * {@code (rows, 1)} operand across the {@code (rows, columns)} output copies its single
	 * non-zero into every column of each row. Both are false positives of the row-monomial
	 * property, but neither corrupts a result:
	 * {@link io.almostrealism.compute.RowMonomialOptimization} only uses the flag to keep the
	 * operand inline, and the gather collapse it enables is an independent value-based analysis
	 * that reads the true value at each row and declines to collapse any row whose non-zero entry
	 * is not unique, falling back to the dense reduction. The flag gates only whether the collapse
	 * is attempted, never what value it reads.</p>
	 *
	 * @return true if exactly one operand is non-zero and that operand is row-monomial
	 * @see Algebraic#isRowMonomial(Object)
	 * @see Algebraic#isZero(Object)
	 */
	@Override
	public boolean isRowMonomial() {
		if (rowMonomial == null) {
			rowMonomial = computeRowMonomial();
		}

		return rowMonomial;
	}

	/**
	 * Performs the actual row-monomial determination described by {@link #isRowMonomial()},
	 * invoked once and cached by that method.
	 *
	 * @return true if exactly one operand is non-zero and that operand is row-monomial
	 */
	private boolean computeRowMonomial() {
		List<Producer<PackedCollection>> operands = getInputs().stream().skip(1)
				.collect(Collectors.toList());

		Producer<PackedCollection> nonZero = null;

		for (Producer<PackedCollection> operand : operands) {
			if (Algebraic.isZero(operand)) continue;
			if (nonZero != null) return false;
			nonZero = operand;
		}

		return nonZero != null && Algebraic.isRowMonomial(nonZero);
	}
}
