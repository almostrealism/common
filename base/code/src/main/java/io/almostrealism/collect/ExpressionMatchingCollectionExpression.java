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
import io.almostrealism.expression.Conjunction;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.sequence.DefaultIndex;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.sequence.Index;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Objects;
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
 * comparison result is statically known via {@link #checkPossibleMatch}.</p>
 */
public class ExpressionMatchingCollectionExpression extends CollectionExpressionAdapter implements ConsoleFeatures {
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
		Expression<Boolean> comparison = compareExpressions(reference.getValueAt(index), compareTo.getValueAt(index));
		return conditional(comparison, positive.getValueAt(index), negative.getValueAt(index));
	}

	@Override
	public Expression<Integer> uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		ExpressionMatrix<Boolean> comparison = ExpressionMatrix.create(globalIndex, localIndex, targetIndex,
				i -> compareExpressions(reference.getValueAt(i), compareTo.getValueAt(i)));
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
		DefaultIndex index = generateTemporaryIndex();
		Expression<Boolean> compare = compareExpressions(reference.getValueAt(index), compareTo.getValueAt(index));
		Optional<Boolean> alt = compare.booleanValue();

		if (alt.isPresent()) {
			return alt.get() ? positive.isIndexIndependent() : negative.isIndexIndependent();
		}

		return false;
	}

	@Override
	public Console console() { return Scope.console; }

	/**
	 * Structurally compares two expressions for equality.
	 *
	 * <p>For {@link InstanceReference} pairs, equality holds when the referent names match
	 * (or both referents implement {@link Algebraic} and report a match) and the indices
	 * are equal. For other expression types, equality requires the same class and recursively
	 * equal children. If the class or child count differs, {@code false} is returned.</p>
	 *
	 * @param a the first expression
	 * @param b the second expression
	 * @return a boolean expression representing whether {@code a} equals {@code b}
	 */
	public static Expression<Boolean> compareExpressions(Expression<?> a, Expression<?> b) {
		if (a == null && b == null) return new BooleanConstant(true);
		if (a == null || b == null) return new BooleanConstant(false);
		if (a.getClass() != b.getClass()) return new BooleanConstant(false);

		if (a instanceof InstanceReference) {
			InstanceReference ra = (InstanceReference) a;
			InstanceReference rb = (InstanceReference) b;

			boolean eq = Objects.equals(ra.getReferent().getName(), rb.getReferent().getName());
			eq = eq || (ra.getReferent() instanceof Algebraic && rb.getReferent() instanceof Algebraic &&
					((Algebraic) ra.getReferent()).matches((Algebraic) rb.getReferent()));

			if (eq) {
				return ra.getIndex().eq(rb.getIndex());
			}
		} else if (a.getChildren().size() == b.getChildren().size()) {
			if (a.getChildren().isEmpty()) return a.eq(b);

			Expression<Boolean> comparisons[] = new Expression[a.getChildren().size()];

			for (int i = 0; i < a.getChildren().size(); i++) {
				comparisons[i] = compareExpressions(a.getChildren().get(i), b.getChildren().get(i));
			}

			return Conjunction.of(comparisons);
		}

		return new BooleanConstant(false);
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
	 * <p>If {@link #checkPossibleMatch} can statically determine that the reference always
	 * equals (or never equals) the compareTo expression, the positive or negative operand
	 * is returned directly without building a conditional expression.</p>
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
		Optional<Boolean> comp = checkPossibleMatch(reference, compareTo);
		if (comp.isEmpty())
			return new ExpressionMatchingCollectionExpression(reference, compareTo, positive, negative);
		return comp.orElseThrow() ? positive : negative;
	}

	/**
	 * Checks whether the given reference and compareTo expressions can possibly match by evaluating
	 * them at a temporary index and inspecting the resulting comparison expression.
	 *
	 * <p>Returns {@link Optional#empty()} when the result cannot be determined statically.
	 * Only applies when {@link #enablePossibleMatch} is {@code true}.</p>
	 *
	 * @param reference  the reference expression
	 * @param compareTo  the expression to compare against
	 * @return an optional boolean: {@code true} if always matching, {@code false} if never matching,
	 *         or empty if unknown
	 */
	protected static Optional<Boolean> checkPossibleMatch(CollectionExpression reference, CollectionExpression compareTo) {
		if (!enablePossibleMatch) return Optional.empty();

		DefaultIndex index = generateTemporaryIndex();
		Expression r = reference.getValueAt(index);
		Expression c = compareTo.getValueAt(index);
		Expression<Boolean> comparison = compareExpressions(r, c);
		if (comparison instanceof BooleanConstant) {
			return comparison.booleanValue();
		}

		return Optional.empty();
	}
}
