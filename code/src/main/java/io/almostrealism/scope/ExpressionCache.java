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

package io.almostrealism.scope;

import io.almostrealism.expression.Expression;
import io.almostrealism.util.FrequencyCache;

import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ExpressionCache {
	private static ThreadLocal<ExpressionCache> current = new ThreadLocal<>();

	private FrequencyCache<String, Expression<?>> cache;

	public ExpressionCache() {
		cache = new FrequencyCache<>(ScopeSettings.getExpressionCacheSize(), 0.7);
	}

	public <T> Expression<T> get(Expression<T> expression) {
		if (!ScopeSettings.isExpressionCacheTarget(expression.treeDepth()))
			return expression;

		String s = expression.signature();
		Expression e = cache.get(s);
		if (e == null) {
			cache.put(s, expression);
			e = expression;
		}

		return e;
	}

	public List<Expression<?>> getFrequentExpressions() {
		List<Expression<?>> expressions = cache
				.valuesByFrequency(f -> f > ScopeSettings.getExpressionCacheFrequencyThreshold())
				.collect(Collectors.toList());
		Collections.reverse(expressions);
		return expressions;
	}

	public boolean isEmpty() { return cache.isEmpty(); }

	public ExpressionCache use(Runnable r) {
		ExpressionCache old = current.get();
		current.set(this);

		try {
			r.run();
		} finally {
			current.set(old);
		}

		return this;
	}

	public <T> T use(Supplier<T> r) {
		ExpressionCache old = current.get();
		current.set(this);

		try {
			return r.get();
		} finally {
			current.set(old);
		}
	}

	public static <T> Expression<T> match(Expression<T> expression) {
		ExpressionCache cache = getCurrent();
		if (cache == null) return expression;
		return cache.get(expression);
	}

	public static ExpressionCache getCurrent() {
		return current.get();
	}
}
