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

import io.almostrealism.code.ExpressionFeatures;
import io.almostrealism.expression.DoubleConstant;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.expression.Mask;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.TimingMetric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

public class KernelSeriesMatcher implements ExpressionFeatures {
	private static List<Expression<?>> commonSeries;

	static {
		commonSeries = new ArrayList<>();
	}

	public static Expression match(double seq[], boolean isInt) {
		long start = System.nanoTime();

		try {
			double distinct[] = DoubleStream.of(seq).distinct().sorted().toArray();
			if (distinct.length == 1)
				return isInt ? new IntegerConstant((int) distinct[0]) : new DoubleConstant(distinct[0]);

			if (distinct.length == 2 && distinct[0] == 0) {
				int first = IntStream.range(0, seq.length).filter(i -> seq[i] == distinct[1]).findFirst().orElse(-1);
				if (first < 0) throw new UnsupportedOperationException();

				int tot = IntStream.range(0, seq.length).map(i -> seq[i] == distinct[1] ? 1 : 0).sum();

				long cont = IntStream.range(0, seq.length)
						.skip(first).limit(tot).mapToDouble(i -> seq[i]).distinct().count();

				if (cont == 1) {
					Expression<Boolean> condition =
							new KernelIndex().greaterThanOrEqual(new IntegerConstant(first)).and(
									new KernelIndex().lessThan(new IntegerConstant(first + tot)));

					if (isInt) {
						return new Mask<>(condition, new IntegerConstant((int) distinct[1]));
					} else {
						return new Mask<>(condition, new DoubleConstant(distinct[1]));
					}
				}
			}

			double initial = seq[0];
			double delta = seq[1] - seq[0];
			boolean isArithmetic = true;
			for (int i = 2; i < seq.length; i++) {
				if (seq[i] - seq[i - 1] != delta) {
					isArithmetic = false;
					break;
				}
			}

			if (isArithmetic) {
				Expression<?> r = new KernelIndex();

				if (isInt) {
					if (delta != 1.0) r = r.multiply(new IntegerConstant((int) delta));
					if (initial != 0.0) r = r.add(new IntegerConstant((int) initial));
				} else {
					if (delta != 1.0) r = r.multiply(new DoubleConstant(delta));
					if (initial != 0.0) r = r.add(new DoubleConstant(initial));
				}

				return r;
			}

			return match(DoubleStream.of(seq).boxed().toArray(Number[]::new));
		} finally {
			KernelSeriesProvider.timing.addEntry("match", System.nanoTime() - start);
		}
	}

	public static Expression match(Number seq[]) {
		for (int i = 0; i < commonSeries.size(); i++) {
			if (kernelEquivalent(seq, commonSeries.get(i).kernelSeq(seq.length), seq.length)) {
				return commonSeries.get(i);
			}
		}

		return null;
	}

	public static Expression match(Expression e, int len) {
		for (int i = 0; i < commonSeries.size(); i++) {
			if (kernelEquivalent(e, commonSeries.get(i), len)) {
				return commonSeries.get(i);
			}
		}

		return null;
	}

	public static Expression simplify(Expression e, int mod) {
		OptionalInt period = e.kernelSeries().getPeriod();
		if (!period.isPresent()) return e;

		for (int i = 0; i < commonSeries.size(); i++) {
			if (kernelEquivalent(e, commonSeries.get(i).imod(mod), period.getAsInt())) {
				return commonSeries.get(i).imod(mod);
			}
		}

		return e;
	}

	public static boolean kernelEquivalent(Expression<?> l, Expression<?> r, int kernelMax) {
		if (!l.isKernelValue()) return false;
		if (!r.isKernelValue()) return false;

		return kernelEquivalent(l.kernelSeq(kernelMax), r.kernelSeq(kernelMax), kernelMax);
	}

	public static boolean kernelEquivalent(Number l[], Number r[], int kernelMax) {
		if (!Arrays.stream(l).allMatch(i -> i instanceof Integer)) return false;
		if (!Arrays.stream(r).allMatch(i -> i instanceof Integer)) return false;

		for (int i = 0; i < kernelMax; i++) {
			if (!l[i].equals(r[i]))
				return false;
		}

		return true;
	}
}
