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

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

public class KernelSeriesMatcher {
	private static List<Expression<?>> commonSeries;

	static {
		commonSeries = new ArrayList<>();
		commonSeries.add(new KernelIndex());
	}

	public static Expression match(Expression e, int len) {
		for (int i = 0; i < commonSeries.size(); i++) {
			if (e.kernelEquivalent(commonSeries.get(i), len)) {
				return commonSeries.get(i);
			}
		}

		return null;
	}

	public static Expression simplify(Expression e, int mod) {
		OptionalInt period = e.kernelSeries().getPeriod();
		if (!period.isPresent()) return e;

		for (int i = 0; i < commonSeries.size(); i++) {
			if (e.kernelEquivalent(commonSeries.get(i).imod(mod), period.getAsInt())) {
				return commonSeries.get(i).imod(mod);
			}
		}

		return e;
	}
}
