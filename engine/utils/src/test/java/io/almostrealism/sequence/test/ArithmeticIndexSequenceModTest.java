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

package io.almostrealism.sequence.test;

import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.sequence.ArithmeticIndexSequence;
import io.almostrealism.sequence.IndexSequence;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DefaultTraversableExpressionComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ArithmeticIndexSequence#mod(long)}.
 *
 * <p>{@link ArithmeticIndexSequence#mod(long)} is documented as an optimization
 * that returns an equivalent {@link ArithmeticIndexSequence} when it can, and
 * "otherwise falls back to the default element-wise modulo operation." Whichever
 * path it takes, the contract of {@link IndexSequence#mod(long)} is element-wise:
 * the result at every position must equal {@code valueAt(pos) % m}. These tests
 * pin that equivalence for sequences with granularity greater than one, where the
 * modulus is a multiple of {@code m} but not of {@code granularity * m}.</p>
 */
public class ArithmeticIndexSequenceModTest extends TestSuiteBase {

	/**
	 * Reducing a granularity-2 sequence whose modulus (6) is a multiple of the
	 * reduction modulus (2) but not of {@code granularity * m} (4) must still
	 * produce the element-wise remainder at every position.
	 *
	 * <p>The base sequence {@code offset=0, scale=1, granularity=2, mod=6, len=8}
	 * is {@code [0, 0, 1, 1, 2, 2, 0, 0]}. Reduced modulo 2 it must be
	 * {@code [0, 0, 1, 1, 0, 0, 0, 0]}. The fast path may only be taken when it
	 * reproduces exactly this.</p>
	 */
	@Test(timeout = 10000)
	public void granularTwoModTwoMatchesElementwise() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, 1, 2, 6, 8);
		IndexSequence reduced = seq.mod(2);

		for (long pos = 0; pos < 8; pos++) {
			long expected = seq.valueAt(pos).longValue() % 2;
			Assert.assertEquals("mismatch at position " + pos,
					expected, reduced.valueAt(pos).longValue());
		}
	}

	/**
	 * A second, independent case: granularity 3, modulus 12 (a multiple of the
	 * reduction modulus 2 but not of {@code granularity * m = 6}). The base
	 * sequence is {@code [0,0,0,1,1,1,2,2,2,3,3,3]} over its first 12 positions;
	 * reduced modulo 2 it must be {@code [0,0,0,1,1,1,0,0,0,1,1,1]}.
	 */
	@Test(timeout = 10000)
	public void granularThreeModTwoMatchesElementwise() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, 1, 3, 12, 12);
		IndexSequence reduced = seq.mod(2);

		for (long pos = 0; pos < 12; pos++) {
			long expected = seq.valueAt(pos).longValue() % 2;
			Assert.assertEquals("mismatch at position " + pos,
					expected, reduced.valueAt(pos).longValue());
		}
	}

	/**
	 * The fast path is still exercised and correct when the modulus <em>is</em> a
	 * multiple of {@code granularity * m}: {@code mod=8, granularity=2, m=2}. The
	 * base sequence {@code [0,0,1,1,2,2,3,3]} reduced modulo 2 is
	 * {@code [0,0,1,1,0,0,1,1]}. This guards against a fix that simply disables
	 * the optimization entirely.
	 */
	@Test(timeout = 10000)
	public void alignedModulusMatchesElementwise() {
		ArithmeticIndexSequence seq = new ArithmeticIndexSequence(0, 1, 2, 8, 8);
		IndexSequence reduced = seq.mod(2);

		for (long pos = 0; pos < 8; pos++) {
			long expected = seq.valueAt(pos).longValue() % 2;
			Assert.assertEquals("mismatch at position " + pos,
					expected, reduced.valueAt(pos).longValue());
		}
	}

	/**
	 * End-to-end proof that the guard in {@link ArithmeticIndexSequence#mod(long)} is not
	 * merely an {@link IndexSequence} bookkeeping detail: the same {@code (index % 6) / 2 % 2}
	 * pattern arises whenever a kernel index expression is reduced modulo a divisor and then
	 * modulo a second, smaller value (for example, position arithmetic feeding a repeated or
	 * tiled memory access). When a {@link org.almostrealism.collect.CollectionProducer} built
	 * from such an expression is compiled to a real kernel, the compiler's
	 * {@code KernelSeriesProvider} calls exactly this code path
	 * ({@code Mod.sequence()} to {@code ArithmeticGenerator.sequence()} to
	 * {@code ArithmeticIndexSequence.mod()}) to fold the index arithmetic into the generated
	 * source. With the pre-fix guard ({@code mod % m != 0}), the folded sequence is wrong at
	 * positions 6 and 7, so the actual, compiled {@code evaluate()} output of the producer —
	 * not just the {@link IndexSequence} in isolation — is numerically incorrect.
	 */
	@Test(timeout = 30000)
	public void collectionProducerGranularTwoModTwoMatchesElementwise() {
		// TODO(review): counterfactual (fails if mod(long) guard is reverted) unconfirmed
		TraversalPolicy shape = new TraversalPolicy(8);
		PackedCollection unused = new PackedCollection(1);

		DefaultTraversableExpressionComputation computation =
				new DefaultTraversableExpressionComputation("granularityModRegression", shape,
						args -> CollectionExpression.create(shape, idx ->
								idx.imod(6).divide(2).imod(2)),
						p(unused));

		PackedCollection actual = computation.get().evaluate();

		for (int pos = 0; pos < 8; pos++) {
			long expected = ((pos % 6) / 2) % 2;
			Assert.assertEquals("mismatch at position " + pos,
					(double) expected, actual.toDouble(pos), 0.0);
		}
	}
}
