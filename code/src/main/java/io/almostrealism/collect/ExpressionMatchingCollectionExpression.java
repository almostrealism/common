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
		return conditional(index.eq(this.index), positive.getValueAt(index), negative.getValueAt(index));
	}

	public static CollectionExpression create(CollectionExpression reference,
																CollectionExpression compareTo,
																Expression<?> index,
																Expression<?> positive,
																Expression<?> negative) {
		return create(reference, compareTo, index,
				DefaultCollectionExpression.create(reference.getShape(), idx -> positive),
				DefaultCollectionExpression.create(reference.getShape(), idx -> negative));
	}

	public static CollectionExpression create(CollectionExpression reference, CollectionExpression compareTo, Expression<?> index,
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
		return matcher == null ? false : matcher.apply(referenceProducer, compareToProducer);
	}
}
