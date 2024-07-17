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
import io.almostrealism.profile.ScopeTimingListener;
import io.almostrealism.relation.ParallelProcess;

public class ScopeSettings {
	public static final boolean enableReplacements = true;

	public static int maxKernelSeriesCount = ParallelProcess.maxCount << 2;
	public static int sequenceComputationLimit = maxKernelSeriesCount;

	public static ScopeTimingListener timing;

	public static boolean strict = false;

	private static int tier0 = 2;
	private static int tier1 = strict ? 8 : 12;
	private static int tier2 = strict ? 16 : 24;

	private static int pref0 = 1;
	private static int pref1 = 2;
//	private static int pref2 = 3;
//	private static int pref3 = 4;
	private static int pref2 = 4;
	private static int pref3 = 7;

	public static boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		if (expression.getType() == Boolean.class) return true;

		if (depth < tier0) {
			return true;
		} else if (depth < tier1) {
			return expression.containsLong() || expression.countNodes() > 50 ||
					targetByDepth(expression.treeDepth(), pref1);
		} else if (depth < tier2) {
			return expression.containsLong() || expression.countNodes() > 75 ||
					targetByDepth(expression.treeDepth(), pref2);
		} else {
			return expression.countNodes() > 100 ||
					targetByDepth(expression.treeDepth(), pref3);
		}
	}

	public static boolean targetByDepth(int depth, int preference) {
		return depth > (preference + 3) && depth % preference == 0;
	}

	public static int getExpressionCacheSize() { return 150; }

	public static int getExpressionCacheFrequencyThreshold() { return 10; }

	public static boolean isExpressionCacheTarget(int depth) {
		return depth == 3 || depth == 7 || depth == 9 || depth == 11;
	}

	public static int getMaximumReplacements() {
		return 12;
	}
}
