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

package io.almostrealism.expression;

import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public class Less extends Expression<Boolean> {
	private boolean orEqual;

	public Less(Expression<?> left, Expression<?> right) {
		this(left, right, false);
	}

	public Less(Expression<?> left, Expression<?> right, boolean orEqual) {
		super(Boolean.class, left, right);
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
	public Expression<Boolean> simplify() {
		Expression<?> left = getChildren().get(0).simplify();
		Expression<?> right = getChildren().get(1).simplify();

		OptionalInt li = left.intValue();
		OptionalInt ri = right.intValue();
		if (li.isPresent() && ri.isPresent()) {
			if (orEqual) {
				return new BooleanConstant(li.getAsInt() <= ri.getAsInt());
			} else {
				return new BooleanConstant(li.getAsInt() < ri.getAsInt());
			}
		}

		OptionalDouble ld = left.doubleValue();
		OptionalDouble rd = right.doubleValue();
		if (ld.isPresent() && rd.isPresent()) {
			if (orEqual) {
				return new BooleanConstant(ld.getAsDouble() <= rd.getAsDouble());
			} else {
				return new BooleanConstant(ld.getAsDouble() < rd.getAsDouble());
			}
		}

		return new Less(left, right);
	}

	@Override
	public Expression<Boolean> generate(List<Expression<?>> children) {
		if (children.size() != 2) throw new UnsupportedOperationException();
		return new Less(children.get(0), children.get(1), orEqual);
	}
}
