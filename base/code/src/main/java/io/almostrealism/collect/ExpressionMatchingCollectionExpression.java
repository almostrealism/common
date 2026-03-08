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
import io.almostrealism.kernel.DefaultIndex;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.kernel.Index;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Objects;
import java.util.Optional;

public class ExpressionMatchingCollectionExpression extends CollectionExpressionAdapter implements ConsoleFeatures {
	public static boolean enablePossibleMatch = true;

	private final CollectionExpression reference;
	private final CollectionExpression compareTo;
	private final CollectionExpression positive;
	private final CollectionExpression negative;

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

	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo,
			Expression<?> positive, Expression<?> negative) {
		return create(reference, compareTo,
				new ConstantCollectionExpression(reference.getShape(), positive),
				new ConstantCollectionExpression(reference.getShape(), negative));
	}

	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo,
			CollectionExpression positive, CollectionExpression negative) {
		Optional<Boolean> comp = checkPossibleMatch(reference, compareTo);
		if (comp.isEmpty())
			return new ExpressionMatchingCollectionExpression(reference, compareTo, positive, negative);
		return comp.orElseThrow() ? positive : negative;
	}

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
