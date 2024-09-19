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

public class UnaryExpression<T> extends Expression<T> {
	private String operator;

	public UnaryExpression(Class<T> type, String operator, Expression<?> value) {
		super(type, value);
		this.operator = operator;
	}

	protected boolean isIncludeSpace() { return true; }

	@Override
	public String getExpression(LanguageOperations lang) {
		if (isIncludeSpace()) {
			return operator + " " + getChildren().get(0).getWrappedExpression(lang);
		} else {
			return operator + getChildren().get(0).getWrappedExpression(lang);
		}
	}

	@Override
	public boolean equals(Object obj) {
		return super.equals(obj) && ((UnaryExpression) obj).operator.equals(operator);
	}
}
