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
import io.almostrealism.compute.Process;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.CollectionAddComputation;
import org.almostrealism.collect.computations.CollectionProductComputation;
import org.almostrealism.collect.computations.CollectionZerosComputation;
import org.almostrealism.collect.computations.SubsetProjectionComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Recognition tests for the {@link Algebraic#isRowMonomial()} capability.
 *
 * <p>A row-monomial matrix has at most one non-zero entry per row (a row may also be
 * entirely zero). The Jacobian of a subset (slice) operation is row-monomial by
 * construction (each output element comes from exactly one input element), and
 * {@link SubsetProjectionComputation} advertises this so the isolation strategy can keep
 * the selection visible for gather collapse. These tests confirm the capability is true on
 * a real subset-delta and that it propagates through the reshape wrappers, the Hadamard
 * product ({@link org.almostrealism.collect.computations.CollectionProductComputation}), and
 * the degenerate sum ({@link CollectionAddComputation}) that sit between the projection and
 * any downstream contraction. They also guard the conservative default (ordinary
 * computations must report {@code false}) and the soundness of the gather collapse when a
 * row-merging reshape produces a false-positive flag.</p>
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

	/**
	 * A Hadamard product between a row-monomial operand and a factor that is zero at the
	 * row-monomial operand's selected entry produces a row with zero non-zero entries, not
	 * exactly one - {@link CollectionProductComputation#isRowMonomial()} still reports
	 * {@code true} in this case (it only requires at least one operand to be row-monomial,
	 * not that the product retain exactly one non-zero per row). This test confirms that
	 * the gather-collapse this enables still reads the true (zero) value at that position
	 * rather than an incorrect one, since the collapse reads the actual product expression
	 * at the computed offset rather than assuming the row-monomial operand's value is
	 * necessarily present in the result.
	 *
	 * <p>The hard-coded values below would also hold if the optimizer silently fell back to
	 * the dense reduction, so this additionally requires the optimized graph (which may
	 * collapse the reduction to a gather) to match the un-optimized dense evaluation
	 * element-for-element: a wrong gather collapse would break that equality.</p>
	 */
	@Test(timeout = 60000)
	public void productWithAnnihilatingFactorIsSound() {
		PackedCollection multiplier = pack(0.0, 3.0, 2.0, 1.0).reshape(2, 2).traverse(1);
		PackedCollection in = pack(2.0, 1.0, 4.0, 3.0).reshape(2, 2).traverse(1);
		CollectionProducer delta = cp(in).multiply(cp(multiplier)).sum().delta(cp(in));
		PackedCollection out = Process.optimized(delta).get().evaluate();

		assertEquals(0.0, out.valueAt(0, 0, 0, 0));
		assertEquals(3.0, out.valueAt(0, 0, 0, 1));
		assertEquals(0.0, out.valueAt(0, 0, 1, 0));
		assertEquals(0.0, out.valueAt(0, 0, 1, 1));
		assertEquals(0.0, out.valueAt(1, 0, 0, 0));
		assertEquals(0.0, out.valueAt(1, 0, 0, 1));
		assertEquals(2.0, out.valueAt(1, 0, 1, 0));
		assertEquals(1.0, out.valueAt(1, 0, 1, 1));

		PackedCollection dense = cp(in).multiply(cp(multiplier)).sum().delta(cp(in)).get().evaluate();
		assertEquals(dense.getShape().getTotalSize(), out.getShape().getTotalSize());
		for (int i = 0; i < dense.getShape().getTotalSize(); i++) {
			assertEquals(dense.toDouble(i), out.toDouble(i));
		}
	}

	/**
	 * The row-monomial property of one operand propagates through a Hadamard product: a
	 * {@link CollectionProductComputation} whose operand is the (row-monomial) Jacobian of a
	 * subset must itself report {@code isRowMonomial() == true}. This is the recognition that
	 * {@link CollectionProductComputation#isRowMonomial()} adds, and it is what keeps the
	 * product visible for a downstream gather collapse. Directly asserting it here covers the
	 * propagation independently of any end-to-end evaluation that would still pass on the dense
	 * fallback.
	 */
	@Test(timeout = 60000)
	public void productPropagatesRowMonomialOperand() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();
		PackedCollection other = new PackedCollection(shape(k, n)).randFill();

		CollectionProducer product = cp(in).subset(shape(k), off).delta(cp(in)).multiply(cp(other));

		assertTrue("a Hadamard product of a row-monomial operand should be a CollectionProductComputation",
				product instanceof CollectionProductComputation);
		assertTrue("a Hadamard product with a row-monomial operand should be recognized as row-monomial",
				Algebraic.isRowMonomial(product));
	}

	/**
	 * The degenerate sum that survives the product-rule delta: when every operand except one
	 * is algebraically zero and that one operand is row-monomial, the
	 * {@link CollectionAddComputation} must report {@code isRowMonomial() == true}. This is the
	 * case a product-rule {@code delta} produces when the second term has a zero derivative,
	 * and it is the true path of {@link CollectionAddComputation#isRowMonomial()}. The operand
	 * is a {@link CollectionZerosComputation} (an {@link Algebraic#isZero(Object) algebraic
	 * zero}, unlike a merely zero-valued collection) so the sum is not simplified away before
	 * the recognition runs.
	 */
	@Test(timeout = 60000)
	public void sumWithSingleNonZeroRowMonomialOperandIsRowMonomial() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		CollectionAddComputation sum = new CollectionAddComputation(shape(k, n),
				cp(in).subset(shape(k), off).delta(cp(in)),
				new CollectionZerosComputation(shape(k, n)));

		assertTrue("a sum of a row-monomial operand and an algebraic zero should be row-monomial",
				Algebraic.isRowMonomial(sum));
	}

	/**
	 * The complementary false path of {@link CollectionAddComputation#isRowMonomial()}: a sum
	 * of two genuinely non-zero row-monomial operands is not row-monomial, because their
	 * non-zero columns can differ and add to a row with two non-zero entries. Guarding this
	 * {@code false} prevents a wrong gather collapse on an ordinary two-term sum.
	 */
	@Test(timeout = 60000)
	public void sumOfTwoRowMonomialOperandsIsNotRowMonomial() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		CollectionAddComputation sum = new CollectionAddComputation(shape(k, n),
				cp(in).subset(shape(k), off).delta(cp(in)),
				cp(in).subset(shape(k), off).delta(cp(in)));

		assertFalse("a sum of two non-zero row-monomial operands should not be row-monomial",
				Algebraic.isRowMonomial(sum));
	}

	/**
	 * Regression guard for row-merging reshapes. A {@code reshape} delegates
	 * {@link Algebraic#isRowMonomial()} to its inner producer unchanged, so reshaping a
	 * {@code (k, n)} row-monomial Jacobian into a single {@code (1, k*n)} row leaves it
	 * reporting {@code true} even though that merged row now holds {@code k} non-zero entries -
	 * a genuine false positive. This test feeds such a reshaped operand into a contraction and
	 * requires the optimized graph (which may act on the row-monomial flag to keep the operand
	 * visible for a gather collapse) to match the un-optimized dense evaluation
	 * element-for-element. The equality holds because the collapse is a value-based analysis
	 * that reads the true value at each row and declines to collapse a row whose non-zero entry
	 * is not unique; the flag only gates whether the collapse is attempted, not what value it
	 * reads, so a false positive cannot corrupt the result.
	 */
	@Test(timeout = 60000)
	public void rowMergingReshapeGatherRemainsSound() {
		int n = 12;
		int k = 5;
		int off = 3;

		PackedCollection in = new PackedCollection(shape(n)).randFill();

		CollectionProducer merged = cp(in).subset(shape(k), off).delta(cp(in)).reshape(shape(1, k * n));
		assertTrue("a row-merging reshape delegates the flag unchanged, yielding a false positive",
				Algebraic.isRowMonomial(merged));

		PackedCollection dense = cp(in).subset(shape(k), off).delta(cp(in))
				.reshape(shape(1, k * n)).sum().delta(cp(in)).get().evaluate();
		PackedCollection optimized = (PackedCollection) Process.optimized(
				cp(in).subset(shape(k), off).delta(cp(in))
						.reshape(shape(1, k * n)).sum().delta(cp(in))).get().evaluate();

		assertEquals(dense.getShape().getTotalSize(), optimized.getShape().getTotalSize());
		for (int i = 0; i < dense.getShape().getTotalSize(); i++) {
			assertEquals(dense.toDouble(i), optimized.toDouble(i));
		}
	}
}
