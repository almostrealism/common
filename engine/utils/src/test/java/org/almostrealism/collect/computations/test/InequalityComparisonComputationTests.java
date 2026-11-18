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

import io.almostrealism.relation.Computable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.computations.ScalarMatrixComputation;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.GreaterThanCollection;
import org.almostrealism.collect.computations.LessThanCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

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
	 * consolidation. Prior to the consolidation, {@link GreaterThanCollection} additionally
	 * carried a {@code getCountLong() <= 0} guard that {@link LessThanCollection} lacked;
	 * that guard was unreachable (the base class validation above always fires first for a
	 * genuinely zero-sized shape) and has been removed rather than shared.</p>
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

	/**
	 * Pins the shared conditional-selection scaffold for greater-than: when the strict
	 * relation holds the true value is selected, and otherwise the false value is. This
	 * exercises the {@code conditional(compare(...), trueValue, falseValue)} expression
	 * now hoisted onto {@link org.almostrealism.collect.computations.InequalityComparisonComputation},
	 * with the operator direction supplied by {@link GreaterThanCollection}.
	 */
	@Test(timeout = 30000)
	public void greaterThanSelectsTrueAndFalseValues() {
		try (PackedCollection whenTrue = greaterThan(c(7.0), c(5.0), c(10.0), c(20.0), false).get().evaluate();
			 PackedCollection whenFalse = greaterThan(c(2.0), c(5.0), c(10.0), c(20.0), false).get().evaluate()) {
			Assert.assertEquals("7 > 5 selects the true value", 10.0, whenTrue.toDouble(), 0.001);
			Assert.assertEquals("2 > 5 selects the false value", 20.0, whenFalse.toDouble(), 0.001);
		}
	}

	/**
	 * Pins the shared conditional-selection scaffold for less-than: when the strict
	 * relation holds the true value is selected, and otherwise the false value is. The
	 * operator direction is supplied by {@link LessThanCollection}; the surrounding
	 * selection is the shared scaffold on the superclass.
	 */
	@Test(timeout = 30000)
	public void lessThanSelectsTrueAndFalseValues() {
		try (PackedCollection whenTrue = lessThan(c(2.0), c(5.0), c(10.0), c(20.0), false).get().evaluate();
			 PackedCollection whenFalse = lessThan(c(7.0), c(5.0), c(10.0), c(20.0), false).get().evaluate()) {
			Assert.assertEquals("2 < 5 selects the true value", 10.0, whenTrue.toDouble(), 0.001);
			Assert.assertEquals("7 < 5 selects the false value", 20.0, whenFalse.toDouble(), 0.001);
		}
	}

	/**
	 * Pins the inclusive operators element-wise over a multi-element shape, with operands
	 * above, equal to, and below the threshold, and distinct true and false values per
	 * element. This covers the inclusive branch of each subclass's
	 * {@code compare} for unequal operands (the strict-versus-inclusive tests only use
	 * equal operands) and verifies that the shared scaffold reads every argument at the
	 * same index rather than at a fixed position.
	 */
	@Test(timeout = 30000)
	public void inclusiveOperatorsSelectElementWise() {
		try (PackedCollection a = pack(7.0, 5.0, 2.0);
			 PackedCollection b = pack(5.0, 5.0, 5.0);
			 PackedCollection t = pack(10.0, 11.0, 12.0);
			 PackedCollection f = pack(20.0, 21.0, 22.0);
			 PackedCollection gte = greaterThan(cp(a), cp(b), cp(t), cp(f), true).get().evaluate();
			 PackedCollection lte = lessThan(cp(a), cp(b), cp(t), cp(f), true).get().evaluate()) {
			Assert.assertEquals("7 >= 5 selects true", 10.0, gte.toDouble(0), 0.001);
			Assert.assertEquals("5 >= 5 selects true", 11.0, gte.toDouble(1), 0.001);
			Assert.assertEquals("2 >= 5 selects false", 22.0, gte.toDouble(2), 0.001);

			Assert.assertEquals("7 <= 5 selects false", 20.0, lte.toDouble(0), 0.001);
			Assert.assertEquals("5 <= 5 selects true", 11.0, lte.toDouble(1), 0.001);
			Assert.assertEquals("2 <= 5 selects true", 12.0, lte.toDouble(2), 0.001);
		}
	}

	/**
	 * Verifies that optimizing a comparison keeps its output shape. The optimizer
	 * regenerates each comparison from its optimized children, and the regenerated
	 * computation must have the same traversal axis as the original. If it did not,
	 * a reduction over the result would reduce each element on its own instead of
	 * reducing the whole collection.
	 */
	@Test(timeout = 60000)
	public void optimizedComparisonPreservesTraversalAxis() {
		try (PackedCollection data = pack(0.0, -0.25, 0.8, -0.5, 0.1)) {
			PackedCollection optimized = lessThan(abs(cp(data)), c(Double.MAX_VALUE), cp(data), c(0.0)).evaluate();
			PackedCollection explicit = lessThan(abs(cp(data)), c(Double.MAX_VALUE), cp(data), c(0.0)).evaluateExplicit();
			PackedCollection gt = greaterThan(abs(cp(data)), c(-1.0), cp(data), c(0.0)).evaluate();
			PackedCollection eq = equals(cp(data), cp(data), cp(data), c(0.0)).evaluate();
			PackedCollection and = and(cp(data), c(1.0), cp(data), c(0.0)).evaluate();

			Assert.assertEquals(explicit.getShape().getTraversalAxis(), optimized.getShape().getTraversalAxis());
			Assert.assertEquals(explicit.getShape().getTraversalAxis(), gt.getShape().getTraversalAxis());
			Assert.assertEquals(explicit.getShape().getTraversalAxis(), eq.getShape().getTraversalAxis());
			Assert.assertEquals(explicit.getShape().getTraversalAxis(), and.getShape().getTraversalAxis());

			Assert.assertEquals(0.8, max(cp(optimized).abs()).evaluate().toDouble(0), 1e-6);
			Assert.assertEquals(0.8, max(cp(gt).abs()).evaluate().toDouble(0), 1e-6);
			Assert.assertEquals(0.8, max(cp(eq).abs()).evaluate().toDouble(0), 1e-6);
		}
	}

	/**
	 * Verifies that the derivative of a comparison has the derivative's shape. The delta of a
	 * comparison regenerates the comparison over the derivatives of its branches, which are
	 * larger than the comparison's own output because they carry the target's dimensions as
	 * well, so the regenerated computation must not keep the comparison's output shape.
	 */
	@Test(timeout = 60000)
	public void comparisonDeltaHasJacobianShape() {
		try (PackedCollection data = pack(1.0, -2.0, 3.0, -4.0)) {
			CollectionProducer x = cp(data);
			PackedCollection jacobian = greaterThan(x, c(0.0), x.multiply(2.0), x).delta(x).evaluate();

			Assert.assertEquals(16, jacobian.getShape().getTotalSize());
			for (int i = 0; i < 4; i++) {
				for (int j = 0; j < 4; j++) {
					double expected = i != j ? 0.0 : (data.toDouble(i) > 0 ? 2.0 : 1.0);
					Assert.assertEquals(expected, jacobian.toDouble(i * 4 + j), 1e-6);
				}
			}
		}
	}

	/**
	 * The diagonal scalar of a comparison between scalar matrices is a comparison of their
	 * scalars, and it has the scalars' shape rather than the matrix shape of the comparison it
	 * was extracted from. A matrix multiplication by a diagonal matrix multiplies by this scalar,
	 * so a matrix-shaped scalar would change the shape of the product.
	 */
	@Test(timeout = 30000)
	public void comparisonDiagonalScalarHasScalarShape() {
		int n = 4;
		GreaterThanCollection comparison = new GreaterThanCollection(shape(n, n),
				new ScalarMatrixComputation(shape(n, n), c(3.0)),
				new ScalarMatrixComputation(shape(n, n), c(2.0)),
				new ScalarMatrixComputation(shape(n, n), c(5.0)),
				new ScalarMatrixComputation(shape(n, n), c(7.0)), false);

		Optional<Computable> scalar = comparison.getDiagonalScalar(n);
		Assert.assertTrue(scalar.isPresent());

		CollectionProducer scalarProducer = (CollectionProducer) scalar.get();
		Assert.assertEquals(1, scalarProducer.getShape().getTotalSize());

		try (PackedCollection result = scalarProducer.evaluate()) {
			Assert.assertEquals(1, result.getMemLength());
			Assert.assertEquals(5.0, result.toDouble(0), 1e-6);
		}
	}

	/**
	 * Pins the fixed-count side of the comparison-operand index projection: a comparison operand
	 * whose item is narrower than the output item but whose count is fixed keeps the ordinary
	 * element-wise (repeating) read and is NOT broadcast per item. The condition is a fixed
	 * {@code (2, 1)} collection, one value per item, while the branches are fixed {@code (2, 3)}
	 * collections, three values per item. The two condition values repeat element-wise across the
	 * six output elements (alternating true and false), whereas a per-row broadcast would select
	 * the true branch for the whole first row and the false branch for the whole second row.
	 * The computation is constructed directly with a {@code (2, 3)} output so that the narrower
	 * fixed-count operand reaches the projection as-is, rather than being aligned to the output
	 * shape beforehand as {@code greaterThan(...)} does.
	 */
	@Test(timeout = 30000)
	public void greaterThanKeepsElementWiseReadForFixedCountNarrowerCondition() {
		try (PackedCollection condition = pack(1.0, -1.0).reshape(2, 1).traverse(1);
			 PackedCollection t = pack(10.0, 11.0, 12.0, 13.0, 14.0, 15.0).reshape(2, 3).traverse(1);
			 PackedCollection f = pack(20.0, 21.0, 22.0, 23.0, 24.0, 25.0).reshape(2, 3).traverse(1);
			 PackedCollection result = new GreaterThanCollection(shape(2, 3).traverse(1),
					 cp(condition), c(0.0), cp(t), cp(f)).get().evaluate()) {
			Assert.assertEquals(6, result.getMemLength());
			Assert.assertEquals(10.0, result.toDouble(0), 0.001);
			Assert.assertEquals(21.0, result.toDouble(1), 0.001);
			Assert.assertEquals(12.0, result.toDouble(2), 0.001);
			Assert.assertEquals(23.0, result.toDouble(3), 0.001);
			Assert.assertEquals(14.0, result.toDouble(4), 0.001);
			Assert.assertEquals(25.0, result.toDouble(5), 0.001);
		}
	}

	/**
	 * Pins the batched broadcast of a per-row scalar condition over vector-valued branches for
	 * strict greater-than. The condition is one value per row (a variable-count {@code (-1, 1)}
	 * input) while the true and false branches are a three-element vector per row that varies by
	 * row. Each row's single condition governs all three of that row's output channels, and the
	 * branches are read element-wise within the row. This is the batched counterpart of
	 * {@link #greaterThanSelectsTrueAndFalseValues()} and exercises the comparison-operand index
	 * projection on the left operand.
	 */
	@Test(timeout = 30000)
	public void greaterThanBroadcastsPerRowConditionOverVectorBranches() {
		Producer<PackedCollection> cond = v(shape(-1, 1), 0);
		Producer<PackedCollection> trueBranch = v(shape(-1, 3), 1);
		Producer<PackedCollection> falseBranch = v(shape(-1, 3), 2);

		PackedCollection condition = new PackedCollection(3, 1).traverse(1);
		condition.set(0, 1.0);    // 1 > 0 selects the true branch
		condition.set(1, -1.0);   // -1 > 0 is false, selects the false branch
		condition.set(2, 0.5);    // 0.5 > 0 selects the true branch

		PackedCollection trueValues =
				pack(10, 11, 12, 13, 14, 15, 16, 17, 18).reshape(3, 3).traverse(1);
		PackedCollection falseValues =
				pack(20, 21, 22, 23, 24, 25, 26, 27, 28).reshape(3, 3).traverse(1);
		PackedCollection result = new PackedCollection(3, 3);

		greaterThan(cond, c(0.0), trueBranch, falseBranch).get().into(result.each())
				.evaluate(condition, trueValues, falseValues);

		Assert.assertEquals(10.0, result.toDouble(0), 0.001);
		Assert.assertEquals(11.0, result.toDouble(1), 0.001);
		Assert.assertEquals(12.0, result.toDouble(2), 0.001);
		Assert.assertEquals(23.0, result.toDouble(3), 0.001);
		Assert.assertEquals(24.0, result.toDouble(4), 0.001);
		Assert.assertEquals(25.0, result.toDouble(5), 0.001);
		Assert.assertEquals(16.0, result.toDouble(6), 0.001);
		Assert.assertEquals(17.0, result.toDouble(7), 0.001);
		Assert.assertEquals(18.0, result.toDouble(8), 0.001);
	}

	/**
	 * Pins the batched broadcast for the inclusive less-than-or-equal operator, with a per-row
	 * scalar condition over vector-valued branches that vary by row. Row 1 exercises the inclusive
	 * boundary ({@code 0 <= 0}), confirming the per-row condition is broadcast across the whole
	 * row rather than read with the branches' stride.
	 */
	@Test(timeout = 30000)
	public void lessThanOrEqualBroadcastsPerRowConditionOverVectorBranches() {
		Producer<PackedCollection> cond = v(shape(-1, 1), 0);
		Producer<PackedCollection> trueBranch = v(shape(-1, 3), 1);
		Producer<PackedCollection> falseBranch = v(shape(-1, 3), 2);

		PackedCollection condition = new PackedCollection(3, 1).traverse(1);
		condition.set(0, -1.0);   // -1 <= 0 selects the true branch
		condition.set(1, 0.0);    // 0 <= 0 is true (inclusive), selects the true branch
		condition.set(2, 2.0);    // 2 <= 0 is false, selects the false branch

		PackedCollection trueValues =
				pack(10, 11, 12, 13, 14, 15, 16, 17, 18).reshape(3, 3).traverse(1);
		PackedCollection falseValues =
				pack(20, 21, 22, 23, 24, 25, 26, 27, 28).reshape(3, 3).traverse(1);
		PackedCollection result = new PackedCollection(3, 3);

		lessThan(cond, c(0.0), trueBranch, falseBranch, true).get().into(result.each())
				.evaluate(condition, trueValues, falseValues);

		Assert.assertEquals(10.0, result.toDouble(0), 0.001);
		Assert.assertEquals(11.0, result.toDouble(1), 0.001);
		Assert.assertEquals(12.0, result.toDouble(2), 0.001);
		Assert.assertEquals(13.0, result.toDouble(3), 0.001);
		Assert.assertEquals(14.0, result.toDouble(4), 0.001);
		Assert.assertEquals(15.0, result.toDouble(5), 0.001);
		Assert.assertEquals(26.0, result.toDouble(6), 0.001);
		Assert.assertEquals(27.0, result.toDouble(7), 0.001);
		Assert.assertEquals(28.0, result.toDouble(8), 0.001);
	}

	/**
	 * Verifies that a comparison operand narrower than the output item is rejected even when its
	 * width divides the output item evenly, because divisibility alone does not determine a unique
	 * broadcast. A two-value-per-row condition over a six-element branch could map its values to an
	 * outer axis (channels 0&ndash;2 then 3&ndash;5) or to an inner axis, and the projection cannot
	 * tell those apart from the operand's total width. Only a single per-row value is an unambiguous
	 * broadcast; anything wider is refused with a fail-loud {@link IllegalArgumentException} rather
	 * than guessing an axis mapping.
	 */
	@Test(timeout = 30000)
	public void greaterThanRejectsSubItemConditionWiderThanScalar() {
		Producer<PackedCollection> cond = v(shape(-1, 2), 0);
		Producer<PackedCollection> trueBranch = v(shape(-1, 6), 1);
		Producer<PackedCollection> falseBranch = v(shape(-1, 6), 2);

		PackedCollection condition = pack(1.0, -1.0).reshape(1, 2).traverse(1);
		PackedCollection trueValues =
				pack(10, 11, 12, 13, 14, 15).reshape(1, 6).traverse(1);
		PackedCollection falseValues =
				pack(20, 21, 22, 23, 24, 25).reshape(1, 6).traverse(1);
		PackedCollection result = new PackedCollection(1, 6);

		try {
			greaterThan(cond, c(0.0), trueBranch, falseBranch).get().into(result.each())
					.evaluate(condition, trueValues, falseValues);
			Assert.fail("A sub-item condition operand wider than a per-row scalar should be rejected");
		} catch (RuntimeException expected) {
			// The fail-loud rejection surfaces as an IllegalArgumentException, wrapped in the
			// HardwareException raised when the kernel fails to compile.
			Throwable cause = expected;
			while (cause != null && !(cause instanceof IllegalArgumentException)) {
				cause = cause.getCause();
			}
			Assert.assertNotNull(
					"Expected an IllegalArgumentException for the non-scalar narrower operand",
					cause);
			Assert.assertTrue(cause.getMessage().contains("broadcast-compatible"));
		}
	}

	/**
	 * Verifies that a comparison operand narrower than the output item is rejected with a fail-loud
	 * {@link IllegalArgumentException} rather than silently repeating and dropping condition values.
	 * The condition is two values per row while the branches are a three-element vector per row;
	 * two is neither a single per-row value nor a divisor of three, so there is no unambiguous
	 * broadcast and the projection refuses the shape mismatch.
	 */
	@Test(timeout = 30000)
	public void narrowerConditionNotDividingOutputItemIsRejected() {
		Producer<PackedCollection> cond = v(shape(-1, 2), 0);
		Producer<PackedCollection> trueBranch = v(shape(-1, 3), 1);
		Producer<PackedCollection> falseBranch = v(shape(-1, 3), 2);

		PackedCollection condition = pack(1.0, -1.0).reshape(1, 2).traverse(1);
		PackedCollection trueValues = pack(10, 11, 12).reshape(1, 3).traverse(1);
		PackedCollection falseValues = pack(20, 21, 22).reshape(1, 3).traverse(1);
		PackedCollection result = new PackedCollection(1, 3);

		try {
			greaterThan(cond, c(0.0), trueBranch, falseBranch).get().into(result.each())
					.evaluate(condition, trueValues, falseValues);
			Assert.fail("A condition operand width that does not divide the output item should be rejected");
		} catch (RuntimeException expected) {
			// The fail-loud rejection surfaces as an IllegalArgumentException, wrapped in the
			// HardwareException raised when the kernel fails to compile.
			Throwable cause = expected;
			while (cause != null && !(cause instanceof IllegalArgumentException)) {
				cause = cause.getCause();
			}
			Assert.assertNotNull(
					"Expected an IllegalArgumentException for the broadcast-incompatible operand width",
					cause);
			Assert.assertTrue(cause.getMessage().contains("broadcast-compatible"));
		}
	}
}
