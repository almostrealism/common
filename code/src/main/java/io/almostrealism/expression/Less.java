/*
 * Copyright 2025 Michael Murray
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

package io.almostrealism.expression;

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;

public class Less extends Comparison {
	private boolean orEqual;

	public Less(Expression<?> left, Expression<?> right) {
		this(left, right, false);
	}

	public Less(Expression<?> left, Expression<?> right, boolean orEqual) {
		super(left, right);
		this.orEqual = orEqual;
	}

	@Override
	public String getExpression(LanguageOperations lang) {
		if (orEqual) {
			return getChildren().get(0).getWrappedExpression(lang) + " <= " + getChildren().get(1).getWrappedExpression(lang);
		} else{
			return getChildren().get(0).getWrappedExpression(lang) + " < " + getChildren().get(1).getWrappedExpression(lang);
		}
	}

	@Override
	protected boolean compare(Number left, Number right) {
		return orEqual ?
				(left.doubleValue() <= right.doubleValue()) :
				(left.doubleValue() < right.doubleValue());
	}

	@Override
	public Expression<Boolean> recreate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return new Less(children.get(0), children.get(1), orEqual);
	}

	public static Expression<Boolean> of(Expression<?> left, Expression<?> right) {
		return Less.of(left, right, false);
	}

	public static Expression<Boolean> of(Expression<?> left, Expression<?> right, boolean orEqual) {
		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();

		if (ld.isPresent() && rd.isPresent()) {
			if (orEqual) {
				return ld.getAsDouble() <= rd.getAsDouble() ? new BooleanConstant(true) : new BooleanConstant(false);
			} else {
				return ld.getAsDouble() < rd.getAsDouble() ? new BooleanConstant(true) : new BooleanConstant(false);
			}
		}


		return new Less(left, right, orEqual);
	}
}
