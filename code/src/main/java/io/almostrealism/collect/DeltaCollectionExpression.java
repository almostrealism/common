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

public class DeltaCollectionExpression extends CollectionExpressionBase {
	private final CollectionExpression deltaExpression;
	private final CollectionExpression targetExpression;
	private final TraversalPolicy targetShape;
	private final IndexedExpressionMatcher target;

	public DeltaCollectionExpression(CollectionExpression deltaExpression,
									 CollectionExpression targetExpression,
									 TraversalPolicy targetShape,
									 IndexedExpressionMatcher target) {
		if (!targetExpression.getShape().equals(targetShape)) {
			throw new IllegalArgumentException();
		}

		this.deltaExpression = deltaExpression;
		this.targetExpression = targetExpression;
		this.targetShape = targetShape;
		this.target = target;
	}

	@Override
	public TraversalPolicy getShape() {
		return deltaExpression.getShape();
	}

	@Override
	public Expression<Double> getValueAt(Expression index) {
		return deltaExpression.getValueAt(index.divide(targetShape.getTotalSize()))
				.delta(targetExpression)
				.getValueAt(index.imod(targetShape.getTotalSize()));
	}
}
