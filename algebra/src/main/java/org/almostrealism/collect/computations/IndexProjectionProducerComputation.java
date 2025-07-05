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

import io.almostrealism.code.CollectionUtils;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.IndexProjectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.algebra.AlgebraFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * {@link IndexProjectionProducerComputation} is a specialized {@link TraversableExpressionComputation} 
 * that performs index transformation operations on multi-dimensional collections. This computation 
 * applies a custom index projection function to transform how collection elements are accessed,
 * enabling operations like subsetting, permuting, and other advanced indexing transformations.
 * 
 * <p>The core functionality involves:</p>
 * <ul>
 *   <li>Taking an input collection with a defined {@link TraversalPolicy} shape</li>
 *   <li>Applying an index projection function to transform access indices</li>
 *   <li>Producing a new collection where elements are accessed via the projected indices</li>
 *   <li>Supporting automatic differentiation through delta computation</li>
 * </ul>
 * 
 * <h2>Index Projection Concept</h2>
 * 
 * <p>An index projection is a mathematical transformation that maps one set of indices to another.
 * For example, if you have a 4-element collection [a, b, c, d] and want to create a subset [a, c, d],
 * the index projection function would map:</p>
 * <ul>
 *   <li>Output index 0 → Input index 0 (element a)</li>
 *   <li>Output index 1 → Input index 2 (element c)</li>
 *   <li>Output index 2 → Input index 3 (element d)</li>
 * </ul>
 * 
 * <h2>Usage Examples</h2>
 * 
 * <p><strong>Basic Subset Example:</strong></p>
 * <pre>{@code
 * // Create a simple subset computation that extracts elements at indices 0, 2, 3
 * TraversalPolicy inputShape = shape(4);   // 4-element input
 * TraversalPolicy outputShape = shape(3);  // 3-element output
 * 
 * UnaryOperator<Expression<?>> projection = idx -> {
 *     // Map output indices to input indices: [0->0, 1->2, 2->3]
 *     return conditional(idx.eq(e(0)), e(0),
 *            conditional(idx.eq(e(1)), e(2), e(3)));
 * };
 * 
 * IndexProjectionProducerComputation<?> subset = 
 *     new IndexProjectionProducerComputation<>("subset", outputShape, 
 *                                              projection, inputProducer);
 * 
 * // Result: produces [input[0], input[2], input[3]]
 * }</pre>
 * 
 * <p><strong>2D Matrix Row Selection Example:</strong></p>
 * <pre>{@code
 * // Extract specific rows from a 4x3 matrix
 * TraversalPolicy inputShape = shape(4, 3);   // 4 rows, 3 columns
 * TraversalPolicy outputShape = shape(2, 3);  // 2 rows, 3 columns
 * 
 * UnaryOperator<Expression<?>> rowProjection = idx -> {
 *     Expression[] pos = outputShape.position(idx);  // [row, col]
 *     Expression newRow = conditional(pos[0].eq(e(0)), e(1), e(3)); // rows 1 and 3
 *     return inputShape.index(newRow, pos[1]);
 * };
 * 
 * IndexProjectionProducerComputation<?> rowSelection = 
 *     new IndexProjectionProducerComputation<>("selectRows", outputShape, 
 *                                              rowProjection, matrixProducer);
 * 
 * // Result: produces a 2x3 matrix with rows 1 and 3 from the original
 * }</pre>
 * 
 * <p><strong>Permutation Example:</strong></p>
 * <pre>{@code
 * // Reverse the order of elements in a collection
 * TraversalPolicy shape = shape(5);  // 5-element collection
 * 
 * UnaryOperator<Expression<?>> reverseProjection = idx -> 
 *     e(4).subtract(idx);  // maps [0,1,2,3,4] to [4,3,2,1,0]
 * 
 * IndexProjectionProducerComputation<?> reversed = 
 *     new IndexProjectionProducerComputation<>("reverse", shape, 
 *                                              reverseProjection, inputProducer);
 * 
 * // Result: produces [input[4], input[3], input[2], input[1], input[0]]
 * }</pre>
 * 
 * <h2>Advanced Features</h2>
 * 
 * <p>This computation supports several advanced features:</p>
 * <ul>
 *   <li><strong>Delta Computation:</strong> Automatic differentiation for gradient-based optimization</li>
 *   <li><strong>Memory Optimization:</strong> Configurable isolation strategies for large computations</li>
 *   <li><strong>Constant Optimization:</strong> Special handling for constant index projections</li>
 *   <li><strong>Nested Projections:</strong> Support for chaining multiple index transformations</li>
 * </ul>
 * 
 * <h2>Related Classes</h2>
 * 
 * <p>This class serves as the foundation for several specialized computations:</p>
 * <ul>
 *   <li>{@link DynamicIndexProjectionProducerComputation} - For runtime-dynamic projections</li>
 *   <li>{@link org.almostrealism.collect.computations.PackedCollectionSubset} - Collection subsetting</li>
 *   <li>{@link org.almostrealism.collect.computations.CollectionPermute} - Element permutation</li>
 *   <li>{@link org.almostrealism.collect.computations.PackedCollectionEnumerate} - Element enumeration</li>
 * </ul>
 * 
 * @param <T> The type of {@link PackedCollection} produced by this computation
 * 
 * @see TraversableExpressionComputation
 * @see TraversalPolicy
 * @see TraversableExpression
 * @see PackedCollection
 * @see DynamicIndexProjectionProducerComputation
 * @see io.almostrealism.collect.IndexProjectionExpression
 * 
 * @author Michael Murray
 * @since 0.68
 */
