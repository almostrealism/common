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

package io.almostrealism.collect;

import io.almostrealism.code.ExpressionList;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelIndex;
import org.almostrealism.io.Describable;

import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Represents a collection of values that can be accessed by index and traversed
 * as both a shaped multi-dimensional structure and a traversable expression.
 *
 * <p>{@link CollectionExpression} combines the capabilities of {@link TraversableExpression}
 * for index-based value access with {@link Shape} for dimensional structure, providing
 * a unified interface for working with multi-dimensional collections of {@link Double}
 * values within the expression framework.
 *
 * <h2>Core Capabilities</h2>
 *
 * <p>This interface provides several key operations:
 * <ul>
 *   <li><b>Value Access</b> - Retrieve values using multi-dimensional positions
 *       via {@link #getValue(Expression...)} or flat indices via {@link #getValueAt(Expression)}</li>
 *   <li><b>Streaming</b> - Convert the collection to a {@link Stream} of expressions
 *       for functional-style processing</li>
 *   <li><b>Aggregation</b> - Compute {@link #sum()}, {@link #max()}, and other
 *       aggregate operations over all values</li>
 *   <li><b>Differentiation</b> - Generate delta expressions via {@link #delta(CollectionExpression)}
 *       for gradient computation</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create a collection expression from a shape and value function
 * TraversalPolicy shape = new TraversalPolicy(10, 20);
 * CollectionExpression<?> expr = CollectionExpression.create(shape,
 *     index -> someExpression.getValueAt(index));
 *
 * // Access value at position (2, 5)
 * Expression<Double> value = expr.getValue(e(2), e(5));
 *
 * // Stream all values and compute sum
 * Expression<Double> total = expr.sum();
 *
 * // Convert to expression list for batch operations
 * ExpressionList<Double> list = expr.toList();
 * ExpressionList<Double> exponentials = list.exp();
 * }</pre>
 *
 * <h2>Relative Indexing</h2>
 *
 * <p>The {@link #getValueRelative(Expression)} method provides kernel-aware indexing
 * where the provided index is offset by the current kernel position multiplied
 * by the collection size. This enables efficient parallel processing where each
 * kernel instance operates on its own portion of the data.
 *
 * <h2>Shape Context</h2>
 *
 * <p>When a {@link CollectionExpression} represents a subset of a larger collection,
 * {@link #setTotalShape(TraversalPolicy)} can provide the context of the full
 * collection, enabling optimizations that consider the broader data layout.
 *
 * @param <T> the type returned by traversal operations (typically the implementing class)
 * @see TraversableExpression
 * @see Shape
 * @see CollectionExpressionAdapter
 * @see TraversalPolicy
 * @author Michael Murray
 */
public interface CollectionExpression<T> extends TraversableExpression<Double>, Shape<T>, Describable {

	/**
	 * Retrieves the value at the specified multi-dimensional position within this collection.
	 *
	 * <p>This method converts the multi-dimensional position array to a flat index
	 * using the collection's shape and delegates to {@link #getValueAt(Expression)}.
	 *
	 * @param pos the position expressions, one for each dimension of the collection's shape
	 * @return an {@link Expression} representing the value at the specified position
	 */
	@Override
	default Expression<Double> getValue(Expression... pos) {
		return getValueAt(getShape().index(pos));
	}

	/**
	 * Retrieves a value at an index relative to the current kernel position.
	 *
	 * <p>This method computes the absolute index by adding the relative index
	 * to the base offset determined by the current kernel index multiplied by
	 * the collection's size. This enables each kernel instance to access its
	 * own portion of the data during parallel execution.
	 *
	 * <p>The formula used is: {@code absoluteIndex = kernelIndex * size + relativeIndex}
	 *
	 * @param index the relative index within the current kernel's data portion
	 * @return an {@link Expression} representing the value at the computed absolute index
	 */
	@Override
	default Expression<Double> getValueRelative(Expression index) {
		return getValueAt(new KernelIndex().multiply(getShape().getSize()).add(index.toInt()));
	}

	/**
	 * Creates a {@link Stream} of all values in this collection as expressions.
	 *
	 * <p>The stream iterates over all indices from 0 to the total size of the
	 * collection, retrieving the expression for each value. This enables
	 * functional-style operations over the entire collection.
	 *
	 * <p><b>Note:</b> This method evaluates all indices eagerly to create the
	 * stream, which may be inefficient for very large collections.
	 *
	 * @return a {@link Stream} of {@link Expression} objects representing all values
	 */
	default Stream<Expression<Double>> stream() {
		return IntStream.range(0, getShape().getTotalSize()).mapToObj(i -> getValueAt(e(i)));
	}

	/**
	 * Converts this collection to an {@link ExpressionList} containing all values.
	 *
	 * <p>This method collects all values from the {@link #stream()} into an
	 * {@link ExpressionList}, which provides additional batch operations.
	 *
	 * @return an {@link ExpressionList} containing all values in this collection
	 */
	default ExpressionList<Double> toList() {
		return stream().collect(ExpressionList.collector());
	}

	/**
	 * Computes the sum of all values in this collection.
	 *
	 * @return an {@link Expression} representing the sum of all values
	 */
	default Expression<Double> sum() { return toList().sum(); }

	/**
	 * Computes the maximum value in this collection.
	 *
	 * @return an {@link Expression} representing the maximum value
	 */
	default Expression<Double> max() { return toList().max(); }

	/**
	 * Computes the exponential (e^x) of each value in this collection.
	 *
	 * @return an {@link ExpressionList} containing the exponential of each value
	 */
	default ExpressionList<Double> exp() { return toList().exp(); }

	/**
	 * Creates a delta (difference) expression between this collection and a target collection.
	 *
	 * <p>This method is used in gradient computation and automatic differentiation
	 * to compute how changes in one collection affect another.
	 *
	 * @param target the target collection to compute the delta against
	 * @return a {@link CollectionExpression} representing the delta
	 * @throws UnsupportedOperationException if delta computation is not supported
	 *         by the implementing class
	 */
	default CollectionExpression delta(CollectionExpression target) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Sets the total shape context for this collection expression.
	 *
	 * <p>If this {@link CollectionExpression} represents a subset of a larger set of values,
	 * this method can be used to provide the {@link TraversalPolicy} for the larger
	 * collection. This context may be useful in choosing the optimal behavior
	 * for the expression, such as memory layout decisions or traversal optimizations.
	 *
	 * <p>The default implementation does nothing; subclasses may override to
	 * store and utilize the total shape information.
	 *
	 * @param shape the {@link TraversalPolicy} representing the total shape of the
	 *              larger collection this expression is part of
	 */
	default void setTotalShape(TraversalPolicy shape) { }

	/**
	 * Determines if the specified index is contained within this collection's valid index range.
	 *
	 * <p>This implementation delegates to the {@link Shape} superinterface's
	 * default implementation.
	 *
	 * @param index an {@link Expression} representing the index to test
	 * @return an {@link Expression} evaluating to {@code true} if the index is valid
	 */
	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		return Shape.super.containsIndex(index);
	}

	/**
	 * Returns a human-readable description of this collection expression.
	 *
	 * @return a detailed string representation of this collection's shape
	 */
	@Override
	default String describe() {
		return getShape().toStringDetail();
	}

	/**
	 * Creates a new {@link CollectionExpression} with the specified shape and value function.
	 *
	 * <p>This factory method provides a convenient way to create collection expressions
	 * from a shape definition and a function that computes values at each index.
	 *
	 * @param shape the {@link TraversalPolicy} defining the dimensions of the collection
	 * @param valueAt a function that computes the value expression for a given index expression
	 * @return a new {@link CollectionExpression} with the specified behavior
	 */
	static CollectionExpression create(TraversalPolicy shape, Function<Expression<?>, Expression<?>> valueAt) {
		return new DefaultCollectionExpression(shape, valueAt);
	}
}
