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
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.util.Arrays;

public class SpectrumSimplification implements SimplificationSettings, ConsoleFeatures {
	private final double scale;
	private final int s;
	private final int j;
	private final int k;
	private final int m;

	public SpectrumSimplification(double scale) {
		this.scale = scale;
		this.s = 40;
		this.j = 13;
		this.k = 41;
		this.m = (int) (scale * (k - 1));
		log("d = " + (scale * s) + " | m = " + m + "/" + k);
	}

	@Override
	public boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		depth = Math.min(s, depth);
		double d = depth / (double) s;
		if (d > scale) return false;

		return (j * expression.countNodes()) % k <= m;
	}

	@Override
	public Console console() { return Scope.console; }
}