public class IndexProjectionProducerComputation<T extends PackedCollection<?>>
		extends TraversableExpressionComputation<T> {
	/**
	 * Enables delegated isolation optimization for improved memory efficiency.
	 * When true, allows this computation to delegate isolation operations to optimize
	 * memory usage in complex computation graphs.
	 */
	public static boolean enableDelegatedIsolate = true;
	
	/**
	 * Enables delegated isolation specifically for constant computations.
	 * When true, constant index projections can use optimized isolation strategies.
	 */
	public static boolean enableConstantDelegatedIsolate = true;
	
	/**
	 * Enables input isolation optimization for nested index projections.
	 * When true, allows optimization of computations that contain other 
	 * {@link IndexProjectionProducerComputation} instances as inputs.
	 */
	public static boolean enableInputIsolate = false;

	/**
	 * The index projection function that transforms output indices to input indices.
	 * This function defines how elements from the input collection are mapped to 
	 * positions in the output collection.
	 */
	private UnaryOperator<Expression<?>> indexProjection;

	/**
	 * Creates a new index projection computation with the specified parameters.
	 * This is the primary constructor for most use cases.
	 * 
	 * @param name A descriptive name for this computation, used for debugging and optimization
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param indexProjection The function that maps output indices to input indices
	 * @param collection The input {@link Producer} providing the source collection
	 * 
	 * @throws IllegalArgumentException if shape or indexProjection is null
	 * 
	 * @see #IndexProjectionProducerComputation(String, TraversalPolicy, UnaryOperator, Producer, Producer...)
	 */
	public IndexProjectionProducerComputation(String name, TraversalPolicy shape,
											  UnaryOperator<Expression<?>> indexProjection,
											  Producer<?> collection) {
		this(name, shape, indexProjection, collection, new Producer[0]);
	}

	/**
	 * Creates a new index projection computation with additional input producers.
	 * This constructor allows for more complex computations that depend on multiple inputs
	 * beyond the primary collection being projected.
	 * 
	 * <p>Example usage with additional inputs:</p>
	 * <pre>{@code
	 * // Create a projection that depends on both the collection and a separate index array
	 * IndexProjectionProducerComputation<?> computation = 
	 *     new IndexProjectionProducerComputation<>("dynamicSubset", outputShape, 
	 *                                              projection, sourceCollection, 
	 *                                              indexArray, offsetProducer);
	 * }</pre>
	 * 
	 * @param name A descriptive name for this computation
	 * @param shape The {@link TraversalPolicy} defining the output collection's dimensions
	 * @param indexProjection The function that maps output indices to input indices
	 * @param collection The primary input {@link Producer} providing the source collection
	 * @param inputs Additional input {@link Producer}s that the projection function may reference
	 * 
	 * @throws IllegalArgumentException if shape or indexProjection is null
	 * @throws IllegalArgumentException if collection is null
	 */
	protected IndexProjectionProducerComputation(String name, TraversalPolicy shape,
												 UnaryOperator<Expression<?>> indexProjection,
												 Producer<?> collection,
												 Producer<?>... inputs) {
		super(name, shape, CollectionUtils.include(new Supplier[0], (Supplier) collection, (Supplier[]) inputs));
		this.indexProjection = indexProjection;
	}

	/**
	 * Creates the underlying {@link CollectionExpression} that implements the index projection.
	 * This method is called by the framework to generate the actual computation expression.
	 * 
	 * @param args The {@link TraversableExpression} arguments for this computation
	 * @return A new {@link io.almostrealism.collect.IndexProjectionExpression} that performs the projection
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return new IndexProjectionExpression(getShape(),
				idx -> projectIndex(args[1], idx), args[1]);
	}

	/**
	 * Projects an index using the input expression context.
	 * This method can be overridden by subclasses to provide custom projection logic
	 * that depends on the input expression.
	 * 
	 * @param input The input {@link TraversableExpression} being projected
	 * @param index The index {@link Expression} to project
	 * @return The projected index expression
	 */
	protected Expression<?> projectIndex(TraversableExpression<?> input, Expression<?> index) {
		return projectIndex(index);
	}

	/**
	 * Applies the index projection function to transform an index.
	 * This is the core method that performs the actual index transformation.
	 * 
	 * @param index The index {@link Expression} to project
	 * @return The projected index expression
	 */
	protected Expression<?> projectIndex(Expression<?> index) {
		return indexProjection.apply(index);
	}

	/**
	 * Creates a computation that generates the index mapping matrix.
	 * This method produces a matrix where each element indicates whether the corresponding
	 * input index contributes to the corresponding output index.
	 * 
	 * <p>The resulting matrix has dimensions [outputSize x inputSize] where:
	 * <ul>
	 *   <li>outputSize = number of elements in the output collection</li>
	 *   <li>inputSize = number of elements in the input collection</li>
	 *   <li>matrix[i][j] = 1 if output[i] uses input[j], 0 otherwise</li>
	 * </ul>
	 * 
	 * <p>This is primarily used for automatic differentiation and optimization analysis.</p>
	 * 
	 * @return A {@link CollectionProducerComputation} that produces the index mapping matrix
	 */
	public CollectionProducerComputation<PackedCollection<?>> getIndex() {
		int outSize = getShape().getTotalSize();
		int inSize = shape(getInputs().get(1)).getTotalSize();
		TraversalPolicy shape = shape(outSize, inSize);

		// TODO  This should use DiagonalCollectionExpression
		return compute(null, CollectionExpression.create(shape.traverse(), idx -> {
						Expression pos[] = shape.position(idx);
						return conditional(pos[0].eq(projectIndex(pos[1])), e(1), e(0));
					}))
				.addDependentLifecycle(this);
	}

	/**
	 * Forces isolation of this computation, bypassing permission checks.
	 * This is used internally when isolation is required regardless of the current
	 * isolation policy settings.
	 * 
	 * @return An isolated version of this computation
	 */
	private Process<Process<?, ?>, Evaluable<? extends T>> isolateForce() {
		return Process.isolationPermitted(this) ? super.isolate() : this;
	}

	/**
	 * Isolates the input computation if it's also an {@link IndexProjectionProducerComputation}.
	 * This optimization can improve performance for nested index projections by
	 * creating a more efficient computation graph.
	 * 
	 * @return An optimized version of this computation with isolated inputs
	 */
	private Process<Process<?, ?>, Evaluable<? extends T>> isolateInput() {
		IndexProjectionProducerComputation c;

		if (getInputs().get(1) instanceof IndexProjectionProducerComputation) {
			c = (IndexProjectionProducerComputation) ((IndexProjectionProducerComputation) getInputs().get(1)).isolateInput();
		} else {
			c = (IndexProjectionProducerComputation)
					generateReplacement((List) getInputs().stream().map(Process::isolated).collect(Collectors.toList()));
		}

		return c;
	}

	/**
	 * Creates an isolated version of this computation for memory optimization.
	 * This method implements sophisticated isolation strategies to minimize memory usage
	 * while maintaining computational correctness.
	 * 
	 * <p>The isolation strategy depends on several factors:</p>
	 * <ul>
	 *   <li>Whether explicit isolation is enabled globally</li>
	 *   <li>The memory length of the computation</li>
	 *   <li>Whether delegated isolation is enabled</li>
	 *   <li>Whether the computation is constant</li>
	 *   <li>Whether inputs are also index projection computations</li>
	 * </ul>
	 * 
	 * @return An isolated version of this computation optimized for memory usage
	 */
	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		if (Process.isExplicitIsolation() || getMemLength() > ScopeSettings.maxStatements)
			return super.isolate();

		if (enableDelegatedIsolate || (enableConstantDelegatedIsolate && isConstant())) {
			IndexProjectionProducerComputation c;

			if (enableInputIsolate && getInputs().get(1) instanceof IndexProjectionProducerComputation) {
				c = (IndexProjectionProducerComputation) ((IndexProjectionProducerComputation) getInputs().get(1)).isolateInput();
			} else {
				c = (IndexProjectionProducerComputation)
						generateReplacement((List) getInputs().stream().map(Process::isolated).collect(Collectors.toList()));
			}

			return c.isolateForce();
		}

		return super.isolate();
	}

	/**
	 * Generates a new instance of this computation with different child processes.
	 * This method is used by the optimization framework to create modified versions
	 * of this computation during graph optimization.
	 * 
	 * @param children The new child {@link Process} instances to use
	 * @return A new {@link IndexProjectionProducerComputation} with the specified children
	 */
	@Override
	public IndexProjectionProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new IndexProjectionProducerComputation<>(getName(), getShape(), indexProjection,
				(Producer<?>) children.get(1),
				children.stream().skip(2).toArray(Producer[]::new));
	}

	/**
	 * Computes the derivative of this computation with respect to a target variable.
	 * This method implements automatic differentiation for index projection operations,
	 * which is essential for gradient-based optimization and machine learning applications.
	 * 
	 * <p>The delta computation works by:</p>
	 * <ol>
	 *   <li>Checking if the input can match the target variable</li>
	 *   <li>Computing the derivative of the input collection</li>
	 *   <li>Applying the same index projection to the derivative</li>
	 *   <li>Adjusting dimensions to account for the derivative structure</li>
	 * </ol>
	 * 
	 * <p>If the input cannot contribute to the target (no dependency), this method
	 * returns a zero collection of appropriate dimensions.</p>
	 * 
	 * @param target The {@link Producer} representing the variable with respect to which 
	 *               the derivative is computed
	 * @return A {@link CollectionProducer} representing the derivative of this computation
	 * 
	 * @see org.almostrealism.algebra.AlgebraFeatures
	
	 */
	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		Supplier in = getInputs().get(1);

		if (AlgebraFeatures.cannotMatch(in, target)) {
			TraversalPolicy shape = getShape();
			TraversalPolicy targetShape = shape(target);
			return zeros(shape.append(targetShape));
		}

		CollectionProducer<PackedCollection<?>> delta = null;

		if (in instanceof CollectionProducer) {
			delta = ((CollectionProducer) in).delta(target);
		} else if (AlgebraFeatures.match(in, target)) {
			TraversalPolicy shape = shape(in);
			TraversalPolicy targetShape = shape(target);
			delta = identity(shape(shape.getTotalSize(), targetShape.getTotalSize()))
							.reshape(shape.append(targetShape));
		}

		if (delta != null) {
			TraversalPolicy outShape = getShape();
			TraversalPolicy inShape = shape(getInputs().get(1));
			TraversalPolicy targetShape = shape(target);

			int outSize = outShape.getTotalSize();
			int inSize = inShape.getTotalSize();
			int targetSize = targetShape.getTotalSize();

			TraversalPolicy deltaShape = shape(inSize, targetSize);
			TraversalPolicy overallShape = shape(outSize, targetSize);

			TraversalPolicy shape = outShape.append(targetShape);
			int traversalAxis = shape.getTraversalAxis();

			UnaryOperator<Expression<?>> project = idx -> {
				Expression pos[] = overallShape.position(idx);
				return deltaShape.index(projectIndex(pos[0]), pos[1]);
			};

			return traverse(traversalAxis,
					new IndexProjectionProducerComputation<>("projectDelta", shape.traverseEach(), project, delta));
		}

		return super.delta(target);
	}
}
