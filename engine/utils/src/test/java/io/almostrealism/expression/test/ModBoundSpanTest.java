/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.expression.test;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Mod;
import io.almostrealism.kernel.KernelIndex;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link Mod#upperBound} and {@link Mod#lowerBound} report
 * <em>sound</em> bounds for {@code (dividend % modulus)} when the dividend's
 * integer range straddles a multiple of the modulus.
 *
 * <p>Over a non-negative dividend range, {@code x % m} increases by one per
 * step and wraps back to {@code 0} at every multiple of {@code m}. The value
 * immediately before a wrap is {@code m - 1} and the value at the wrap is
 * {@code 0}, so whenever the range crosses a multiple of {@code m} the tightest
 * <em>sound</em> upper bound is {@code m - 1} and the tightest sound lower bound
 * is {@code 0}. Estimating the bounds from the residues of the range endpoints
 * alone (as the span-based branch of {@link Mod} did) silently under-reports the
 * maximum and over-reports the minimum, which is an unsound bound the kernel
 * index analysis can rely on to produce incorrect code.</p>
 *
 * <p>A {@link KernelIndex} is used as the dividend because it carries a known,
 * present lower bound of {@code 0} and an upper bound of {@code limit - 1}, so
 * the {@code Mod} bounds branches actually engage (an index whose lower bound is
 * absent would fall through to the safe defaults and never exercise the
 * defect). Every assertion checks bound soundness against the residue extremes
 * enumerated over the range; none of these values is a floating-point
 * tolerance.</p>
 */
public class ModBoundSpanTest extends TestSuiteBase {

	/**
	 * The largest residue {@code x % m} over the inclusive integer range
	 * {@code [lo, hi]}, computed by direct enumeration.
	 */
	private static long trueMax(long lo, long hi, long m) {
		long max = 0;
		for (long x = lo; x <= hi; x++) max = Math.max(max, x % m);
		return max;
	}

	/**
	 * The smallest residue {@code x % m} over the inclusive integer range
	 * {@code [lo, hi]}, computed by direct enumeration.
	 */
	private static long trueMin(long lo, long hi, long m) {
		long min = Long.MAX_VALUE;
		for (long x = lo; x <= hi; x++) min = Math.min(min, x % m);
		return min;
	}

	/**
	 * Builds {@code (kernel[0, limit) + offset) % modulus}. The kernel index
	 * supplies a present lower bound so the {@code Mod} bounds analysis engages.
	 */
	private static Expression<?> boundedMod(int limit, int offset, int modulus) {
		return new KernelIndex().withLimit(limit).add(offset).imod(modulus);
	}

	/**
	 * Dividend range {@code [1, 5]} modulo {@code 5} takes residues
	 * {@code {1, 2, 3, 4, 0}}, whose maximum is {@code 4}. The wrap happens at the
	 * multiple {@code 5}, which is the modulus value itself but sits on the range
	 * boundary, so the endpoint-only estimate reports {@code max(1 % 5, 5 % 5) = 1}
	 * — an upper bound below values the expression actually takes.
	 */
	@Test(timeout = 30000)
	public void upperBoundStraddlingMultipleIsSound() {
		Expression<?> mod = boundedMod(5, 1, 5);

		long bound = mod.upperBound().orElse(Long.MIN_VALUE);
		long actualMax = trueMax(1, 5, 5);

		Assert.assertTrue("upper bound " + bound
						+ " must be a sound bound (>= the actual maximum residue " + actualMax + ")",
				bound >= actualMax);
	}

	/**
	 * Dividend range {@code [3, 8]} modulo {@code 6} takes residues
	 * {@code {3, 4, 5, 0, 1, 2}}, whose minimum is {@code 0} (attained at the
	 * multiple {@code 6}). The endpoint-only estimate reports
	 * {@code min(3 % 6, 8 % 6) = 2} — a lower bound above values the expression
	 * actually takes.
	 */
	@Test(timeout = 30000)
	public void lowerBoundStraddlingMultipleIsSound() {
		Expression<?> mod = boundedMod(6, 3, 6);

		long bound = mod.lowerBound().orElse(Long.MAX_VALUE);
		long actualMin = trueMin(3, 8, 6);

		Assert.assertTrue("lower bound " + bound
						+ " must be a sound bound (<= the actual minimum residue " + actualMin + ")",
				bound <= actualMin);
	}

	/**
	 * When the whole dividend range lies within one modulus block (no wrap), the
	 * endpoint residues are exact and must be preserved: range {@code [6, 9]}
	 * modulo {@code 5} yields residues {@code {1, 2, 3, 4}}. This guards the fix
	 * against loosening the tight bound in the common non-wrapping case — the
	 * bounds must equal the enumerated residue extremes exactly.
	 */
	@Test(timeout = 30000)
	public void nonWrappingRangeKeepsExactEndpointBounds() {
		Expression<?> mod = boundedMod(4, 6, 5);

		long upper = mod.upperBound().orElse(Long.MIN_VALUE);
		long lower = mod.lowerBound().orElse(Long.MAX_VALUE);

		Assert.assertEquals("upper bound of a non-wrapping range is the top residue",
				trueMax(6, 9, 5), upper);
		Assert.assertEquals("lower bound of a non-wrapping range is the bottom residue",
				trueMin(6, 9, 5), lower);
	}
}
