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

import io.almostrealism.relation.ParallelProcess;

public class ScopeSettings {
	public static final boolean enableReplacements = true;

	public static int maxKernelSeriesCount = ParallelProcess.maxCount << 2;
	public static int sequenceComputationLimit = maxKernelSeriesCount;

	public static boolean isSeriesSimplificationTarget(
			Class<?> type, int depth, int nodeCount, boolean containsLong) {
		return
				containsLong ||
				type == Boolean.class ||
				(depth > 5 && depth % 2 == 0) ||
				nodeCount > 100;
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
