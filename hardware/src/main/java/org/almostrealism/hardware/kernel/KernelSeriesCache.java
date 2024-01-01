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


package org.almostrealism.hardware.kernel;

import io.almostrealism.code.Computation;
import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelSeriesMatcher;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.scope.ArrayVariable;
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
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class KernelSeriesCache implements KernelSeriesProvider, ExpressionFeatures, ConsoleFeatures {
	public static boolean enableCache = true;
	public static boolean enableVerbose = false;
	public static int maxCount = (int) Math.pow(2, 23);
	public static int defaultMaxEntries = 16;

	private int count;
	private boolean fixed;
	private MemoryDataCacheManager cacheManager;
	private LanguageOperations lang;

	private Map<String, Integer> cache;
	private Map<String, Expression> expressions;
	private Base64.Encoder encoder = Base64.getEncoder();

	public KernelSeriesCache(int count, boolean fixed, MemoryDataCacheManager cacheManager) {
		if (cacheManager != null && count != cacheManager.getEntrySize()) {
			throw new IllegalArgumentException();
		}

		this.count = count;
		this.fixed = fixed;
		this.cacheManager = cacheManager;
		this.lang = new LanguageOperationsStub();
		this.cache = cacheManager == null ? null : new HashMap<>();
		this.expressions = new HashMap<>();
	}

	@Override
	public OptionalInt getMaximumLength() {
		return fixed ? OptionalInt.of(count) : OptionalInt.empty();
	}

	@Override
	public Expression getSeries(Expression exp) {
		if (exp.isSingleIndexMasked()) {
			return exp;
		}

		String e = exp.getExpression(lang);
		Expression result = expressions.get(e);
		if (result != null) return result;

		result = KernelSeriesProvider.super.getSeries(exp);
		expressions.put(e, result);
		return result;
	}

	@Override
	public Expression getSeries(double[] seq, boolean isInt) {
		double init = seq[0];
		if (init != 0.0) {
			seq = DoubleStream.of(seq).map(d -> d - init).toArray();
		}

		Expression result = null;

		r: try {
			result = KernelSeriesMatcher.match(seq, isInt);
			if (result != null || !enableCache || cache == null) break r;

			String sig = signature(seq);

			if (!cache.containsKey(sig)) {
				if (cache.size() >= cacheManager.getMaxEntries()) {
					if (enableVerbose)
						warn("Cache is full");
					return null;
				}

				int index = cache.size();
				cache.put(sig, index);
				cacheManager.setValue(index, seq);
			}

			result = cacheManager.reference(cache.get(sig), kernel());
		} finally {
			if (result != null) {
				result = result.add(new DoubleConstant(init));
				if (isInt) result = result.toInt();
			}
		}

		return result;
	}

	public String signature(double[] values) {
		long start = System.nanoTime();

		try {
			ByteBuffer byteBuffer = ByteBuffer.allocate(Double.SIZE / Byte.SIZE * values.length);
			DoubleBuffer doubleBuffer = byteBuffer.asDoubleBuffer();
			doubleBuffer.put(values);
			return encoder.encodeToString(byteBuffer.array());
		} finally {
			KernelSeriesProvider.timing.addEntry("signature", System.nanoTime() - start);
		}
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
