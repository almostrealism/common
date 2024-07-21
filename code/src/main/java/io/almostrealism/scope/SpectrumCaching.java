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

public class SpectrumCaching implements CachingSettings {
	private final double scale;

	public SpectrumCaching(double scale) {
		this.scale = scale;
	}

	@Override
	public boolean isExpressionCacheTarget(Expression<?> expression) {
		int depth = expression.treeDepth() - 3;
		if (depth < 0 || depth > scale * 100) return false;

		int m = 11 - (int) (scale * 10);
		return expression.countNodes() % m == 0;
	}
}
