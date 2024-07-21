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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SpectrumCaching implements CachingSettings, ConsoleFeatures {
	private final List<Integer> depths;

	private final int j;
	private final int k;
	private final int m[];

	public SpectrumCaching(double dScale, double fScale) {
		this.depths = new ArrayList<>();

		int n = (int) (dScale * 10); int d = 4;
		for (int i = 0; i < n; i++) {
			depths.add(3 + d);
			d = (d + 3) % 7;
		}

		this.j = 13;
		this.k = 41;
		this.m = new int[depths.size()];
		double p = depths.size() > 1 ? (1.0 - fScale) / (depths.size() - 1) : 0;
		for (int i = 0; i < depths.size(); i++) {
			m[i] = k - 1 - (int) (p * i * k);
		}

		log("d = " + Arrays.toString(depths.toArray()) +
				" | m = " + Arrays.toString(m) + " (" + fScale + ")");
	}

	@Override
	public boolean isExpressionCacheTarget(Expression<?> expression) {
		int d = depths.indexOf(expression.treeDepth());
		if (d < 0) return false;

		return (j * expression.countNodes()) % k <= m[d];
	}

	@Override
	public Console console() { return Scope.console; }
}
