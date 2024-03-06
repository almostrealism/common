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
import java.util.function.Predicate;

public class DeltaCollectionExpression extends CollectionExpressionBase {
	private final CollectionExpression exp;
	private final TraversalPolicy targetShape;
	private final IndexedExpressionMatcher target;

	public DeltaCollectionExpression(CollectionExpression exp, TraversalPolicy targetShape,
									 IndexedExpressionMatcher target) {
		this.exp = exp;
		this.targetShape = targetShape;
		this.target = target;
	}

	@Override
	public TraversalPolicy getShape() {
		return exp.getShape();
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return exp.getValueAt(index.divide(targetShape.getTotalSize()))
				.delta(targetShape, target)
				.getValueAt(index.imod(targetShape.getTotalSize()));
	}
}
