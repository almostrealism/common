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
import org.almostrealism.io.SystemUtils;

import java.util.Objects;

public class ScopeSettings {
	public static final boolean enableReplacements = true;

	public static boolean enableExpressionWarnings =
			SystemUtils.isEnabled("AR_EXPRESSION_WARNINGS").orElse(true);

	public static long simplificationCount = 0;
	public static long cacheCount = 0;

	public static int maxKernelSeriesCount = ParallelProcess.maxCount << 2;
	public static int sequenceComputationLimit = maxKernelSeriesCount;

	public static ScopeTimingListener timing;

	private static SimplificationSettings simplification;
	private static CachingSettings caching;

	static {
		String sd = "1.0";
		String simplify = SystemUtils.getProperty("AR_SCOPE_SIMPLIFICATION", sd);

		if (simplify.equalsIgnoreCase("tiered")) {
			simplification = new TieredSimplificationSettings();
		} else {
			if (!Objects.equals(sd, simplify))
				System.out.println("SpectrumSimplification[" + simplify + "]");

			simplification = new SpectrumSimplification(Double.parseDouble(simplify));
		}

		String cd = "0.2:0.2";
		String cache = SystemUtils.getProperty("AR_SCOPE_CACHING", cd);

		if (cache.equalsIgnoreCase("explicit")) {
			caching = new ExplicitDepthCaching();
		} else {
			if (!Objects.equals(cd, cache))
				System.out.println("SpectrumCaching[" + cache + "]");

			String c[] = cache.split(":");
			caching = new SpectrumCaching(Double.parseDouble(c[0]), Double.parseDouble(c[1]));
		}
	}

	public static boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		boolean s = simplification.isSeriesSimplificationTarget(expression, depth);
		if (s) {
			simplificationCount++;
		}

		return s;
	}

	public static boolean isDeepSimplification() {
		return false;
	}

	public static int getExpressionCacheSize() { return 300; }

	public static int getExpressionCacheFrequencyThreshold() { return 10; }

	public static boolean isExpressionCacheTarget(Expression<?> expression) {
		boolean c = caching.isExpressionCacheTarget(expression);
		if (c) {
			cacheCount++;
		}
		return c;
	}

	public static int getMaximumReplacements() {
		return 12;
	}

	public static void printStats() {
		Scope.console.features(ScopeSettings.class)
				.log("Simplification Count = " + simplificationCount +
						" | Cache Count = " + cacheCount);
	}

	public static String shortDesc() {
		return caching.shortDesc() + "_" + simplification.shortDesc();
	}
}
