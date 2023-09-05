/*
 * Copyright 2021 Michael Murray
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

public class UnaryExpression<T> extends Expression<T> {
	private String operator;

	public UnaryExpression(Class<T> type, String operator, Expression<?> value) {
		super(type, operator + "(" + value.getExpression() + ")", value);
		this.operator = operator;
	}

	@Override
	public String getExpression() {
		return operator + " (" + getChildren().get(0).getExpression() + ")";
	}
}
