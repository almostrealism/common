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

import io.almostrealism.expression.BooleanConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.sequence.Index;

import java.util.Optional;

/**
 * A {@link CollectionExpression} that compares two reference expressions element-wise
 * and selects from a positive or negative operand based on the comparison result.
 *
 * <p>At each index the {@code reference} and {@code compareTo} expressions are compared
 * structurally. If they match, the value from {@code positive} is returned; otherwise the
 * value from {@code negative} is returned. The comparison is done on the expression tree
 * structure (not at runtime), so it can be resolved at compile time when both sides are
 * constants or share the same sub-expression tree.</p>
 *
 * <p>The static {@link #create} factory methods can short-circuit the conditional when the
 * comparison result is statically known via {@link #staticComparison}.</p>
 */
public class ExpressionMatchingCollectionExpression extends CollectionExpressionAdapter {
	/**
	 * Whether to attempt a static pre-check of whether the reference and compareTo expressions
	 * can possibly match, allowing early elimination of the conditional.
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
	 * @param positive   the expression to use when reference equals compareTo
	 * @param negative   the expression to use when reference does not equal compareTo
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
		return conditional(comparisonAt(index), positive.getValueAt(index), negative.getValueAt(index));
	}

	@Override
	public Expression<Integer> uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		ExpressionMatrix<Boolean> comparison =
				ExpressionMatrix.create(globalIndex, localIndex, targetIndex, this::comparisonAt);
		if (comparison == null) return null;

		Expression<Boolean> allMatch = comparison.allMatch();

		if (allMatch != null) {
			Optional<Boolean> alt = allMatch.booleanValue();

			if (alt.isPresent()) {
				if (alt.get()) {
					return positive.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
				} else {
					return negative.uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
				}
			}
		} else {
			return comparison.uniqueMatchingOffset(globalIndex, e -> e.booleanValue().orElse(false));
		}

		return null;
	}

	@Override
	public boolean isIndexIndependent() {
		Optional<Boolean> alt = staticComparison();

		if (alt.isPresent()) {
			return alt.get() ? positive.isIndexIndependent() : negative.isIndexIndependent();
		}

		return false;
	}

	/**
	 * Builds the comparison of the {@code reference} and {@code compareTo} values at the given
	 * index, using {@link Expression#structuralEq(Expression) structural equality}. A missing
	 * value matches only another missing value.
	 *
	 * @param index the index at which to compare
	 * @return a boolean expression for whether the two values match at {@code index}
	 */
	protected Expression<Boolean> comparisonAt(Expression<?> index) {
		Expression<?> r = reference.getValueAt(index);
		Expression<?> c = compareTo.getValueAt(index);
		if (r == null || c == null) return new BooleanConstant(r == c);
		return r.structuralEq(c);
	}

	/**
	 * Determines whether the comparison is statically known, by comparing the {@code reference}
	 * and {@code compareTo} values at a temporary index.
	 *
	 * @return {@code true} if the values always match, {@code false} if they never match,
	 *         or empty if this cannot be determined without evaluation
	 */
	protected Optional<Boolean> staticComparison() {
		return comparisonAt(generateTemporaryIndex()).booleanValue();
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
	 * Creates a matching expression, short-circuiting when the comparison is statically known.
	 *
	 * <p>When {@link #enablePossibleMatch} is set and the {@link #staticComparison() static
	 * comparison} shows that the reference always equals (or never equals) the compareTo
	 * expression, the positive or negative operand is returned directly instead of the
	 * conditional expression.</p>
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
		if (!enablePossibleMatch) return match;

		Optional<Boolean> comp = match.staticComparison();
		if (comp.isEmpty()) return match;
		return comp.get() ? positive : negative;
	}
}
