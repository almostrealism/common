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

package io.almostrealism.code;

import io.almostrealism.expression.Conditional;
import io.almostrealism.expression.Equals;
import io.almostrealism.expression.Exp;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.NAryExpression;
import io.almostrealism.scope.Variable;

import java.util.Collections;

public interface ExpressionFeatures {

	default Expression e(int value) {
		return new IntegerConstant(value);
	}


	default Expression<Double> e(String expression, Expression<?>... dependencies) {
		return new Expression<>(Double.class, expression, dependencies);
	}

	@Deprecated
	default Expression<Double> expression(String expression, Variable<?, ?>... dependencies) {
		return new Expression<>(Double.class, expression, Collections.emptyList(), dependencies);
	}

	default Exp exp(Expression expression) {
		return new Exp(expression);
	}

	default NAryExpression<Boolean> greater(Expression<?> left, Expression<?> right, boolean includeEqual) {
		return new NAryExpression<>(Boolean.class, includeEqual ? ">=" : ">", left, right);
	}

	default Equals equals(Expression<?> left, Expression<?> right) {
		return new Equals(left, right);
	}

	default Conditional conditional(Expression<Boolean> condition, Expression<Double> positive, Expression<Double> negative) {
		return new Conditional(condition, positive, negative);
	}
}
