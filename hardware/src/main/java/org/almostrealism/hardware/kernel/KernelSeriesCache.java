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

package org.almostrealism.hardware.kernel;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Index;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelSeriesMatcher;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataCacheManager;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.io.SystemUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class KernelSeriesCache implements KernelSeriesProvider, ExpressionFeatures, ConsoleFeatures {
	public static boolean enableCache = SystemUtils.isEnabled("AR_HARDWARE_KERNEL_CACHE").orElse(true);
	public static boolean enableVerbose = false;
	public static int maxCount = ParallelProcess.maxCount << 6;
	public static int defaultMaxExpressions = 16;
	public static int defaultMaxEntries = 16;
	public static int minNodeCountMatch = 6;
	public static int minNodeCountCache = 128;

	private int count;
	private boolean fixed;
	private MemoryDataCacheManager cacheManager;

	private Map<String, Integer> cache;
	private FrequencyCache<String, Expression> expressions;
	private Set<String> matchFailures;

	public KernelSeriesCache(int count, boolean fixed, MemoryDataCacheManager cacheManager) {
		if (cacheManager != null && count != cacheManager.getEntrySize()) {
			throw new IllegalArgumentException();
		}

		this.count = count;
		this.fixed = fixed;
		this.cacheManager = cacheManager;
		this.cache = cacheManager == null ? null : new HashMap<>();
		this.expressions = new FrequencyCache<>(defaultMaxExpressions, 0.7);
		this.matchFailures = new TreeSet<>();
	}

	public boolean isComputable() { return fixed && count <= maxCount; }

	@Override
	public OptionalInt getMaximumLength() {
		return fixed ? OptionalInt.of(count) : OptionalInt.empty();
	}

	@Override
	public Expression getSeries(Expression exp, Index index) {
		if (!isComputable() || exp.isSingleIndexMasked()) {
			return exp;
		}

		String e = exp.getExpression(lang);
		if (matchFailures.contains(e)) return exp;

		Expression result = expressions.get(e);
		if (result != null) return result;

		result = KernelSeriesProvider.super.getSeries(exp, index);
		if (result == exp) {
//			if (exp.countNodes() >= minNodeCountMatch)
//				throw new RuntimeException();
		} else {
			expressions.put(e, result);
		}

		return result;
	}

	@Override
	public Expression getSeries(Expression index, Supplier<String> exp,
								Supplier<IndexSequence> sequence,
								boolean isInt, IntSupplier nodes) {
		int n = nodes.getAsInt();
		if (n < minNodeCountMatch) return null;

		IndexSequence seq = sequence.get();

		Expression result = KernelSeriesMatcher.match(index, seq, isInt);
		if (result != null) return result;

		if (!enableCache || cache == null || n < minNodeCountCache) {
			matchFailures.add(exp.get());
			return result;
		}

		if (seq.length() != count) {
			matchFailures.add(exp.get());
			if (enableVerbose)
				warn("Cannot cache sequence of length " + seq.length() + " (length != " + count + ")");
			return result;
		}

		double init = seq.doubleAt(0);
		if (init != 0.0) {
			seq = seq.mapDouble(d -> d - init);
		}

		r: try {
			String sig = seq.signature();

			if (!cache.containsKey(sig)) {
				if (cache.size() >= cacheManager.getMaxEntries()) {
					if (enableVerbose)
						warn("Cache is full");
					break r;
				}

				int idx = cache.size();
				cache.put(sig, idx);
				cacheManager.setValue(idx, seq.doubleStream().toArray());
			}

			result = cacheManager.reference(cache.get(sig), index);
		} finally {
			if (result == null) {
				matchFailures.add(exp.get());
			} else {
				result = result.add(new DoubleConstant(init));
				if (isInt) result = result.toInt();
			}
		}

		return result;
	}

	@Override
	public Console console() { return Hardware.console; }

	public static KernelSeriesCache create(Computation<?> c, Function<MemoryData, ArrayVariable<?>> variableFactory) {
		int count = ParallelProcess.count(c);
		boolean fixed = ParallelProcess.isFixedCount(c);
		return new KernelSeriesCache(count, fixed,
				(enableCache && fixed && count < maxCount) ?
						MemoryDataCacheManager.create(count, defaultMaxEntries, variableFactory) : null);
	}
}
