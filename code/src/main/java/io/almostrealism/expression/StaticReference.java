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

import io.almostrealism.scope.Variable;

import java.util.Collections;
import java.util.List;

public class StaticReference<T> extends Expression<T> {
	public StaticReference(Class<T> type, String expression) {
		super(type, expression, Collections.emptyList());
	}

	@Override
	public Expression<T> generate(List<Expression<?>> children) {
		if (children.size() > 0) throw new UnsupportedOperationException();
		return this;
	}

	@Override
	public String toString() {
		return getExpression();
	}
}
