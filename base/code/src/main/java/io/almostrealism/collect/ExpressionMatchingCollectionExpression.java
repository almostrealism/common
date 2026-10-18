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
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.sequence.Index;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;

import java.util.Optional;

/**
 * A {@link CollectionExpression} that compares two reference expressions element-wise
 * and selects from a positive or negative operand based on the comparison result.
 *
 * <p>At each index the value of {@code reference} is {@link Expression#matches(Expression)
 * matched} against the value of {@code compareTo}. Where they match, the value from
 * {@code positive} is returned; otherwise the value from {@code negative} is returned. The
 * match is decided on the expression tree structure (not at runtime), so it can be resolved
 * at compile time when both sides are constants or share the same sub-expression tree.</p>
 *
 * <p>The static {@link #create} factory methods return the selected operand itself, rather
 * than a conditional, when the selection is the same at every index.</p>
 */
public class ExpressionMatchingCollectionExpression extends CollectionExpressionAdapter {
	/**
	 * Whether {@link #create} checks, before building the conditional, whether the reference
	 * and compareTo expressions match at every index or at none, allowing early elimination
	 * of the conditional.
	 */
	public static boolean enablePossibleMatch = true;

	/** The expression supplying the reference values for comparison. */
	private final CollectionExpression reference;

	/** The expression supplying the values to compare against the reference. */
	private final CollectionExpression compareTo;

	/** The expression used when the comparison is true. */
	private final CollectionExpression positive;

	/** The expression used when the comparison is false. */
	private final CollectionExpression negative;

	/**
	 * Creates a matching expression with the given reference, comparison target,
	 * and positive/negative outcomes.
	 *
	 * @param reference  the expression providing reference values
	 * @param compareTo  the expression to compare against the reference
	 * @param positive   the expression to use where reference matches compareTo
	 * @param negative   the expression to use where reference does not match compareTo
	 */
	public ExpressionMatchingCollectionExpression(CollectionExpression reference,
												  CollectionExpression compareTo,
												  CollectionExpression positive,
												  CollectionExpression negative) {
		super("match", reference.getShape());
		this.reference = reference;
		this.compareTo = compareTo;
		this.positive = positive;
		this.negative = negative;
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return conditional(matchAt(index), positive.getValueAt(index), negative.getValueAt(index));
	}

	@Override
	public Expression<Integer> uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		ExpressionMatrix<Boolean> comparison =
				ExpressionMatrix.create(globalIndex, localIndex, targetIndex, this::matchAt);
		if (comparison == null) return null;

		Expression<Boolean> allMatch = comparison.allMatch();

		if (allMatch == null) {
			return comparison.uniqueMatchingOffset(globalIndex, e -> e.booleanValue().orElse(false));
		}

		return allMatch.booleanValue().map(this::select)
				.map(selected -> selected.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex))
				.orElse(null);
	}

	@Override
	public boolean isIndexIndependent() {
		return getUniformSelection().map(CollectionExpression::isIndexIndependent).orElse(false);
	}

	@Override
	public Console console() { return Scope.console; }

	/**
	 * Returns the condition under which the value of the reference at the given index
	 * {@link Expression#matches(Expression) matches} the value of compareTo at that index.
	 *
	 * @param index the index at which to compare the two expressions
	 * @return a boolean expression that holds where the operand to select is {@code positive}
	 */
	private Expression<Boolean> matchAt(Expression<?> index) {
		return reference.getValueAt(index).matches(compareTo.getValueAt(index));
	}

	/**
	 * Returns the operand this expression selects at every index, when that does not depend
	 * on the index: the match is decided at a fresh temporary index, and if it holds or fails
	 * there regardless of the index's value, the same operand is selected everywhere.
	 *
	 * @return the operand selected at every index, or empty if the selection depends on the index
	 */
	private Optional<CollectionExpression> getUniformSelection() {
		return matchAt(DefaultIndex.temporary()).booleanValue().map(this::select);
	}

	/**
	 * Returns the operand selected for the given outcome of the match.
	 *
	 * @param matched whether reference matches compareTo
	 * @return {@code positive} if matched, otherwise {@code negative}
	 */
	private CollectionExpression select(boolean matched) {
		return matched ? positive : negative;
	}

	/**
	 * Creates a matching expression using constant positive and negative scalar values.
	 *
	 * <p>Delegates to {@link #create(CollectionExpression, CollectionExpression, CollectionExpression, CollectionExpression)}
	 * after wrapping the scalars in {@link ConstantCollectionExpression} instances.</p>
	 *
	 * @param reference  the reference expression
	 * @param compareTo  the comparison target expression
	 * @param positive   the scalar value when the comparison is true
	 * @param negative   the scalar value when the comparison is false
	 * @return a collection expression implementing the conditional selection
	 */
	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo,
			Expression<?> positive, Expression<?> negative) {
		return create(reference, compareTo,
				new ConstantCollectionExpression(reference.getShape(), positive),
				new ConstantCollectionExpression(reference.getShape(), negative));
	}

	/**
	 * Creates a matching expression, returning the selected operand itself when the
	 * selection is the same at every index.
	 *
	 * <p>When {@link #enablePossibleMatch} is set and the reference matches the compareTo
	 * expression at every index (or at none), the positive (or negative) operand is returned
	 * directly instead of a conditional expression.</p>
	 *
	 * @param reference  the reference expression
	 * @param compareTo  the comparison target expression
	 * @param positive   the expression when the comparison is true
	 * @param negative   the expression when the comparison is false
	 * @return a collection expression implementing the conditional selection
	 */
	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo,
			CollectionExpression positive, CollectionExpression negative) {
		ExpressionMatchingCollectionExpression match =
				new ExpressionMatchingCollectionExpression(reference, compareTo, positive, negative);
		return enablePossibleMatch ? match.getUniformSelection().orElse(match) : match;
	}
}
