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
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.util.FrequencyCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Thread-local cache for expression deduplication during compilation.
 *
 * <p>{@code ExpressionCache} identifies and reuses equivalent expressions during
 * code generation to reduce expression tree size and improve compilation performance.
 * It uses a frequency-based caching strategy organized by expression tree depth.</p>
 *
 * <h2>Purpose</h2>
 * <p>During compilation, the same sub-expressions often appear multiple times. Without
 * caching, each occurrence creates a separate node in the expression tree, leading to:
 * <ul>
 *   <li>Exponentially growing expression trees</li>
 *   <li>Redundant computations in generated code</li>
 *   <li>Longer compilation times</li>
 *   <li>Larger generated kernels</li>
 * </ul>
 * </p>
 *
 * <p>{@code ExpressionCache} solves this by returning a cached reference when an
 * equivalent expression is encountered, ensuring each unique expression exists only once.</p>
 *
 * <h2>Usage Pattern</h2>
 * <p>The cache is thread-local and must be activated via {@link #use(Runnable)} or
 * {@link #use(Supplier)}:</p>
 * <pre>{@code
 * ExpressionCache cache = new ExpressionCache();
 *
 * // Activate cache for a scope of work
 * cache.use(() -> {
 *     // All expression matching within this scope uses this cache
 *     Expression<?> result = computeSomething();
 *     return result;
 * });
 *
 * // Static lookup (uses current thread's cache)
 * Expression<?> deduped = ExpressionCache.match(expression);
 * }</pre>
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>Expressions are grouped by tree depth to optimize lookup performance</li>
 *   <li>{@link FrequencyCache} tracks usage frequency for cache eviction</li>
 *   <li>{@link ScopeSettings#isExpressionCacheTarget(Expression)} controls which expressions are cached</li>
 *   <li>{@link #getFrequentExpressions()} returns commonly occurring expressions (useful for optimization analysis)</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Each thread has its own cache via {@link ThreadLocal}. The cache is automatically
 * scoped to the current compilation unit via {@link #use(Runnable)}.</p>
 *
 * @see ScopeSettings#isExpressionCacheTarget(Expression)
 * @see ScopeSettings#getExpressionCacheSize()
 * @see FrequencyCache
 * @author Michael Murray
 */
public class ExpressionCache {
	private static ThreadLocal<ExpressionCache> current = new ThreadLocal<>();
	private static ThreadLocal<OperationMetadata> currentMetadata = new ThreadLocal<>();

	private Map<Integer, FrequencyCache<Expression<?>, Expression<?>>> caches;

	public ExpressionCache() {
		caches = new HashMap<>();
	}

	/** Performs the get operation. */
	public <T> Expression<T> get(Expression<T> expression) {
		if (!ScopeSettings.isExpressionCacheTarget(expression))
			return expression;

		Supplier<Expression<T>> lookup = () -> {
			FrequencyCache<Expression<?>, Expression<?>> cache = getCache(expression.treeDepth());

			Expression e = cache.get(expression);
			if (e == null) {
				cache.put(expression, expression);
				e = expression;
			}

			return e;
		};

		if (Expression.timing == null) {
			return lookup.get();
		} else {
			String title = "expressionCacheMatch_" + expression.treeDepth() +
					"_" + expression.countNodes() +
					"_" + expression.getClass().getSimpleName();
			return Expression.timing.recordDuration(currentMetadata.get(), title, lookup);
		}
	}

	/** Performs the getCache operation. */
	protected FrequencyCache<Expression<?>, Expression<?>> getCache(int depth) {
		return caches.computeIfAbsent(depth,
				k -> new FrequencyCache<>(ScopeSettings.getExpressionCacheSize(), 0.7));
	}

	/** Performs the getFrequentExpressions operation. */
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

	/** Performs the isEmpty operation. */
	public boolean isEmpty() {
		if (caches.isEmpty()) return true;
		return caches.values().stream().allMatch(FrequencyCache::isEmpty);
	}


	/** Performs the use operation. */
	public ExpressionCache use(Runnable r) {
		return use(null, r);
	}

	/** Performs the use operation. */
	public ExpressionCache use(OperationMetadata metadata, Runnable r) {
		use(metadata, () -> {
			r.run();
			return null;
		});

		return this;
	}

	/** Performs the use operation. */
	public <T> T use(Supplier<T> r) {
		return use(null, r);
	}

	/** Performs the use operation. */
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

	/** Performs the match operation. */
	public static <T> Expression<T> match(Expression<T> expression) {
		ExpressionCache cache = getCurrent();
		if (cache == null) return expression;

		return cache.get(expression);
	}

	public static ExpressionCache getCurrent() {
		return current.get();
	}
}
