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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.relation.Delegated;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.io.ConsoleFeatures;

/**
 * A functional interface representing an expression that can be traversed by index
 * to produce values. {@link TraversableExpression} provides the core abstraction for
 * accessing elements in a collection or computed sequence through index-based lookup.
 *
 * <p>This interface combines several capabilities:
 * <ul>
 *   <li>{@link IndexSet} - Defines which indices are valid for this expression</li>
 *   <li>{@link Algebraic} - Enables algebraic optimizations (zero detection, identity checks)</li>
 *   <li>{@link ExpressionFeatures} - Provides expression construction utilities</li>
 *   <li>{@link ConsoleFeatures} - Enables logging and debugging output</li>
 * </ul>
 *
 * <h2>Core Method</h2>
 *
 * <p>The primary method {@link #getValueAt(Expression)} is the only abstract method,
 * making this a functional interface. It returns an {@link Expression} representing
 * the value at a given index, enabling lazy evaluation and expression tree construction.
 *
 * <h2>Index Modes</h2>
 *
 * <p>Values can be accessed in multiple ways:
 * <ul>
 *   <li>{@link #getValueAt(Expression)} - Access by flat (linear) index</li>
 *   <li>{@link #getValue(Expression...)} - Access by multi-dimensional position</li>
 *   <li>{@link #getValueRelative(Expression)} - Access relative to current context</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Create a simple traversable expression
 * TraversableExpression<Double> squared = index -> index.multiply(index);
 *
 * // Get value at index 5
 * Expression<Double> value = squared.getValueAt(e(5)); // Returns 25
 *
 * // Use with collection framework
 * CollectionExpression<?> collection = ...;
 * TraversableExpression<Double> traversable = TraversableExpression.traverse(collection);
 * if (traversable != null) {
 *     Expression<Double> first = traversable.getValueAt(e(0));
 * }
 * }</pre>
 *
 * <h2>Unique Non-Zero Offset</h2>
 *
 * <p>The {@link #uniqueNonZeroOffset(Index, Index, Expression)} method supports
 * optimization analysis by determining if, for a given local index range, there
 * exists exactly one index that produces a non-zero value. This is useful for
 * sparse operations and reduction optimizations.
 *
 * @param <T> the type of values produced by this expression (typically {@link Double})
 * @see CollectionExpression
 * @see IndexSet
 * @see Algebraic
 * @author Michael Murray
 */
@FunctionalInterface
public interface TraversableExpression<T> extends IndexSet, Algebraic, ExpressionFeatures, ConsoleFeatures {

	/**
	 * Retrieves the value at the specified multi-dimensional position.
	 *
	 * <p>The default implementation throws {@link UnsupportedOperationException}
	 * since flat indexing via {@link #getValueAt(Expression)} is the primary
	 * access method. Subinterfaces like {@link CollectionExpression} override
	 * this to provide multi-dimensional access.
	 *
	 * @param pos the position expressions, one for each dimension
	 * @return an {@link Expression} representing the value at the position
	 * @throws UnsupportedOperationException by default; subclasses may override
	 */
	default Expression<T> getValue(Expression... pos) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Retrieves the value at the specified flat (linear) index.
	 *
	 * <p>This is the primary abstract method of the interface. Implementations
	 * must define how values are computed or retrieved for each index position.
	 *
	 * @param index an {@link Expression} representing the index position
	 * @return an {@link Expression} representing the value at the specified index
	 */
	Expression<T> getValueAt(Expression<?> index);

	/**
	 * Retrieves a value at an index relative to the current context.
	 *
	 * <p>The default implementation simply delegates to {@link #getValueAt(Expression)}.
	 * Subclasses like {@link CollectionExpression} may override this to provide
	 * kernel-aware relative indexing.
	 *
	 * @param index the relative index expression
	 * @return an {@link Expression} representing the value at the relative index
	 */
	default Expression<T> getValueRelative(Expression index) {
		return getValueAt(index);
	}

	/**
	 * Determines if the specified index is contained in this expression's valid index set.
	 *
	 * <p>The default implementation throws {@link UnsupportedOperationException}.
	 * Subclasses should override to provide index bounds checking.
	 *
	 * @param index an {@link Expression} representing the index to test
	 * @return an {@link Expression} evaluating to true if the index is valid
	 * @throws UnsupportedOperationException by default
	 */
	@Override
	default Expression<Boolean> containsIndex(Expression<Integer> index) {
		// TODO  exploit unique non-zero offset to determine this?
		throw new UnsupportedOperationException();
	}

