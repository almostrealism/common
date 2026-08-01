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

/**
 * A {@link TraversalOrdering} that reads physical positions from a {@link TraversableExpression}.
 *
 * <p>Each logical index is passed to the backing expression's {@code getValueAt} and the result
 * is cast to an integer. This allows arbitrary runtime index mappings to be used as traversal
 * orderings, for example when the mapping is stored in a collection that is evaluated lazily.</p>
 */
public class IndexExpressionTraversalOrdering implements TraversalOrdering {
	/** The expression that maps logical indices to physical positions. */
	private final TraversableExpression<?> indices;

	/**
	 * Creates an ordering backed by the given index expression.
	 *
	 * @param indices the expression that supplies physical positions for each logical index
	 */
	public IndexExpressionTraversalOrdering(TraversableExpression<?> indices) {
		this.indices = indices;
	}

	/**
	 * Returns the backing index expression.
	 *
	 * @return the traversable expression supplying index values
	 */
	public TraversableExpression<?> getIndices() {
		return indices;
	}

	/** {@inheritDoc} Returns {@code indices.getValueAt(idx).toInt()}. */
	@Override
	public Expression<Integer> indexOf(Expression<Integer> idx) {
		return indices.getValueAt(idx).toInt();
	}
}
