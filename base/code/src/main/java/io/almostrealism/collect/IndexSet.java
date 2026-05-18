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

package io.almostrealism.collect;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;

import java.util.Optional;

/**
 * Represents a subset of the infinite set of integers, which may or may not
 * itself be finite. This interface provides a foundation for defining index
 * membership, enabling compile-time and runtime determination of which indices
 * are valid for a given context.
 *
 * <p>{@link IndexSet} is a fundamental abstraction in the collection framework,
 * used to define:
 * <ul>
 *   <li><b>Traversal boundaries</b> - Which indices can be accessed during iteration</li>
 *   <li><b>Shape constraints</b> - Valid index ranges for multi-dimensional data</li>
 *   <li><b>Sparse patterns</b> - Non-contiguous index selections</li>
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <p>The primary use case is determining whether a given index is valid:
 * <pre>{@code
 * IndexSet indices = ...; // Some index set definition
 *
 * // Compile-time check with constant index
 * Optional<Boolean> isValid = indices.containsIndex(5);
 * if (isValid.isPresent() && isValid.get()) {
 *     // Index 5 is definitely in the set
 * }
 *
 * // Runtime expression for generated code
 * Expression<Integer> dynamicIndex = ...; // Some index expression
 * Expression<Boolean> membership = indices.containsIndex(dynamicIndex);
 * // Use membership expression in generated code for bounds checking
 * }</pre>
 *
 * <h2>Subinterfaces and Implementations</h2>
 *
 * <p>Key subinterfaces that extend {@link IndexSet}:
 * <ul>
 *   <li>{@link TraversalOrdering} - Adds ordering semantics with {@code indexOf()} mapping</li>
 *   <li>{@link TraversableExpression} - Combines index membership with algebraic properties</li>
 *   <li>{@link Shape} - Defines index membership based on dimensional constraints</li>
 * </ul>
 *
 * <h2>Expression-Based Membership</h2>
 *
 * <p>The {@link #containsIndex(Expression)} method returns an {@link Expression}
 * rather than a boolean, allowing membership tests to be embedded in generated
 * code. This enables efficient runtime bounds checking without requiring
 * pre-computed bounds arrays.
 *
 * @see TraversalOrdering
 * @see TraversableExpression
 * @see Shape
 * @author Michael Murray
 */
public interface IndexSet {
	/**
	 * Determines if the provided constant index is a member of this {@link IndexSet}.
	 *
	 * <p>This convenience method converts the integer index to an {@link IntegerConstant}
	 * and delegates to {@link #containsIndex(Expression)}, then attempts to extract
	 * a concrete boolean value.
	 *
	 * <p>The result is wrapped in an {@link Optional} because membership may not
	 * always be determinable at compile time (e.g., when the set bounds depend
	 * on runtime values).
	 *
	 * @param index the integer index to test for membership
	 * @return an {@link Optional} containing {@code true} if the index is in the set,
	 *         {@code false} if it is not, or {@link Optional#empty()} if membership
	 *         cannot be determined with the available information
	 */
	default Optional<Boolean> containsIndex(int index) {
		return containsIndex(new IntegerConstant(index)).booleanValue();
	}

	/**
	 * Creates an {@link Expression} representing the membership status of the
	 * provided index expression in this {@link IndexSet}.
	 *
	 * <p>The returned expression evaluates to {@code true} if the index is
	 * contained in the set and {@code false} otherwise. This expression can
	 * be used in generated code for runtime bounds checking or conditional
	 * execution.
	 *
	 * <p>Example implementation for a range-based index set:
	 * <pre>{@code
	 * public Expression<Boolean> containsIndex(Expression<Integer> index) {
	 *     return index.greaterThanOrEqual(min).and(index.lessThan(max));
	 * }
	 * }</pre>
	 *
	 * @param index an {@link Expression} representing the index to test; may be
	 *              a constant (like {@link IntegerConstant}) or a dynamic expression
	 * @return an {@link Expression} evaluating to {@code true} if the index is
	 *         a member of this set, {@code false} otherwise
	 */
	Expression<Boolean> containsIndex(Expression<Integer> index);
}
