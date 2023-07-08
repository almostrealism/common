/*
 * Copyright 2023 Michael Murray
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
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Variable;

import java.util.function.IntFunction;

public interface TraversableExpression<T> extends ExpressionFeatures {
	default IntFunction<Variable<T, ?>> getAssignmentFunction(Variable<?, ?> outputVariable) {
		return i -> new Variable(((ArrayVariable) outputVariable).valueAt(i).getSimpleExpression(),
				false, getValueRelative(new IntegerConstant(i)).getSimplified(), outputVariable.getRootDelegate());
	}

	default Expression<T> getValue(PositionExpression pos) {
		return getValue(pos.toArray());
	}

	Expression<T> getValue(Expression... pos);

	Expression<T> getValueAt(Expression index);

	default Expression<T> getValueRelative(Expression index) {
		return getValueAt(index);
	}

	default boolean isTraversable() {
		return true;
	}

	static TraversableExpression traverse(Object o) {
		if (!(o instanceof TraversableExpression)) return null;
		if (!((TraversableExpression) o).isTraversable()) return null;
		return (TraversableExpression) o;
	}
}
