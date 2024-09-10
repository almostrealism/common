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
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.ExpressionMatrix;
import io.almostrealism.kernel.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelIndex;
import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Delegated;
import io.almostrealism.scope.ScopeSettings;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.Describable;

public interface TraversableExpression<T> extends Computable, ExpressionFeatures, ConsoleFeatures {

	default Expression<T> getValue(Expression... pos) {
		throw new UnsupportedOperationException();
	}

	Expression<T> getValueAt(Expression index);

	default Expression<T> getValueRelative(Expression index) {
		return getValueAt(index);
	}

	default Expression uniqueNonZeroOffset(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		if (localIndex.getLimit().orElse(-1) == 1)
			return new IntegerConstant(0);

		ExpressionMatrix<?> indices = ExpressionMatrix.create(globalIndex, localIndex, targetIndex);
		if (indices == null) {
			if (ScopeSettings.enableExpressionWarnings)
				warn("Unable to create ExpressionMatrix for " + targetIndex.getExpressionSummary());
			return null;
		}

		IndexSequence columnSeq = indices.columnSequence();
		if (columnSeq != null) {
			return columnSeq.getExpression(globalIndex);
		}

		Expression<?> column[] = indices.allColumnsMatch();
		if (column != null) {
			// TODO
			throw new RuntimeException("localIndex is irrelevant");
		}

		if (getValueAt(CollectionExpressionAdapter.generateTemporaryIndex()) instanceof InstanceReference) {
			return null;
		}

		ExpressionMatrix<T> values = indices.apply(this::getValueAt);
		if (values == null) {
			if (ScopeSettings.enableExpressionWarnings)
				warn("Unable to create ExpressionMatrix for " + getClass().getSimpleName());
			return null;
		}

		return values.uniqueNonZeroOffset(globalIndex);
	}

	default Expression uniqueNonZeroIndex(Index globalIndex, Index localIndex, Expression<?> targetIndex) {
		Expression offset = uniqueNonZeroOffset(globalIndex, localIndex, targetIndex);
		if (offset == null) return null;

		return ((Expression) globalIndex)
				.multiply(Math.toIntExact(localIndex.getLimit().getAsLong()))
				.add(offset);
	}

	default Expression uniqueNonZeroIndexRelative(Index localIndex, Expression<?> targetIndex) {
		return uniqueNonZeroIndex(new KernelIndex(), localIndex, targetIndex);
	}

	default boolean isIndexIndependent() { return false; }

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

	static boolean match(TraversableExpression<?> a, TraversableExpression<?> b) {
		while (a instanceof RelativeTraversableExpression) {
			a = ((RelativeTraversableExpression) a).getExpression();
		}

		while (b instanceof RelativeTraversableExpression) {
			b = ((RelativeTraversableExpression) b).getExpression();
		}

		if (a == b) return true;
		if (a == null || b == null) return false;
		return a.equals(b);
	}
}
