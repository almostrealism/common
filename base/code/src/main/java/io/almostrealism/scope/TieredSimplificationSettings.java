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
 * A depth-tiered {@link SimplificationSettings} strategy that becomes progressively
 * stricter as the recursion depth increases.
 *
 * <p>Expressions at shallow recursion depths are always simplified; at deeper levels the
 * decision is gated by additional structural criteria (node count, presence of long integers,
 * boolean type, and a depth-modulo preference). This avoids runaway recursion on large
 * expression trees while ensuring complex sub-expressions are still simplified.</p>
 *
 * <p>The strategy is selected by passing {@code AR_SCOPE_SIMPLIFICATION=tiered} at startup.</p>
 *
 * @see SimplificationSettings
 * @see ScopeSettings
 */
public class TieredSimplificationSettings implements SimplificationSettings {

	/** Recursion depth boundary below which all expressions are simplified unconditionally. */
	private static int tier0 = 2;

	/** Recursion depth boundary for the first selective tier. */
	private static int tier1 = 8;

	/** Recursion depth boundary for the second selective tier. */
	private static int tier2 = 16;

	/** Recursion depth boundary for the third selective tier. */
	private static int tier3 = 20;

	/** Depth-modulo preference applied in the first selective tier. */
	private static int pref1 = 2;

	/** Depth-modulo preference applied in the second selective tier. */
	private static int pref2 = 3;

	/** Depth-modulo preference applied in the third selective tier. */
	private static int pref3 = 4;

	/** Depth-modulo preference applied in the deepest tier. */
	private static int pref4 = 7;

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns {@code true} based on the recursion depth and expression properties:</p>
	 * <ul>
	 *   <li>Depth &lt; {@link #tier0}: always returns {@code true}</li>
	 *   <li>Depth &lt; {@link #tier1}: boolean type, long presence, large node count, or depth-pref1 match</li>
	 *   <li>Depth &lt; {@link #tier2}: long presence, large node count, or depth-pref2 match</li>
	 *   <li>Depth &lt; {@link #tier3}: long presence, large node count, or depth-pref3 match</li>
	 *   <li>Deeper: very large node count or depth-pref4 match</li>
	 * </ul>
	 */
	public boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		// if (expression.getType() == Boolean.class) return true;

		if (depth < tier0) {
			return true;
		} else if (depth < tier1) {
			return expression.getType() == Boolean.class || expression.containsLong() ||
					expression.countNodes() > 50 ||
					targetByDepth(expression.treeDepth(), pref1);
		} else if (depth < tier2) {
			return expression.containsLong() ||
					expression.countNodes() > 75 ||
					targetByDepth(expression.treeDepth(), pref2);
		} else if (depth < tier3) {
			return expression.containsLong() ||
					expression.countNodes() > 85 ||
					targetByDepth(expression.treeDepth(), pref3);
		} else {
			return expression.countNodes() > 100 ||
					targetByDepth(expression.treeDepth(), pref4);
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code "Tiered"}
	 */
	public String shortDesc() {
		return "Tiered";
	}

	/**
	 * Returns {@code true} if the given tree depth exceeds a minimum threshold and is
	 * evenly divisible by the preference modulus. Used to sample a fraction of expressions
	 * at deeper recursion levels.
	 *
	 * @param depth      the expression tree depth to test
	 * @param preference the modulus divisor; smaller values approve more expressions
	 * @return {@code true} if the depth qualifies under the preference rule
	 */
	public static boolean targetByDepth(int depth, int preference) {
		return depth > (preference + 3) && depth % preference == 0;
	}
}
