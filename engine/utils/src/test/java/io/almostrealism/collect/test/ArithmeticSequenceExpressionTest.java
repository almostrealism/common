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

package io.almostrealism.collect.test;

import io.almostrealism.collect.ArithmeticSequenceExpression;
import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

/**
 * Tests for {@link ArithmeticSequenceExpression#containsIndex(io.almostrealism.expression.Expression)}.
 *
 * <p>Both the class javadoc (which lists {@code [5, 5, 5, ...]} with {@code rate=0}
 * as a supported constant sequence) and the {@code containsIndex} javadoc ("Otherwise,
 * assumes all indices contain non-zero values and returns true") require that a
 * constant non-zero sequence report every index as a member. These tests pin that
 * contract.</p>
 */
public class ArithmeticSequenceExpressionTest extends TestSuiteBase {

	/**
	 * A constant non-zero sequence ({@code initial != 0}, {@code rate == 0}) has a
	 * non-zero value at every index, so {@code containsIndex} must resolve to
	 * {@code true} for every index. The zero-crossing branch computes
	 * {@code n = -initial / rate}, which is {@code -Infinity} when {@code rate == 0};
	 * without a guard this cast to {@code (int)} yields {@link Integer#MIN_VALUE}, so
	 * the method incorrectly claims the index is a member only at
	 * {@code Integer.MIN_VALUE} (i.e. effectively never).
	 */
	@Test(timeout = 30000)
	public void constantNonZeroSequenceContainsEveryIndex() {
		ArithmeticSequenceExpression seq =
				new ArithmeticSequenceExpression(new TraversalPolicy(4), 5.0, 0.0);

		Assert.assertEquals(Optional.of(Boolean.TRUE), seq.containsIndex(0));
		Assert.assertEquals(Optional.of(Boolean.TRUE), seq.containsIndex(3));
	}
}
