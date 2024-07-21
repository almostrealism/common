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

public class SpectrumCaching implements CachingSettings, ConsoleFeatures {
	private final int s;
	private final int d;
	private final int m;

	public SpectrumCaching(double scale) {
		this.s = 4;
		this.d = (int) (scale * 41) + s;
		this.m = (int) (scale * 23);
		log("d = " + d + ", m = " + m);
	}

	@Override
	public boolean isExpressionCacheTarget(Expression<?> expression) {
		int depth = expression.treeDepth();
		if (depth < s || depth > d) return false;

		return (2 * expression.countNodes()) % 41 < m;
	}

	@Override
	public Console console() { return Scope.console; }
}
