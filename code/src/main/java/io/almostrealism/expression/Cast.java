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

import java.util.List;

public class Cast extends UnaryExpression<Double> {
	private String typeName;

	public Cast(String typeName, Expression<?> operand) {
		super(Double.class, "(" + typeName + ")", operand);
		this.typeName = typeName;
	}

	@Override
	public Expression generate(List children) {
		if (children.size() != 1) throw new UnsupportedOperationException();
		return new Cast(typeName, (Expression) children.get(0));
	}

	@Override
	public String toString() {
		return getExpression();
	}
}
