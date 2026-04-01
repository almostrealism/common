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

/**
 * Strategy interface that controls which {@link Expression} instances are eligible for
 * series-based simplification during scope compilation.
 *
 * <p>Implementations may use any heuristic — tree depth, node count, a hash-based
 * random sample, or a combination — to decide whether a given expression should be
 * simplified. The strategy is selected at startup via the {@code AR_SCOPE_SIMPLIFICATION}
 * system property and stored in {@link ScopeSettings}.</p>
 *
 * @see ScopeSettings#isSeriesSimplificationTarget(Expression, int)
 * @see SpectrumSimplification
 * @see TieredSimplificationSettings
 */
public interface SimplificationSettings {

	/**
	 * Returns {@code true} if the given expression should be simplified using
	 * series-based techniques at the specified recursion depth.
	 *
	 * @param expression the expression being considered for simplification
	 * @param depth      the current recursion depth within the expression tree
	 * @return {@code true} if series simplification should be applied
	 */
	boolean isSeriesSimplificationTarget(Expression<?> expression, int depth);

	/**
	 * Returns a short human-readable description of this strategy, used in profiling
	 * and logging output via {@link ScopeSettings#shortDesc()}.
	 *
	 * @return a brief description of the simplification strategy
	 */
	String shortDesc();

	/**
	 * Returns a {@link SimplificationSettings} that never approves any expression for
	 * simplification — effectively disabling series simplification entirely.
	 *
	 * @return a no-op simplification strategy
	 */
	static SimplificationSettings none() {
		return new SimplificationSettings() {
			@Override
			public boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
				return false;
			}

			@Override
			public String shortDesc() {
				return "none";
			}
		};
	}
}
