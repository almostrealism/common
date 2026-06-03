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

package org.almostrealism.collect.computations.test;

import io.almostrealism.collect.Algebraic;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.SubsetProjectionComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Recognition tests for the {@link Algebraic#isRowMonomial()} capability.
 *
 * <p>A row-monomial matrix has exactly one non-zero entry per row. The Jacobian of a
 * subset (slice) operation is row-monomial by construction (each output element comes
 * from exactly one input element), and {@link SubsetProjectionComputation} advertises
 * this so the isolation strategy can keep the selection visible for gather collapse.
 * These tests confirm the capability is true on a real subset-delta and that it
 * propagates through the reshape wrappers that sit between the projection and any
 * downstream contraction. They also guard the conservative default: ordinary
 * computations must report {@code false}.</p>
 *
 * @author Michael Murray
 */
public class RowMonomialRecognitionTests extends TestSuiteBase {

	/**
	 * The direct-case subset Jacobian ({@code d(subset(x))/dx}) is a
	 * {@link SubsetProjectionComputation} and must report {@code isRowMonomial() == true}.
	 */
	@Test(timeout = 60000)
	public void subsetDeltaIsRowMonomial() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		CollectionProducer delta = cp(in).subset(shape(k), off).delta(cp(in));

		assertTrue("subset delta should be a SubsetProjectionComputation",
				delta instanceof SubsetProjectionComputation);
		assertTrue("subset delta should be recognized as row-monomial",
				Algebraic.isRowMonomial(delta));
	}

	/**
	 * The row-monomial property must survive a {@code reshape}: a reshape delegates
	 * {@link Algebraic} queries to its inner producer, so reshaping a subset Jacobian
	 * preserves recognition.
	 */
	@Test(timeout = 60000)
	public void reshapedSubsetDeltaIsRowMonomial() {
		int n = 12;
		int k = 6;
		int off = 2;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		CollectionProducer delta = cp(in).subset(shape(k), off).delta(cp(in));
		CollectionProducer reshaped = delta.reshape(shape(k, n));

		assertTrue("reshaped subset delta should still be recognized as row-monomial",
				Algebraic.isRowMonomial(reshaped));
	}

	/**
	 * Conservative default: an ordinary element-wise computation is not row-monomial.
	 * A false positive here would cause a wrong gather collapse, so this guards the
	 * default {@code false}.
	 */
	@Test(timeout = 60000)
	public void ordinaryComputationIsNotRowMonomial() {
		int n = 8;

		PackedCollection a = new PackedCollection(shape(n)).randFill();
		PackedCollection b = new PackedCollection(shape(n)).randFill();

		CollectionProducer sum = cp(a).add(cp(b));

		assertFalse("an element-wise sum should not be recognized as row-monomial",
				Algebraic.isRowMonomial(sum));
	}
}
