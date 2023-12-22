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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.DoubleStream;

public class KernelSeriesMatcher implements ExpressionFeatures {
	private static List<Expression<?>> commonSeries;

	static {
		commonSeries = new ArrayList<>();
		commonSeries.add(new KernelIndex());
	}

	public static Expression match(double seq[], boolean isInt) {
		double distinct[] = DoubleStream.of(seq).distinct().toArray();
		if (distinct.length == 1)
			return isInt ? new IntegerConstant((int) distinct[0]) : new DoubleConstant(distinct[0]);

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
			if (isInt) {
				return new KernelIndex().multiply(new IntegerConstant((int) delta)).add(new IntegerConstant((int) initial));
			} else {
				return new KernelIndex().multiply(new DoubleConstant(delta)).add(new DoubleConstant(initial));
			}
		}

		return match(DoubleStream.of(seq).boxed().toArray(Number[]::new));
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
