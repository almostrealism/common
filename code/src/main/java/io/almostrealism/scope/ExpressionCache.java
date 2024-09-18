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

import io.almostrealism.code.OperationMetadata;
import io.almostrealism.expression.Expression;
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.util.FrequencyCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ExpressionCache {
	private static ThreadLocal<ExpressionCache> current = new ThreadLocal<>();
	private static ThreadLocal<OperationMetadata> currentMetadata = new ThreadLocal<>();

	public static ScopeTimingListener timing;

	private Map<Integer, FrequencyCache<Expression<?>, Expression<?>>> caches;

	public ExpressionCache() {
		caches = new HashMap<>();
	}

	public <T> Expression<T> get(Expression<T> expression) {
		if (!ScopeSettings.isExpressionCacheTarget(expression))
			return expression;

		FrequencyCache<Expression<?>, Expression<?>> cache = getCache(expression.treeDepth());

		Expression e = cache.get(expression);
		if (e == null) {
			cache.put(expression, expression);
			e = expression;
		}

		return e;
	}

	protected FrequencyCache<Expression<?>, Expression<?>> getCache(int depth) {
		return caches.computeIfAbsent(depth,
				k -> new FrequencyCache<>(ScopeSettings.getExpressionCacheSize(), 0.7));
	}

	public List<Expression<?>> getFrequentExpressions() {
		List<Integer> depths = caches.keySet().stream().sorted().collect(Collectors.toList());
		List<Expression<?>> expressions = new ArrayList<>();

		for (Integer d : depths) {
			getCache(d)
					.valuesByFrequency(f -> f > ScopeSettings.getExpressionCacheFrequencyThreshold())
					.forEach(expressions::add);
		}

		Collections.reverse(expressions);
		return expressions;
	}

	public boolean isEmpty() {
		if (caches.isEmpty()) return true;
		return caches.values().stream().allMatch(FrequencyCache::isEmpty);
	}


	public ExpressionCache use(Runnable r) {
		return use(null, r);
	}

	public ExpressionCache use(OperationMetadata metadata, Runnable r) {
		use(metadata, () -> {
			r.run();
			return null;
		});

		return this;
	}

	public <T> T use(Supplier<T> r) {
		return use(null, r);
	}

	public <T> T use(OperationMetadata metadata, Supplier<T> r) {
		OperationMetadata oldMetadata = currentMetadata.get();
		ExpressionCache old = current.get();
		currentMetadata.set(metadata);
		current.set(this);

		try {
			return r.get();
		} finally {
			currentMetadata.set(oldMetadata);
			current.set(old);
		}
	}

	public static <T> Expression<T> match(Expression<T> expression) {
		Supplier<Expression<T>> matcher = () -> {
			ExpressionCache cache = getCurrent();
			if (cache == null) return expression;
			return cache.get(expression);
		};

		if (timing == null) {
			return matcher.get();
		} else {
			return timing.recordDuration(currentMetadata.get(), "expressionCacheMatch", matcher);
		}
	}

	public static ExpressionCache getCurrent() {
		return current.get();
	}
}
