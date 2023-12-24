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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.lang.LanguageOperationsStub;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.TimingMetric;

import java.util.OptionalInt;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public interface KernelSeriesProvider {
	TimingMetric timing = Scope.console.timing("kernelSeries");

	default Expression getSeries(Expression exp) {
		if (exp instanceof KernelIndex || exp.doubleValue().isPresent() || !exp.isKernelValue()) return exp;

		OptionalInt len = getMaximumLength();
		if (!len.isPresent()) return exp;

		long start = System.nanoTime();

		Expression result = null;

		try {
			result = getSeries(
					Stream.of(exp.kernelSeq(len.getAsInt())).mapToDouble(Number::doubleValue).toArray(),
					exp.getType() == Integer.class);
			return result == null ? exp : result;
		} finally {
			timing.addEntry(exp.countNodes() + "-" + (result != null), System.nanoTime() - start);
		}
	}

	default Expression getSeries(int values[]) {
		return getSeries(IntStream.of(values).mapToDouble(i -> i).toArray(), true);
	}

	default Expression getSeries(double values[]) {
		return getSeries(values, false);
	}

	Expression getSeries(double values[], boolean isInt);

	OptionalInt getMaximumLength();
}
