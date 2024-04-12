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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Delegated;

public interface TraversableExpression<T> extends ExpressionFeatures {

	default Expression<T> getValue(Expression... pos) {
		throw new UnsupportedOperationException();
	}

	Expression<T> getValueAt(Expression index);

	default Expression<T> getValueRelative(Expression index) {
		return getValueAt(index);
	}

	default Expression uniqueNonZeroIndex(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		return null;
	}

	default Expression uniqueNonZeroIndexRelative(Index localIndex, Expression<?> targetIndex) {
		return uniqueNonZeroIndex(new KernelIndex(), localIndex, targetIndex);
	}

	default boolean isTraversable() {
		return true;
	}

	default boolean isRelative() { return false; }

	static TraversableExpression traverse(Object o) {
		if (o instanceof TraversableExpression) {
			if (!((TraversableExpression) o).isTraversable()) return null;
			return (TraversableExpression) o;
		} else if (o instanceof Delegated) {
			return traverse(((Delegated) o).getDelegate());
		} else {
			return null;
		}
	}
}
