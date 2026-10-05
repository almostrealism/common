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
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;

/**
 * Abstract base class for element-wise relational inequality comparisons
 * ({@code <}, {@code <=}, {@code >}, {@code >=}) that select between two values
 * based on the comparison result.
 *
 * <p>An inequality comparison differs from the equality comparison provided by
 * {@link CollectionComparisonComputation} in that it carries an {@link #includeEqual}
 * flag distinguishing a strict operator ({@code <}, {@code >}) from an inclusive one
 * ({@code <=}, {@code >=}). Concrete subclasses ({@link GreaterThanCollection},
 * {@link LessThanCollection}) supply only the specific relational operator by overriding
 * {@link #compare(Expression, Expression)}, plus the per-type reconstruction in
 * {@link #generate(java.util.List)}; everything the two operators share — the
 * {@code includeEqual} flag, the expansion width, the signature contribution, and
 * the conditional-selection expression scaffold — lives here so that a change to any
 * of it is made once for both operators.</p>
 *
 * @see CollectionComparisonComputation
 * @see GreaterThanCollection
 * @see LessThanCollection
 *
 * @author Michael Murray
 */
public abstract class InequalityComparisonComputation extends CollectionComparisonComputation {
	/**
	 * Flag controlling whether the comparison includes equality (e.g. {@code >=}, {@code <=})
	 * or is strict (e.g. {@code >}, {@code <}).
	 * When true, the inclusive form of the operator is used.
	 * When false, the strict form of the operator is used.
	 */
	protected final boolean includeEqual;

	/**
	 * Constructs an inequality comparison computation with four operands: two comparison
	 * values and two conditional result values.
	 *
	 * <p>A non-positive output count (indicating a zero-sized operand shape) is rejected
	 * by {@link CollectionProducerComputationBase}'s shape validation, which runs as part
	 * of the {@code super(...)} call below. {@link GreaterThanCollection} previously carried
	 * its own {@code getCountLong() <= 0} guard for this same case; that guard could never
	 * fire, because the base class validation above always rejects a zero-sized shape first,
	 * so it was removed rather than shared onto {@link LessThanCollection} as well.</p>
	 *
	 * @param name The operation identifier (e.g. "greaterThan", "lessThan")
	 * @param shape The {@link TraversalPolicy} defining the output shape and traversal pattern
	 * @param left The {@link Producer} providing the left-hand side comparison values
	 * @param right The {@link Producer} providing the right-hand side comparison values
	 * @param trueValue The {@link Producer} providing values to use when the comparison is true
	 * @param falseValue The {@link Producer} providing values to use when the comparison is false
	 * @param includeEqual If true, the inclusive form of the operator ({@code <=}/{@code >=})
	 *                     is used; if false, the strict form ({@code <}/{@code >}) is used
	 */
	protected InequalityComparisonComputation(
			String name,
			TraversalPolicy shape,
			Producer<PackedCollection> left, Producer<PackedCollection> right,
			Producer<PackedCollection> trueValue, Producer<PackedCollection> falseValue,
			boolean includeEqual) {
		super(name, shape, left, right, trueValue, falseValue);
		this.includeEqual = includeEqual;
		init();
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>The emitted expression is a ternary {@code Conditional(lhs OP rhs, truePath, falsePath)}
	 * &mdash; both branches materialise in the expression tree. The expansion width is
	 * therefore {@code 2}.</p>
	 */
	@Override
	public long getExpansionWidth() {
		return 2;
	}

	@Override
	public String signature() {
		String signature = super.signature();
		if (signature == null) return null;

		return signature + "{includeEqual:" +  includeEqual + "}";
	}

	/**
	 * Emits the relational comparison that this operator selects on, for a single
	 * element. The subclass returns the strict form ({@code left > right} or
	 * {@code left < right}) or, when {@link #includeEqual} is set, the inclusive form
	 * ({@code left >= right} or {@code left <= right}); the surrounding conditional that
	 * chooses between the true and false values is shared and lives in
	 * {@link #getExpression(TraversableExpression...)}.
	 *
	 * @param left The left-hand operand value at the current index
	 * @param right The right-hand operand value at the current index
	 * @return A boolean {@link Expression} that is true when the operator's relation holds
	 */
	protected abstract Expression<Boolean> compare(Expression<?> left, Expression<?> right);

	/**
	 * {@inheritDoc}
	 *
	 * <p>Builds the element-wise {@code conditional(compare(left, right), trueValue, falseValue)}
	 * selection that both inequality operators share, delegating only the relational operator to
	 * {@link #compare(Expression, Expression)}. The argument positions are those documented by
	 * {@link CollectionComparisonComputation}: {@code args[1]} and {@code args[2]} are the
	 * operands, {@code args[3]} and {@code args[4]} the true and false values.</p>
	 */
	@Override
	protected CollectionExpression getExpression(TraversableExpression... args) {
		return CollectionExpression.create(getShape(), index ->
				conditional(compare(args[1].getValueAt(index), args[2].getValueAt(index)),
						args[3].getValueAt(index), args[4].getValueAt(index)));
	}
}
