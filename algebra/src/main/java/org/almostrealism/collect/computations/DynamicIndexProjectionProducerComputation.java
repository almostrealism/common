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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.compute.Process;
import io.almostrealism.kernel.Index;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * A specialized {@link IndexProjectionProducerComputation} that supports dynamic, runtime-computed
 * index projections. Unlike the base class which uses static projection functions, this computation
 * allows the index projection to depend on the input data and additional runtime arguments.
 * 
 * <p>This class is particularly useful for operations where the index mapping cannot be determined
 * at compilation time, such as:</p>
 * <ul>
 *   <li>Argmax/argmin operations where indices depend on data values</li>
 *   <li>Conditional indexing based on runtime criteria</li>
 *   <li>Advanced sorting and ranking operations</li>
 *   <li>Data-dependent sampling and selection</li>
 * </ul>
 * 
 * <p><strong>Key Differences from Base Class:</strong></p>
 * <ul>
 *   <li>Uses {@link BiFunction} for projection instead of {@link java.util.function.UnaryOperator}</li>
 *   <li>Projection function receives {@link TraversableExpression} arguments for runtime computation</li>
 *   <li>Supports relative indexing for memory-efficient operations</li>
 *   <li>Provides specialized delta computation for machine learning applications</li>
 * </ul>
 * 
 * <p><strong>Usage Example - Finding Maximum Elements:</strong></p>
 * <pre>{@code
 * // Create a dynamic projection that finds the index of maximum value in each row
 * TraversalPolicy inputShape = shape(4, 5);   // 4x5 matrix
 * TraversalPolicy outputShape = shape(4, 1);  // 4x1 result (one index per row)
 * 
 * BiFunction<TraversableExpression[], Expression, Expression> maxProjection = 
 *     (args, idx) -> {
 *         TraversableExpression input = args[1];  // The input collection
 *         Expression row = idx;  // Output row index
 *         
 *         // Find column index with maximum value in this row
 *         Expression maxIndex = e(0);
 *         for (int col = 1; col < 5; col++) {
 *             Expression currentIdx = inputShape.index(row, e(col));
 *             Expression prevMaxIdx = inputShape.index(row, maxIndex);
 *             maxIndex = conditional(
 *                 input.getValueAt(currentIdx).greaterThan(input.getValueAt(prevMaxIdx)),
 *                 e(col), maxIndex);
 *         }
 *         
 *         return inputShape.index(row, maxIndex);
 *     };
 * 
 * DynamicIndexProjectionProducerComputation<?> maxFinder = 
 *     new DynamicIndexProjectionProducerComputation<>("findRowMax", outputShape, 
 *                                                     maxProjection, matrixProducer);
 * }</pre>
 * 
 * @param <T> The type of {@link PackedCollection} produced by this computation
 * 
 * @see IndexProjectionProducerComputation
 * @see TraversableExpression
 * @see BiFunction
 * 
 * @author Michael Murray
 * @since 0.68
 */
