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
import java.util.List;

/**
 * A {@link CachingSettings} strategy that selects expressions for caching using a
 * hash-based filter distributed across multiple expression tree depths.
 *
 * <p>Unlike {@link ExplicitDepthCaching}, which targets a single fixed depth,
 * {@code SpectrumCaching} spreads caching across a configurable number of depths
 * and uses a deterministic hash on the expression's node count to accept only a
 * fraction of candidates at each depth. This produces a tunable "spectrum" of
 * cached expressions that balances memory overhead against redundant computation.</p>
 *
 * <h2>Parameters</h2>
 * <ul>
 *   <li><b>dScale</b> — depth scale. The number of target tree depths is
 *       {@code (int)(dScale * 10)}. Each depth is generated from a repeating
 *       modular sequence starting at 7.</li>
 *   <li><b>fScale</b> — frequency scale in the range [0, 1]. Controls what
 *       fraction of expressions at each depth pass the hash filter. Higher values
 *       admit more expressions at later depths; lower values are more selective.</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>Configured via the {@code AR_SCOPE_CACHING} system property as
 * {@code "dScale:fScale"} (e.g. {@code "0.2:0.2"}). The default is {@code "0.2:0.2"},
 * which targets 2 depths with low admission frequency. Setting the property to
 * {@code "explicit"} switches to {@link ExplicitDepthCaching} instead.</p>
 *
 * @see CachingSettings
 * @see ExplicitDepthCaching
 * @see ScopeSettings#isExpressionCacheTarget(Expression)
 */
public class SpectrumCaching implements CachingSettings, ConsoleFeatures {
	/** The set of expression tree depths eligible for caching. */
	private final List<Integer> depths;

	/** The frequency scale controlling per-depth admission thresholds. */
	private final double fScale;

	/** Prime multiplier used in the hash function. */
	private final int j;

	/** Modulus used in the hash function. */
	private final int k;

	/** Per-depth admission thresholds: an expression at depth index {@code d} passes
	 *  the filter when {@code (j * nodeCount) % k <= m[d]}. */
	private final int m[];

	/**
	 * Creates a new {@code SpectrumCaching} strategy with the given depth and frequency scales.
	 *
	 * @param dScale depth scale; the number of target depths is {@code (int)(dScale * 10)}.
	 *               Each depth is produced by a repeating modular sequence starting at 7.
	 * @param fScale frequency scale in [0, 1]; controls what fraction of expressions at
	 *               each depth are admitted by the hash filter
	 */
	public SpectrumCaching(double dScale, double fScale) {
		this.fScale = fScale;
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

//		log("d = " + Arrays.toString(depths.toArray()) +
//				" | m = " + Arrays.toString(m) + " (" + fScale + ")");
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Uses a hash-based filter: the expression's node count is hashed with a
	 * prime multiplier and checked against a depth-specific threshold derived from
	 * the frequency scale.</p>
	 */
	@Override
	public boolean isExpressionCacheTarget(Expression<?> expression) {
		int d = depths.indexOf(expression.treeDepth());
		if (d < 0) return false;

		return (j * expression.countNodes()) % k <= m[d];
	}

	/** {@inheritDoc} */
	@Override
	public String shortDesc() {
		return String.join("_",
				depths.stream().map(String::valueOf).toArray(String[]::new)) + "_" + fScale;
	}

	/** {@inheritDoc} */
	@Override
	public Console console() { return Scope.console; }
}
