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
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.kernel.KernelSeriesMatcher;
import io.almostrealism.kernel.KernelSeriesProvider;
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
import java.util.function.Function;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

public class KernelSeriesCache implements KernelSeriesProvider, ExpressionFeatures, ConsoleFeatures {
	public static boolean enableCache = true;
	public static int defaultMaxEntries = 100;

	private int count;
	private boolean fixed;
	private MemoryDataCacheManager cacheManager;

	private Map<String, Integer> cache = new HashMap<>();
	private Base64.Encoder encoder = Base64.getEncoder();

	public KernelSeriesCache(int count, boolean fixed, MemoryDataCacheManager cacheManager) {
		if (cacheManager != null && count != cacheManager.getEntrySize()) {
			throw new IllegalArgumentException();
		}

		this.count = count;
		this.fixed = fixed;
		this.cacheManager = cacheManager;
	}

	@Override
	public Expression getSeries(Expression exp) {
		if (exp instanceof KernelIndex || exp.doubleValue().isPresent()) return exp;
		if (!enableCache || !fixed || !exp.isKernelValue()) return exp;

		boolean isInt = exp.getType() == Integer.class;

		double seq[] = Stream.of(exp.kernelSeq(count)).mapToDouble(Number::doubleValue).toArray();
		double distinct[] = DoubleStream.of(seq).distinct().toArray();
		if (distinct.length == 1)
			return isInt ? e((int) distinct[0]) : e(distinct[0]);

		Expression match = KernelSeriesMatcher.match(exp, count);
		if (match != null) return match;

		String sig = signature(seq);

		if (!cache.containsKey(sig)) {
			if (cache.size() >= cacheManager.getMaxEntries()) {
				warn("Cache is full");
				return exp;
			}

			int index = cache.size();
			cache.put(sig, index);
			cacheManager.setValue(index, seq);
		}

		Expression<?> result = cacheManager.reference(cache.get(sig), kernel());
		return isInt ? result.toInt() : result;
	}

	public String signature(double[] values) {
		ByteBuffer byteBuffer = ByteBuffer.allocate(Double.SIZE / Byte.SIZE * values.length);
		DoubleBuffer doubleBuffer = byteBuffer.asDoubleBuffer();
		doubleBuffer.put(values);
		return encoder.encodeToString(byteBuffer.array());
	}

	@Override
	public Console console() { return Hardware.console; }

	public static KernelSeriesCache create(Computation<?> c, Function<MemoryData, ArrayVariable<?>> variableFactory) {
		int count = ParallelProcess.count(c);
		boolean fixed = ParallelProcess.isFixedCount(c);
		return new KernelSeriesCache(count, fixed,
				fixed ? MemoryDataCacheManager.create(count, defaultMaxEntries, variableFactory) : null);
	}
}
