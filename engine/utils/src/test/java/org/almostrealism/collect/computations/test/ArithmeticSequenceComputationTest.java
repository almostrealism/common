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

import io.almostrealism.collect.TraversalPolicy;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.ArithmeticSequenceComputation;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ArithmeticSequenceComputation} covering value correctness,
 * constructor variants, {@code multiply()}, {@code signature()}, and the
 * instruction-cache isolation regression (different constants must not share
 * a compiled kernel).
 *
 * @author Michael Murray
 */
public class ArithmeticSequenceComputationTest extends TestSuiteBase {

	// -----------------------------------------------------------------------
	// Value correctness — evaluated via the kernel pipeline
	// -----------------------------------------------------------------------

	/**
	 * Default unit-step sequence: [0, 1, 2, ..., 9].
	 */
	@Test(timeout = 30000)
	public void unitStepFromZero() {
		PackedCollection result =
				new ArithmeticSequenceComputation(shape(10), 0.0).get().evaluate();

		for (int i = 0; i < 10; i++) {
			assertEquals((double) i, result.toDouble(i));
		}
	}

	/**
	 * Unit-step sequence with a non-zero start: [5, 6, 7, 8, 9].
	 */
	@Test(timeout = 30000)
	public void unitStepFromFive() {
		PackedCollection result =
				new ArithmeticSequenceComputation(shape(5), 5.0).get().evaluate();

		for (int i = 0; i < 5; i++) {
			assertEquals(5.0 + i, result.toDouble(i));
		}
	}

	/**
	 * Even-number sequence with step 2: [0, 2, 4, 6, 8].
	 */
	@Test(timeout = 30000)
	public void stepTwo() {
		PackedCollection result =
				new ArithmeticSequenceComputation(shape(5), true, 0.0, 2.0).get().evaluate();

		for (int i = 0; i < 5; i++) {
			assertEquals(2.0 * i, result.toDouble(i));
		}
	}

	/**
	 * Non-zero initial value with custom rate: [10, 12, 14, 16, 18].
	 */
	@Test(timeout = 30000)
	public void customInitialAndRate() {
		PackedCollection result =
				new ArithmeticSequenceComputation(shape(5), true, 10.0, 2.0).get().evaluate();

		for (int i = 0; i < 5; i++) {
			assertEquals(10.0 + 2.0 * i, result.toDouble(i));
		}
	}

	/**
	 * Descending sequence with a negative rate: [100, 90, 80, 70, 60, 50].
	 */
	@Test(timeout = 30000)
	public void negativeRate() {
		PackedCollection result =
				new ArithmeticSequenceComputation(shape(6), true, 100.0, -10.0).get().evaluate();

		for (int i = 0; i < 6; i++) {
			assertEquals(100.0 - 10.0 * i, result.toDouble(i));
		}
	}

	/**
	 * Fractional rate: [0.0, 0.5, 1.0, 1.5, 2.0].
	 */
	@Test(timeout = 30000)
	public void fractionalRate() {
		PackedCollection result =
				new ArithmeticSequenceComputation(shape(5), true, 0.0, 0.5).get().evaluate();

		for (int i = 0; i < 5; i++) {
			assertEquals(0.5 * i, result.toDouble(i));
		}
	}

	/**
	 * Single-element constructor: produces a sequence whose single element equals
	 * the supplied initial value.
	 */
	@Test(timeout = 30000)
	public void singleElementConstructor() {
		double initial = 42.0;
		ArithmeticSequenceComputation seq = new ArithmeticSequenceComputation(initial);

		assertEquals(1, seq.getShape().getTotalSize());
		assertFalse("Single-element constructor should produce non-fixed-count", seq.isFixedCount());

		PackedCollection result = seq.get().evaluate();
		assertEquals(initial, result.toDouble(0));
	}

	// -----------------------------------------------------------------------
	// multiply() — both values and shape
	// -----------------------------------------------------------------------

	/**
	 * {@code multiply()} scales every element: original [1,2,3,4], scaled by 5
	 * should give [5, 10, 15, 20].
	 */
	@Test(timeout = 30000)
	public void multiplyScalesValues() {
		ArithmeticSequenceComputation original =
				new ArithmeticSequenceComputation(shape(4), 1.0);
		ArithmeticSequenceComputation scaled = original.multiply(5.0);

		PackedCollection result = scaled.get().evaluate();
		for (int i = 0; i < 4; i++) {
			assertEquals(5.0 * (1.0 + i), result.toDouble(i));
		}
	}

	/**
	 * {@code multiply()} propagates into both {@code initial} and {@code rate},
	 * so the formula {@code factor * (initial + i * rate)} holds element-wise.
	 */
	@Test(timeout = 30000)
	public void multiplyScalesBothInitialAndRate() {
		double initial = 3.0;
		double rate = 2.0;
		double factor = 4.0;
		ArithmeticSequenceComputation original =
				new ArithmeticSequenceComputation(shape(5), true, initial, rate);
		ArithmeticSequenceComputation scaled = original.multiply(factor);

		assertEquals(initial * factor, scaled.get().evaluate().toDouble(0));
		assertEquals((initial + rate) * factor, scaled.get().evaluate().toDouble(1));
	}