public class DynamicIndexProjectionProducerComputation<T extends PackedCollection>
		extends IndexProjectionProducerComputation<T> {
	/**
	 * Enables specialized delta computation for traverse-each operations.
	 * When true, allows optimized gradient computation for operations that 
	 * traverse each element of the input collection.
	 */
	public static boolean enableDeltaTraverseEach = false;
	
	/**
	 * Enables chained delta computation for nested dynamic projections.
	 * When true, allows optimization of gradient computation through
	 * multiple levels of dynamic index projections.
	 */
	public static boolean enableChainDelta = false;

	/**
	 * The dynamic index projection function that computes projections at runtime.
	 * This function receives both the traversable arguments and the output index,
	 * allowing it to compute projections based on actual data values.
	 */
	private BiFunction<TraversableExpression[], Expression, Expression> indexExpression;
	
	/**
	 * Indicates whether this computation uses relative indexing.
	 * When true, the computation uses {@link TraversableExpression#getValueRelative}
	 * for more memory-efficient access patterns.
	 */
	private boolean relative;

	/**
	 * Creates a dynamic index projection computation with absolute indexing.
	 * This is the standard constructor for most dynamic projection operations.
	 * 
	 * @param name A descriptive name for this computation
	 * @param shape The {@link TraversalPolicy} defining the output dimensions
	 * @param indexExpression The function that computes index projections at runtime
	 * @param collection The input {@link Producer} providing the source collection
	 * @param inputs Additional input {@link Producer}s that the projection may reference
	 */
	public DynamicIndexProjectionProducerComputation(String name, TraversalPolicy shape,
													 BiFunction<TraversableExpression[], Expression, Expression> indexExpression,
													 Producer<?> collection,
													 Producer<?>... inputs) {
		this(name, shape, indexExpression, false, collection, inputs);
	}

	/**
	 * Creates a dynamic index projection computation with configurable indexing mode.
	 * This constructor allows specification of relative vs. absolute indexing.
	 * 
	 * @param name A descriptive name for this computation
	 * @param shape The {@link TraversalPolicy} defining the output dimensions
	 * @param indexExpression The function that computes index projections at runtime
	 * @param relative Whether to use relative indexing for memory efficiency
	 * @param collection The input {@link Producer} providing the source collection
	 * @param inputs Additional input {@link Producer}s that the projection may reference
	 */
	public DynamicIndexProjectionProducerComputation(String name, TraversalPolicy shape,
													 BiFunction<TraversableExpression[], Expression, Expression> indexExpression,
													 boolean relative,
													 Producer<?> collection,
													 Producer<?>... inputs) {
		super(name, shape, null, collection, inputs);
		this.indexExpression = indexExpression;
		this.relative = relative;
	}

	@Override
	protected boolean isOutputRelative() { return relative || super.isOutputRelative(); }

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (relative) {
			TraversableExpression<Double> var = getTraversableArguments(index)[1];
			if (var == null) return null;

			Expression offset = index.divide(getMemLength()).multiply(shape(var).getSizeLong());
			return var.getValueAt(offset.add(projectIndex(var, index)));
		}

		return super.getValueAt(index);
	}

	@Override
	public Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (relative) {
			TraversableExpression var = getTraversableArguments(targetIndex)[1];
			if (var == null) return null;

			return var.uniqueNonZeroOffset(globalIndex, localIndex, projectIndex(var, targetIndex));
		}

		return super.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
	}

	@Override
	protected Expression<?> projectIndex(TraversableExpression<?> input, Expression<?> index) {
		return projectIndex(getTraversableArguments(index), index);
	}

	protected Expression<?> projectIndex(TraversableExpression[] args, Expression<?> index) {
		return indexExpression.apply(args, index);
	}

	@Override
	public DynamicIndexProjectionProducerComputation<T> generate(List<Process<?, ?>> children) {
		return (DynamicIndexProjectionProducerComputation)
				new DynamicIndexProjectionProducerComputation<>(getName(), getShape(), indexExpression, relative,
							(Producer<?>) children.get(1),
							children.stream().skip(2).toArray(Producer[]::new))
						.addAllDependentLifecycles(getDependentLifecycles());
	}

	@Override
	public CollectionProducer<T> delta(Producer<?> target) {
		if (enableChainDelta) {
			TraversableDeltaComputation<T> delta =
					TraversableDeltaComputation.create("delta", getShape(), shape(target),
								args -> CollectionExpression.create(getShape(),
										(idx) -> args[1].getValueAt(projectIndex(args, idx))),
							target, getInputs().stream().skip(1).toArray(Producer[]::new));
			return delta;
		} else {
			TraversalPolicy outShape = getShape();
			TraversalPolicy inShape = shape(getInputs().get(1));
			TraversalPolicy targetShape = shape(target);

			int outSize = outShape.getTotalSize();
			int inSize = inShape.getTotalSize();
			int targetSize = targetShape.getTotalSize();

			TraversalPolicy deltaShape = shape(inSize, targetSize);
			TraversalPolicy overallShape = shape(outSize, targetSize);

			CollectionProducer<PackedCollection> delta = ((CollectionProducer) getInputs().get(1)).delta(target);

			TraversalPolicy shape = outShape.append(targetShape);
			int traversalAxis = shape.getTraversalAxis();

			BiFunction<TraversableExpression[], Expression, Expression> project = (args, idx) -> {
				Expression pos[] = overallShape.position(idx);
				return deltaShape.index(projectIndex(args, pos[0]), pos[1]);
			};

			if (enableDeltaTraverseEach) {
				return traverse(traversalAxis,
						new DynamicIndexProjectionProducerComputation(
								getName() + "DeltaIndex", shape.traverseEach(), project, relative, delta,
								getInputs().stream().skip(2).toArray(Producer[]::new)));
			} else {
				return new DynamicIndexProjectionProducerComputation(
						getName() + "DeltaIndex", shape, project, relative, delta,
						getInputs().stream().skip(2).toArray(Producer[]::new));
			}
		}
	}
}
