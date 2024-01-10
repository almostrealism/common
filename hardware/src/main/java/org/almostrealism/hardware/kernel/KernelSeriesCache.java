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
import io.almostrealism.expression.KernelIndexChild;
import io.almostrealism.kernel.IndexSequence;
import io.almostrealism.kernel.KernelSeriesMatcher;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.util.FrequencyCache;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryDataCacheManager;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.DoubleStream;

public class KernelSeriesCache implements KernelSeriesProvider, ExpressionFeatures, ConsoleFeatures {
	public static boolean enableCache = true;
	public static boolean enableVerbose = false;
	public static int maxCount = ParallelProcess.maxCount << 6;
	public static int defaultMaxExpressions = 16;
	public static int defaultMaxEntries = 16;
	public static int minNodeCount = 128;

	private int count;
	private boolean fixed;
	private MemoryDataCacheManager cacheManager;
	private LanguageOperations lang;

	private Map<String, Integer> cache;
	private FrequencyCache<String, Expression> expressions;

	public KernelSeriesCache(int count, boolean fixed, MemoryDataCacheManager cacheManager) {
		if (cacheManager != null && count != cacheManager.getEntrySize()) {
			throw new IllegalArgumentException();
		}

		this.count = count;
		this.fixed = fixed;
		this.cacheManager = cacheManager;
		this.lang = new LanguageOperationsStub();
		this.cache = cacheManager == null ? null : new HashMap<>();
		this.expressions = new FrequencyCache<>(defaultMaxExpressions, 0.7);
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
		Expression result = expressions.get(e);
		if (result != null) return result;

		result = KernelSeriesProvider.super.getSeries(exp, index);
		expressions.put(e, result);
		return result;
	}

	@Override
	public Expression getSeries(Expression index, IndexSequence seq, boolean isInt, IntSupplier nodes) {
		Expression result = KernelSeriesMatcher.match(index, seq, isInt);
		if (result != null || !enableCache || cache == null || nodes.getAsInt() < minNodeCount) {
			return result;
		}

		if (seq.length() != count) {
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
			if (result != null) {
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
