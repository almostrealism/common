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

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class ExpressionMatchingCollectionExpression extends CollectionExpressionBase {
	public static BiFunction<Supplier, Supplier, Boolean> matcher;

	private CollectionExpression reference;
	private CollectionExpression compareTo;
	private Expression<?> index;
	private CollectionExpression positive;
	private CollectionExpression negative;

	public ExpressionMatchingCollectionExpression(CollectionExpression reference,
												  CollectionExpression compareTo,
												  Expression<?> index,
												  CollectionExpression positive,
												  CollectionExpression negative) {
		this.reference = reference;
		this.compareTo = compareTo;
		this.index = index;
		this.positive = positive;
		this.negative = negative;
	}

	@Override
	public TraversalPolicy getShape() {
		return reference.getShape();
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (this.index == null) {
			Expression<Boolean> comparison = compareExpressions(reference.getValueAt(index), compareTo.getValueAt(index));
			return conditional(comparison, positive.getValueAt(index), negative.getValueAt(index));
		} else {
			return conditional(index.eq(this.index), positive.getValueAt(index), negative.getValueAt(index));
		}
	}

	public static Expression<Boolean> compareExpressions(Expression<?> a, Expression<?> b) {
		if (a == null && b == null) return new BooleanConstant(true);
		if (a == null || b == null) return new BooleanConstant(false);
		if (a.getClass() != b.getClass()) return new BooleanConstant(false);

		if (a instanceof InstanceReference) {
			InstanceReference ra = (InstanceReference) a;
			InstanceReference rb = (InstanceReference) b;
			if (Objects.equals(ra.getReferent().getName(), rb.getReferent().getName())) {
				return ra.getIndex().eq(rb.getIndex());
			}
		} else if (a.getChildren().size() == b.getChildren().size()) {
			if (a.getChildren().isEmpty()) return a.eq(b);

			Expression<Boolean> comparisons[] = new Expression[a.getChildren().size()];

			for (int i = 0; i < a.getChildren().size(); i++) {
				comparisons[i] = compareExpressions(a.getChildren().get(i), b.getChildren().get(i));
			}

			return new Conjunction(comparisons);
		}

		return new BooleanConstant(false);
	}

	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo, Expression<?> index,
			Expression<?> positive, Expression<?> negative) {
		return create(reference, compareTo, index,
				DefaultCollectionExpression.create(reference.getShape(), idx -> positive),
				DefaultCollectionExpression.create(reference.getShape(), idx -> negative));
	}

	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo,
			Expression<?> positive, Expression<?> negative) {
		return create(reference, compareTo,
				DefaultCollectionExpression.create(reference.getShape(), idx -> positive),
				DefaultCollectionExpression.create(reference.getShape(), idx -> negative));
	}

	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo,
			CollectionExpression positive, CollectionExpression negative) {
		return create(reference, compareTo, null, positive, negative);
	}

	public static CollectionExpression create(
			CollectionExpression reference, CollectionExpression compareTo, Expression<?> index,
			CollectionExpression positive, CollectionExpression negative) {
		if (checkPossibleMatch(reference, compareTo)) {
			return new ExpressionMatchingCollectionExpression(reference, compareTo, index, positive, negative);
		} else {
			return negative;
		}
	}

	protected static boolean checkPossibleMatch(CollectionExpression reference, CollectionExpression compareTo) {
		Supplier referenceProducer = reference instanceof CollectionVariable ? ((CollectionVariable) reference).getProducer() : null;
		Supplier compareToProducer = compareTo instanceof CollectionVariable ? ((CollectionVariable) compareTo).getProducer() : null;
		if (matcher == null || referenceProducer == null || compareToProducer == null) return true;
		return matcher.apply(referenceProducer, compareToProducer);
	}
}
