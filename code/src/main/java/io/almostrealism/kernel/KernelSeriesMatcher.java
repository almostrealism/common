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

package io.almostrealism.kernel;

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.Mask;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.TimingMetric;

import java.util.OptionalInt;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class KernelSeriesMatcher implements ExpressionFeatures {
	public static boolean enableGranularityDetection = true;
	public static TimingMetric timing = Scope.console.timing("kernelSeriesMatcher");

	// TODO  Move into IndexSequence
	public static Expression match(Expression index, IndexSequence seq, boolean isInt) {
		long start = System.nanoTime();

		try {
			if (seq.isConstant()) {
				return isInt ? new IntegerConstant(seq.intAt(0)) : new DoubleConstant(seq.doubleAt(0));
			}

			double distinct[] = seq.doubleStream().distinct().sorted().toArray();
			if (distinct.length == 1) {
				Scope.console.features(KernelSeriesMatcher.class).warn("Constant sequence not detected by IndexSequence");
				return isInt ? new IntegerConstant((int) distinct[0]) : new DoubleConstant(distinct[0]);
			}

			if (distinct.length == 2 && distinct[0] == 0) {
				int first = seq.matchingIndices(distinct[1]).findFirst().orElse(-1);
				if (first < 0) throw new UnsupportedOperationException();

				int tot = seq.doubleStream().mapToInt(v -> v == distinct[1] ? 1 : 0).sum();

				long cont = seq.doubleStream().skip(first).limit(tot).distinct().count();

				Expression<Boolean> condition = null;

				if (tot == 1) {
					condition = index.eq(new IntegerConstant(first));
				} else if (cont == 1) {
					condition =
							index.greaterThanOrEqual(new IntegerConstant(first)).and(
									index.lessThan(new IntegerConstant(first + tot)));
				}

				if (condition != null) {
					if (isInt) {
						return Mask.of(condition, new IntegerConstant((int) distinct[1]));
					} else {
						return Mask.of(condition, new DoubleConstant(distinct[1]));
					}
				}
			}

			int granularity = enableGranularityDetection ? seq.getGranularity() : 1;

			double initial = seq.doubleAt(0);
			double delta = seq.doubleAt(granularity) - seq.doubleAt(0);
			boolean isArithmetic = true;
			for (int i = 2 * granularity; i < seq.getMod(); i += granularity) {
				if (seq.doubleAt(i) - seq.doubleAt(i - 1) != delta) {
					isArithmetic = false;
					break;
				}
			}

			if (isArithmetic) {
				Expression<?> r = index; // new KernelIndex();
				if (granularity > 1) r = r.toInt().divide(new IntegerConstant(granularity));

				if (isInt) {
					if (delta != 1.0) r = r.multiply(new IntegerConstant((int) delta));
					if (initial != 0.0) r = r.add(new IntegerConstant((int) initial));
					if (seq.getMod() != seq.length())
						r = r.imod(seq.getMod());
				} else {
					if (delta != 1.0) r = r.multiply(new DoubleConstant(delta));
					if (initial != 0.0) r = r.add(new DoubleConstant(initial));
					if (seq.getMod() != seq.length())
						r = r.mod(new IntegerConstant(seq.getMod()), true);
				}

				return r;
			}

			return null;
		} finally {
			timing.addEntry(isInt ? "int" : "fp", System.nanoTime() - start);
		}
	}

	public static KernelSeriesProvider defaultProvider(int count) {
		return new KernelSeriesProvider() {
			@Override
			public Expression getSeries(Expression index, Supplier<String> exp, Supplier<IndexSequence> seq, boolean isInt, IntSupplier nodes) {
				return match(index, seq.get(), isInt);
			}

			@Override
			public OptionalInt getMaximumLength() {
				return OptionalInt.of(count);
			}
		};
	}
}
