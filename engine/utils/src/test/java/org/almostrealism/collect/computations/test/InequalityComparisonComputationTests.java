/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect.computations.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.LessThanCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that pin the behavior shared by {@link GreaterThanCollection} and
 * {@link LessThanCollection} through their common superclass
 * {@link org.almostrealism.collect.computations.InequalityComparisonComputation}.
 *
 * <p>These tests exercise the strict versus inclusive operator selection, the
 * expansion width, the signature contribution, and the construction-time
 * zero-shape rejection. {@link #zeroCountShapeRejectedByBothOperators()} records
 * that both operators reject a zero-sized shape identically, via the shared
 * {@code CollectionProducerComputationBase} shape validation.</p>
 */
public class InequalityComparisonComputationTests extends TestSuiteBase {
	/**
	 * Verifies that the {@code includeEqual} flag switches greater-than between the
	 * strict ({@code >}) and inclusive ({@code >=}) operator for equal operands.
	 */
	@Test(timeout = 30000)
	public void greaterThanStrictVersusInclusive() {
		Producer<PackedCollection> a = c(5.0);
		Producer<PackedCollection> b = c(5.0);

		try (PackedCollection strict = greaterThan(a, b, c(1.0), c(0.0), false).get().evaluate();
			 PackedCollection inclusive = greaterThan(a, b, c(1.0), c(0.0), true).get().evaluate()) {
			Assert.assertEquals("5 > 5 is false", 0.0, strict.toDouble(), 0.001);
			Assert.assertEquals("5 >= 5 is true", 1.0, inclusive.toDouble(), 0.001);
		}
	}

	/**
	 * Verifies that the {@code includeEqual} flag switches less-than between the
	 * strict ({@code <}) and inclusive ({@code <=}) operator for equal operands.
	 */
	@Test(timeout = 30000)
	public void lessThanStrictVersusInclusive() {
		Producer<PackedCollection> a = c(5.0);
		Producer<PackedCollection> b = c(5.0);

		try (PackedCollection strict = lessThan(a, b, c(1.0), c(0.0), false).get().evaluate();
			 PackedCollection inclusive = lessThan(a, b, c(1.0), c(0.0), true).get().evaluate()) {
			Assert.assertEquals("5 < 5 is false", 0.0, strict.toDouble(), 0.001);
			Assert.assertEquals("5 <= 5 is true", 1.0, inclusive.toDouble(), 0.001);
		}
	}

	/**
	 * Verifies that both operators report an expansion width of {@code 2}, matching
	 * the two-branch conditional they emit. This behavior now lives on the shared
	 * superclass rather than being copied into each subclass.
	 */
	@Test(timeout = 30000)
	public void expansionWidthIsTwoForBothOperators() {
		GreaterThanCollection gt =
				new GreaterThanCollection(shape(1), c(1.0), c(1.0), c(1.0), c(1.0));
		LessThanCollection lt =
				new LessThanCollection(shape(1), c(1.0), c(1.0), c(1.0), c(1.0));

		Assert.assertEquals(2L, gt.getExpansionWidth());
		Assert.assertEquals(2L, lt.getExpansionWidth());
	}

	/**
	 * Verifies that the signature records the {@code includeEqual} flag, so that a
	 * strict comparison and an inclusive comparison over identical operands are
	 * distinguished. This contribution now lives on the shared superclass.
	 */
	@Test(timeout = 30000)
	public void signatureRecordsIncludeEqual() {
		GreaterThanCollection strict =
				new GreaterThanCollection(shape(1), c(1.0), c(1.0), c(1.0), c(1.0), false);
		GreaterThanCollection inclusive =
				new GreaterThanCollection(shape(1), c(1.0), c(1.0), c(1.0), c(1.0), true);

		String strictSignature = strict.signature();
		String inclusiveSignature = inclusive.signature();

		Assert.assertNotNull(strictSignature);
		Assert.assertNotNull(inclusiveSignature);
		Assert.assertTrue(strictSignature.endsWith("{includeEqual:false}"));
		Assert.assertTrue(inclusiveSignature.endsWith("{includeEqual:true}"));
		Assert.assertNotEquals(strictSignature, inclusiveSignature);
	}

	/**
	 * Records that both greater-than and less-than reject a zero-sized shape at
	 * construction time, identically, with a fail-loud {@link IllegalArgumentException}.
	 *
	 * <p>The rejection happens in the shared {@code CollectionProducerComputationBase}
	 * constructor (invoked via {@code super(...)} before either operator's own body
	 * runs), so both operators were already consistent for this input even before the
	 * consolidation. What the consolidation fixes is a different, narrower divergence:
	 * {@link GreaterThanCollection} carried an additional {@code getCountLong() <= 0}
	 * guard that {@link LessThanCollection} lacked. That guard now lives once on the
	 * shared {@link org.almostrealism.collect.computations.InequalityComparisonComputation}
	 * superclass, but it is unreachable for a genuinely zero-sized shape — the shared
	 * base class validation above always fires first.</p>
	 */
	@Test(timeout = 30000)
	public void zeroCountShapeRejectedByBothOperators() {
		try {
			new GreaterThanCollection(shape(0), c(1.0), c(1.0), c(1.0), c(1.0));
			Assert.fail("GreaterThanCollection should reject a zero-sized shape");
		} catch (IllegalArgumentException expected) {
			// expected: shared CollectionProducerComputationBase shape validation
		}

		try {
			new LessThanCollection(shape(0), c(1.0), c(1.0), c(1.0), c(1.0));
			Assert.fail("LessThanCollection should reject a zero-sized shape");
		} catch (IllegalArgumentException expected) {
			// expected: shared CollectionProducerComputationBase shape validation
		}
	}
}