	/**
	 * {@code multiply(0)} produces all zeros regardless of the original values.
	 */
	@Test(timeout = 30000)
	public void multiplyByZeroProducesZeros() {
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape(6), true, 5.0, 3.0);
		PackedCollection result = seq.multiply(0.0).get().evaluate();

		for (int i = 0; i < 6; i++) {
			assertEquals(0.0, result.toDouble(i));
		}
	}

	/**
	 * {@code multiply(-1)} negates every element.
	 */
	@Test(timeout = 30000)
	public void multiplyByNegativeOne() {
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape(5), true, 10.0, 2.0);
		PackedCollection result = seq.multiply(-1.0).get().evaluate();

		for (int i = 0; i < 5; i++) {
			assertEquals(-(10.0 + 2.0 * i), result.toDouble(i));
		}
	}

	/**
	 * The result of {@code multiply()} has the same shape as the original.
	 */
	@Test(timeout = 30000)
	public void multiplyPreservesShape() {
		TraversalPolicy shape = shape(7);
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape, true, 1.0, 1.0);
		ArithmeticSequenceComputation scaled = seq.multiply(2.0);

		assertEquals(shape.getTotalSize(), scaled.getShape().getTotalSize());
	}

	// -----------------------------------------------------------------------
	// isFixedCount()
	// -----------------------------------------------------------------------

	/**
	 * The two-argument constructor (shape, initial) produces a fixed-count sequence.
	 */
	@Test(timeout = 30000)
	public void shapeInitialConstructorIsFixed() {
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape(10), 0.0);
		assertTrue("shape+initial constructor should be fixed-count", seq.isFixedCount());
	}

	/**
	 * The three-argument constructor (shape, fixedCount=false, initial) respects
	 * the explicit flag.
	 */
	@Test(timeout = 30000)
	public void explicitDynamicCount() {
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape(10), false, 0.0);
		assertFalse("Explicit fixedCount=false should produce non-fixed-count", seq.isFixedCount());
	}

	/**
	 * The four-argument constructor (shape, fixedCount=true, initial, rate) with
	 * {@code fixedCount=true} produces a fixed-count sequence.
	 */
	@Test(timeout = 30000)
	public void explicitFixedCount() {
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape(10), true, 0.0, 1.0);
		assertTrue("Explicit fixedCount=true should produce fixed-count", seq.isFixedCount());
	}

	// -----------------------------------------------------------------------
	// signature() — regression and stability
	// -----------------------------------------------------------------------

	/**
	 * Two sequences with the same shape but different rates must have different
	 * signatures, preventing kernel cache collisions.
	 *
	 * <p>This is the direct regression for the bug where {@code computeRopeFreqs}
	 * called with different {@code theta} values but the same {@code (headDim, seqLen)}
	 * returned cached results from the first call because both sequences had identical
	 * signatures.</p>
	 */
	@Test(timeout = 30000)
	public void differentRatesHaveDifferentSignatures() {
		ArithmeticSequenceComputation seqA =
				new ArithmeticSequenceComputation(shape(8), true, 0.0, 1.0);
		ArithmeticSequenceComputation seqB =
				new ArithmeticSequenceComputation(shape(8), true, 0.0, 2.0);

		String sigA = seqA.signature();
		String sigB = seqB.signature();

		Assert.assertNotNull("Signature must not be null", sigA);
		Assert.assertNotEquals(
				"Different rates must produce different signatures to prevent kernel cache collisions",
				sigA, sigB);
	}

	/**
	 * Two sequences with the same shape but different initial values must have
	 * different signatures.
	 */
	@Test(timeout = 30000)
	public void differentInitialsHaveDifferentSignatures() {
		ArithmeticSequenceComputation seqA =
				new ArithmeticSequenceComputation(shape(8), true, 0.0, 1.0);
		ArithmeticSequenceComputation seqB =
				new ArithmeticSequenceComputation(shape(8), true, 5.0, 1.0);

		Assert.assertNotEquals(
				"Different initial values must produce different signatures",
				seqA.signature(), seqB.signature());
	}

	/**
	 * Two sequences with identical parameters must have the same signature,
	 * ensuring legitimate kernel reuse is not broken.
	 */
	@Test(timeout = 30000)
	public void identicalParametersHaveSameSignature() {
		ArithmeticSequenceComputation seqA =
				new ArithmeticSequenceComputation(shape(8), true, 3.0, 1.5);
		ArithmeticSequenceComputation seqB =
				new ArithmeticSequenceComputation(shape(8), true, 3.0, 1.5);

		Assert.assertEquals(
				"Identical parameters must produce the same signature for kernel reuse",
				seqA.signature(), seqB.signature());
	}

	/**
	 * Two sequences with different shapes (but same initial and rate) must have
	 * different signatures.
	 */
	@Test(timeout = 30000)
	public void differentShapesHaveDifferentSignatures() {
		ArithmeticSequenceComputation seqA =
				new ArithmeticSequenceComputation(shape(4), true, 0.0, 1.0);
		ArithmeticSequenceComputation seqB =
				new ArithmeticSequenceComputation(shape(8), true, 0.0, 1.0);

		Assert.assertNotEquals(
				"Different shapes must produce different signatures",
				seqA.signature(), seqB.signature());
	}

	// -----------------------------------------------------------------------
	// Kernel isolation regression — different constants must not share a kernel
	// -----------------------------------------------------------------------

	/**
	 * Evaluating two sequences with different rates must return distinct values,
	 * even when evaluated in the same JVM session where the instruction cache may
	 * already hold a kernel for the same shape.
	 *
	 * <p>This is the end-to-end regression for the kernel cache collision bug:
	 * prior to the {@code signature()} fix, the second evaluation returned the
	 * same values as the first because the cached kernel embedded the first rate
	 * as a literal constant.</p>
	 */
	@Test(timeout = 30000)
	public void differentRatesProduceDifferentValues() {
		PackedCollection result1 =
				new ArithmeticSequenceComputation(shape(5), true, 0.0, 1.0).get().evaluate();
		PackedCollection result2 =
				new ArithmeticSequenceComputation(shape(5), true, 0.0, 3.0).get().evaluate();

		// Element 0 is the same (0 + 0*rate = 0 for both), check element 1+
		boolean anyDifferent = false;
		for (int i = 1; i < 5; i++) {
			if (Math.abs(result1.toDouble(i) - result2.toDouble(i)) > 1e-10) {
				anyDifferent = true;
				break;
			}
		}
		assertTrue("Sequences with different rates must produce different values " +
				"(kernel cache must not return a stale cached result)", anyDifferent);
	}

	/**
	 * Evaluating two sequences with different initial values must return distinct
	 * values.
	 */
	@Test(timeout = 30000)
	public void differentInitialsProduceDifferentValues() {
		PackedCollection result1 =
				new ArithmeticSequenceComputation(shape(5), true, 0.0, 1.0).get().evaluate();
		PackedCollection result2 =
				new ArithmeticSequenceComputation(shape(5), true, 100.0, 1.0).get().evaluate();

		Assert.assertNotEquals(
				"Sequences with different initial values must not return the same element 0",
				result1.toDouble(0), result2.toDouble(0), 1e-10);
	}

	/**
	 * Evaluating the same sequence three times in succession must return the same
	 * values each time (stability under repeated evaluation).
	 */
	@Test(timeout = 30000)
	public void repeatedEvaluationIsStable() {
		ArithmeticSequenceComputation seq =
				new ArithmeticSequenceComputation(shape(6), true, 2.0, 3.0);

		PackedCollection r1 = seq.get().evaluate();
		PackedCollection r2 = seq.get().evaluate();
		PackedCollection r3 = seq.get().evaluate();

		for (int i = 0; i < 6; i++) {
			assertEquals(r1.toDouble(i), r2.toDouble(i));
			assertEquals(r1.toDouble(i), r3.toDouble(i));
		}
	}

	// -----------------------------------------------------------------------
	// integers() factory — via CollectionFeatures
	// -----------------------------------------------------------------------

	/**
	 * {@code integers(from, to)} must produce the range {@code [from, from+1, ..., to-1]}.
	 */
	@Test(timeout = 30000)
	public void integersFactoryRange() {
		PackedCollection result = integers(3, 8).get().evaluate();
		assertEquals(5, result.getMemLength());
		for (int i = 0; i < 5; i++) {
			assertEquals(3.0 + i, result.toDouble(i));
		}
	}

	/**
	 * {@code integers(0, n)} must produce {@code [0, 1, 2, ..., n-1]}.
	 */
	@Test(timeout = 30000)
	public void integersFactoryFromZero() {
		int n = 10;
		PackedCollection result = integers(0, n).get().evaluate();
		assertEquals(n, result.getMemLength());
		for (int i = 0; i < n; i++) {
			assertEquals((double) i, result.toDouble(i));
		}
	}

	/**
	 * Multiplying an {@code integers()} result by a scale factor must apply
	 * the factor to every element.
	 */
	@Test(timeout = 30000)
	public void integersMultiplied() {
		double factor = -2.5 * Math.log(10000.0) / 8.0;
		int len = 4;
		PackedCollection result = integers(0, len).multiply(factor).get().evaluate();
		for (int i = 0; i < len; i++) {
			assertEquals(i * factor, result.toDouble(i), 1e-6);
		}
	}
}