	/**
	 * Computes the unique non-zero offset for this expression within a local index range.
	 *
	 * <p>This method analyzes the expression to determine if, for each global index,
	 * there is exactly one local index that produces a non-zero value. This information
	 * is crucial for optimizing sparse operations and reduction computations.
	 *
	 * <p>The analysis proceeds as follows:
	 * <ol>
	 *   <li>If the local index has a limit of 1, returns 0 (trivially unique)</li>
	 *   <li>Creates an {@link ExpressionMatrix} mapping global/local indices to target indices</li>
	 *   <li>Checks if columns follow a recognizable sequence pattern</li>
	 *   <li>Evaluates actual values to find unique non-zero positions</li>
	 * </ol>
	 *
	 * @param globalIndex the global index representing the outer iteration
	 * @param localIndex the local index representing the inner iteration
	 * @param targetIndex the target index expression to analyze
	 * @return an {@link Expression} representing the unique non-zero offset for each
	 *         global index, or null if no unique offset can be determined
	 */
	default Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (localIndex.getLimit().orElse(-1) == 1)
			return new IntegerConstant(0);

		ExpressionMatrix<?> indices = ExpressionMatrix.create(globalIndex, localIndex, targetIndex);
		if (indices == null) {
			if (ScopeSettings.enableExpressionWarnings)
				warn("Unable to create ExpressionMatrix for " + targetIndex.getExpressionSummary());
			return null;
		}

		IndexSequence columnSeq = indices.columnSequence();
		if (columnSeq != null) {
			return columnSeq.getExpression(globalIndex);
		}

		Expression<?> column[] = indices.allColumnsMatch();
		if (column != null) {
			// TODO
			// throw new RuntimeException("localIndex is irrelevant");
			warn("localIndex is irrelevant");
		}

		if (getValueAt(CollectionExpressionAdapter.generateTemporaryIndex()) instanceof InstanceReference) {
			return null;
		}

		ExpressionMatrix<T> values = indices.apply(this::getValueAt);
		if (values == null) {
			if (ScopeSettings.enableExpressionWarnings)
				warn("Unable to create ExpressionMatrix for " + getClass().getSimpleName());
			return null;
		}

		return values.uniqueNonZeroOffset(globalIndex);
	}

	/**
	 * Checks if this expression produces values that are independent of the index.
	 *
	 * <p>An index-independent expression produces the same value for all indices,
	 * effectively representing a constant or broadcast value. This property can
	 * be used for optimization during code generation.
	 *
	 * @return {@code true} if values are independent of the index; {@code false} otherwise
	 */
	default boolean isIndexIndependent() { return false; }

	/**
	 * Checks if this expression can be traversed.
	 *
	 * <p>Returns {@code true} by default, indicating that {@link #getValueAt(Expression)}
	 * can be called to access values. Implementations may return {@code false} to
	 * indicate that traversal is not supported or not meaningful.
	 *
	 * @return {@code true} if this expression supports traversal; {@code false} otherwise
	 */
	default boolean isTraversable() {
		return true;
	}

	/**
	 * Attempts to obtain a {@link TraversableExpression} from an arbitrary object.
	 *
	 * <p>This utility method handles several cases:
	 * <ul>
	 *   <li>If the object is already a {@link TraversableExpression} and is traversable,
	 *       returns it directly</li>
	 *   <li>If the object implements {@link Delegated}, recursively checks the delegate</li>
	 *   <li>Otherwise, returns null</li>
	 * </ul>
	 *
	 * @param o the object to convert to a traversable expression
	 * @return a {@link TraversableExpression} if conversion is possible, or null otherwise
	 */
	static TraversableExpression traverse(Object o) {
		if (o instanceof TraversableExpression) {
			if (!((TraversableExpression) o).isTraversable()) return null;
			return (TraversableExpression) o;
		} else if (o instanceof Delegated) {
			return traverse(((Delegated) o).getDelegate());
		} else {
			return null;
		}
	}

	/**
	 * Checks if two {@link TraversableExpression} instances are equal.
	 *
	 * <p>This utility method handles null values safely:
	 * <ul>
	 *   <li>Returns true if both references point to the same object</li>
	 *   <li>Returns false if either is null (but not both)</li>
	 *   <li>Otherwise delegates to {@link Object#equals(Object)}</li>
	 * </ul>
	 *
	 * @param a the first expression to compare
	 * @param b the second expression to compare
	 * @return {@code true} if the expressions are equal; {@code false} otherwise
	 */
	static boolean match(TraversableExpression<?> a, TraversableExpression<?> b) {
		if (a == b) return true;
		if (a == null || b == null) return false;
		return a.equals(b);
	}
}
