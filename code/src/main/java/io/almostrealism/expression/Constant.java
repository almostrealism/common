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

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lang.LanguageOperations;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public class Constant<T> extends Expression<T> {
	public Constant(Class<T> type) {
		super(type);
	}

	@Override
	public String getExpression(LanguageOperations lang) { return null; }

	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	public Constant<T> generate(List<Expression<?>> children) {
		if (children.size() > 0) {
			throw new UnsupportedOperationException();
		}

		return this;
	}

	@Override
	public CollectionExpression delta(TraversalPolicy shape, Function<Expression, Predicate<Expression>> target) {
		return CollectionExpression.create(shape, idx -> new IntegerConstant(0));
	}
}
