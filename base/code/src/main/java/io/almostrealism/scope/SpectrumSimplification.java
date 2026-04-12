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

/**
 * A hash-based {@link SimplificationSettings} strategy that selects a deterministic
 * fraction of expressions for series simplification.
 *
 * <p>The fraction of expressions simplified is controlled by the {@code scale} parameter
 * (range {@code [0.0, 1.0]}). The selection is made by mapping an expression's node count
 * through a linear congruential hash and comparing the result against a threshold derived
 * from {@code scale}. The hash spreads node-count values across the range {@code [0, k)}
 * so that even small node-count populations are sampled proportionally.</p>
 *
 * <p>Expressions at a tree depth greater than {@link #depthLimit} are never simplified,
 * regardless of scale, to prevent runaway recursion on pathologically deep trees.</p>
 *
 * @see SimplificationSettings
 * @see ScopeSettings
 */
public class SpectrumSimplification implements SimplificationSettings, ConsoleFeatures {
	/** Maximum tree depth at which simplification is attempted; deeper expressions are skipped. */
	public static int depthLimit = 24;

	/** The fraction of expressions approved for simplification, in the range {@code [0.0, 1.0]}. */
	private final double scale;

	/** Multiplier applied to node count before taking the modulus. */
	private final int j;

	/** Modulus divisor; determines the hash range {@code [0, k)}. */
	private final int k;

	/** Threshold derived from {@code scale * (k - 1)}; expressions hash at or below this are approved. */
	private final int m;

	/**
	 * Creates a {@link SpectrumSimplification} that approves approximately {@code scale}
	 * of all expressions for series simplification.
	 *
	 * @param scale the fraction of expressions to approve, in the range {@code [0.0, 1.0]}
	 */
	public SpectrumSimplification(double scale) {
		this.scale = scale;
		this.j = 13;
		this.k = 41;
		this.m = (int) (scale * (k - 1));
//		log("d = " + depthLimit + " | m = " + m + "/" + k);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns {@code false} for expressions deeper than {@link #depthLimit}.
	 * Otherwise, applies the hash {@code (j * nodeCount) % k} and returns {@code true}
	 * when the result is at or below the scale threshold {@link #m}.</p>
	 */
	@Override
	public boolean isSeriesSimplificationTarget(Expression<?> expression, int depth) {
		if (depth > depthLimit) return false;

		return (j * expression.countNodes()) % k <= m;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return the string representation of the {@link #scale} value
	 */
	@Override
	public String shortDesc() {
		return String.valueOf(scale);
	}

	/** {@inheritDoc} */
	@Override
	public Console console() { return Scope.console; }
}
