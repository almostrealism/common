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
import io.almostrealism.expression.IndexedExpressionMatcher;

import java.util.function.Function;

public class IndexMatchingCollectionExpression extends CollectionExpressionBase {
	private final CollectionExpression compare;
	private final CollectionExpression match;
	private final CollectionExpression fallback;
	private final IndexedExpressionMatcher target;

	public IndexMatchingCollectionExpression(CollectionExpression compare,
											 CollectionExpression match,
											 CollectionExpression fallback,
									 		 IndexedExpressionMatcher matcher) {
		this.compare = compare;
		this.match = match;
		this.fallback = fallback;
		this.target = matcher;
	}

	@Override
	public TraversalPolicy getShape() {
		return match.getShape();
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		if (target.matcherForIndex(index).test(compare.getValueAt(index))) {
			return match.getValueAt(index);
		} else {
			return fallback == null ? e(0) : fallback.getValueAt(index);
		}
	}

	public static IndexMatchingCollectionExpression create(TraversalPolicy shape,
														   Function<Expression<?>, Expression<?>> compare,
														   Function<Expression<?>, Expression<?>> match,
														   Function<Expression<?>, Expression<?>> fallback,
														   IndexedExpressionMatcher matcher) {
		return new IndexMatchingCollectionExpression(
				DefaultCollectionExpression.create(shape, compare),
				DefaultCollectionExpression.create(shape, match),
				DefaultCollectionExpression.create(shape, fallback),
				matcher);
	}

	public static IndexMatchingCollectionExpression create(TraversalPolicy shape,
														   Function<Expression<?>, Expression<?>> compare,
														   Function<Expression<?>, Expression<?>> match,
														   CollectionExpression fallback,
														   IndexedExpressionMatcher matcher) {
		return new IndexMatchingCollectionExpression(
				DefaultCollectionExpression.create(shape, compare),
				DefaultCollectionExpression.create(shape, match),
				fallback, matcher);
	}
}
