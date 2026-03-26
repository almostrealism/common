/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.render.test;

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * 100 unique test cases exercising {@link org.almostrealism.algebra.VectorFeatures#normalize}
 * through the Producer-based computation pipeline. Every test constructs vector producers
 * via {@code vector(x, y, z)}, applies {@code normalize()} and optionally other
 * {@link org.almostrealism.algebra.VectorFeatures} operations, then evaluates and asserts
 * against expected values.
 *
 * <p>Written as a corrective measure requiring use of the hardware-accelerated Producer
 * pipeline rather than the POJO {@code Vector.normalize()} method.</p>
 */
public class BatchVectorNormalizeTest extends TestSuiteBase {

	// =========================================================================
	// Tests 1-10: Normalize axis-aligned and simple unit vectors
	// =========================================================================

	/** Normalize unit X axis — should remain (1, 0, 0). */
	@Test
	public void normalizeUnitX() {
		PackedCollection result = normalize(vector(1.0, 0.0, 0.0)).evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize unit Y axis — should remain (0, 1, 0). */
	@Test
	public void normalizeUnitY() {
		PackedCollection result = normalize(vector(0.0, 1.0, 0.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize unit Z axis — should remain (0, 0, 1). */
	@Test
	public void normalizeUnitZ() {
		PackedCollection result = normalize(vector(0.0, 0.0, 1.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** Normalize negative X axis — should be (-1, 0, 0). */
	@Test
	public void normalizeNegX() {
		PackedCollection result = normalize(vector(-1.0, 0.0, 0.0)).evaluate();
		assertEquals(-1.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize negative Y axis — should be (0, -1, 0). */
	@Test
	public void normalizeNegY() {
		PackedCollection result = normalize(vector(0.0, -1.0, 0.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(-1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize negative Z axis — should be (0, 0, -1). */
	@Test
	public void normalizeNegZ() {
		PackedCollection result = normalize(vector(0.0, 0.0, -1.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(-1.0, result.toDouble(2));
	}

	/** Normalize (2, 0, 0) — should be (1, 0, 0). */
	@Test
	public void normalizeScaledX() {
		PackedCollection result = normalize(vector(2.0, 0.0, 0.0)).evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize (0, 5, 0) — should be (0, 1, 0). */
	@Test
	public void normalizeScaledY() {
		PackedCollection result = normalize(vector(0.0, 5.0, 0.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize (0, 0, 100) — should be (0, 0, 1). */
	@Test
	public void normalizeScaledZ() {
		PackedCollection result = normalize(vector(0.0, 0.0, 100.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** Normalize (0, 0, -7) — should be (0, 0, -1). */
	@Test
	public void normalizeNegScaledZ() {
		PackedCollection result = normalize(vector(0.0, 0.0, -7.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(-1.0, result.toDouble(2));
	}

	// =========================================================================
	// Tests 11-20: Normalize standard vectors with known magnitudes
	// =========================================================================

	/** Normalize (3, 4, 0) — magnitude 5. */
	@Test
	public void normalize3_4_0() {
		PackedCollection result = normalize(vector(3.0, 4.0, 0.0)).evaluate();
		assertEquals(3.0 / 5.0, result.toDouble(0));
		assertEquals(4.0 / 5.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize (0, 3, 4) — magnitude 5. */
	@Test
	public void normalize0_3_4() {
		PackedCollection result = normalize(vector(0.0, 3.0, 4.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(3.0 / 5.0, result.toDouble(1));
		assertEquals(4.0 / 5.0, result.toDouble(2));
	}

	/** Normalize (4, 0, 3) — magnitude 5. */
	@Test
	public void normalize4_0_3() {
		PackedCollection result = normalize(vector(4.0, 0.0, 3.0)).evaluate();
		assertEquals(4.0 / 5.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(3.0 / 5.0, result.toDouble(2));
	}

	/** Normalize (0, 5, 12) — magnitude 13. */
	@Test
	public void normalize0_5_12() {
		PackedCollection result = normalize(vector(0.0, 5.0, 12.0)).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(5.0 / 13.0, result.toDouble(1));
		assertEquals(12.0 / 13.0, result.toDouble(2));
	}

	/** Normalize (1, 1, 0) — magnitude sqrt(2). */
	@Test
	public void normalize1_1_0() {
		double len = Math.sqrt(2.0);
		PackedCollection result = normalize(vector(1.0, 1.0, 0.0)).evaluate();
		assertEquals(1.0 / len, result.toDouble(0));
		assertEquals(1.0 / len, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize (1, 1, 1) — magnitude sqrt(3). */
	@Test
	public void normalize1_1_1() {
		double len = Math.sqrt(3.0);
		PackedCollection result = normalize(vector(1.0, 1.0, 1.0)).evaluate();
		assertEquals(1.0 / len, result.toDouble(0));
		assertEquals(1.0 / len, result.toDouble(1));
		assertEquals(1.0 / len, result.toDouble(2));
	}

	/** Normalize (-1, -1, -1) — all components should be -1/sqrt(3). */
	@Test
	public void normalizeNeg1_1_1() {
		double len = Math.sqrt(3.0);
		PackedCollection result = normalize(vector(-1.0, -1.0, -1.0)).evaluate();
		assertEquals(-1.0 / len, result.toDouble(0));
		assertEquals(-1.0 / len, result.toDouble(1));
		assertEquals(-1.0 / len, result.toDouble(2));
	}

	/** Normalize (2, 2, 1) — magnitude 3. */
	@Test
	public void normalize2_2_1() {
		PackedCollection result = normalize(vector(2.0, 2.0, 1.0)).evaluate();
		assertEquals(2.0 / 3.0, result.toDouble(0));
		assertEquals(2.0 / 3.0, result.toDouble(1));
		assertEquals(1.0 / 3.0, result.toDouble(2));
	}

	/** Normalize (1, 2, 2) — magnitude 3. */
	@Test
	public void normalize1_2_2() {
		PackedCollection result = normalize(vector(1.0, 2.0, 2.0)).evaluate();
		assertEquals(1.0 / 3.0, result.toDouble(0));
		assertEquals(2.0 / 3.0, result.toDouble(1));
		assertEquals(2.0 / 3.0, result.toDouble(2));
	}

	/** Normalize (6, 0, 8) — magnitude 10. */
	@Test
	public void normalize6_0_8() {
		PackedCollection result = normalize(vector(6.0, 0.0, 8.0)).evaluate();
		assertEquals(0.6, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.8, result.toDouble(2));
	}

	// =========================================================================
	// Tests 21-30: Normalize result has unit length (verified via length())
	// =========================================================================

	/** Length of normalized (3, 4, 0) should be 1. */
	@Test
	public void normalizedLengthPythagorean() {
		CollectionProducer n = normalize(vector(3.0, 4.0, 0.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (1, 1, 1) should be 1. */
	@Test
	public void normalizedLengthDiagonal() {
		CollectionProducer n = normalize(vector(1.0, 1.0, 1.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (7, 3, 2) should be 1. */
	@Test
	public void normalizedLength7_3_2() {
		CollectionProducer n = normalize(vector(7.0, 3.0, 2.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (-5, 8, -1) should be 1. */
	@Test
	public void normalizedLengthMixed() {
		CollectionProducer n = normalize(vector(-5.0, 8.0, -1.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (100, 200, 300) should be 1. */
	@Test
	public void normalizedLengthLarge() {
		CollectionProducer n = normalize(vector(100.0, 200.0, 300.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** LengthSq of normalized (2, 3, 6) should be 1. */
	@Test
	public void normalizedLengthSq2_3_6() {
		CollectionProducer n = normalize(vector(2.0, 3.0, 6.0));
		PackedCollection lenSq = lengthSq(n).evaluate();
		assertEquals(1.0, lenSq.toDouble(0));
	}

	/** Length of normalized (0.001, 0.002, 0.003) should be 1. */
	@Test
	public void normalizedLengthSmall() {
		CollectionProducer n = normalize(vector(0.001, 0.002, 0.003));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (-3, -4, 0) should be 1. */
	@Test
	public void normalizedLengthNeg3_4_0() {
		CollectionProducer n = normalize(vector(-3.0, -4.0, 0.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (10, 0, 0) should be 1. */
	@Test
	public void normalizedLengthSingleAxis() {
		CollectionProducer n = normalize(vector(10.0, 0.0, 0.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Length of normalized (9, 12, 20) should be 1. */
	@Test
	public void normalizedLength9_12_20() {
		CollectionProducer n = normalize(vector(9.0, 12.0, 20.0));
		PackedCollection len = length(n).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	// =========================================================================
	// Tests 31-40: Normalize combined with add
	// =========================================================================

	/** Normalize (3,0,4) + normalize (0,5,12) — sum of two unit vectors. */
	@Test
	public void addTwoNormalized() {
		CollectionProducer sum = add(normalize(vector(3.0, 0.0, 4.0)), normalize(vector(0.0, 5.0, 12.0)));
		PackedCollection result = sum.evaluate();
		assertEquals(3.0 / 5.0 + 0.0, result.toDouble(0));
		assertEquals(0.0 + 5.0 / 13.0, result.toDouble(1));
		assertEquals(4.0 / 5.0 + 12.0 / 13.0, result.toDouble(2));
	}

	/** Normalize the sum of two vectors: normalize(a + b). */
	@Test
	public void normalizeOfSum() {
		CollectionProducer sum = add(vector(1.0, 0.0, 0.0), vector(0.0, 1.0, 0.0));
		PackedCollection result = normalize(sum).evaluate();
		double len = Math.sqrt(2.0);
		assertEquals(1.0 / len, result.toDouble(0));
		assertEquals(1.0 / len, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize(a) + a — unit direction plus original. */
	@Test
	public void normalizedPlusOriginal() {
		CollectionProducer v = vector(3.0, 4.0, 0.0);
		CollectionProducer result = add(normalize(v), v);
		PackedCollection r = result.evaluate();
		assertEquals(3.0 / 5.0 + 3.0, r.toDouble(0));
		assertEquals(4.0 / 5.0 + 4.0, r.toDouble(1));
		assertEquals(0.0, r.toDouble(2));
	}

	/** Add normalize(X axis) + normalize(Y axis) — should be (1, 1, 0). */
	@Test
	public void addNormalizedAxes() {
		CollectionProducer sum = add(normalize(vector(5.0, 0.0, 0.0)), normalize(vector(0.0, 3.0, 0.0)));
		PackedCollection result = sum.evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize(a + b + c) for three axis vectors. */
	@Test
	public void normalizeTripleSum() {
		CollectionProducer sum = add(add(vector(1.0, 0.0, 0.0), vector(0.0, 1.0, 0.0)), vector(0.0, 0.0, 1.0));
		PackedCollection result = normalize(sum).evaluate();
		double len = Math.sqrt(3.0);
		assertEquals(1.0 / len, result.toDouble(0));
		assertEquals(1.0 / len, result.toDouble(1));
		assertEquals(1.0 / len, result.toDouble(2));
	}

	/** Add two identical normalized vectors — should double the normalized vector. */
	@Test
	public void addSameNormalizedTwice() {
		CollectionProducer n = normalize(vector(1.0, 2.0, 2.0));
		CollectionProducer sum = add(n, n);
		PackedCollection result = sum.evaluate();
		assertEquals(2.0 / 3.0, result.toDouble(0));
		assertEquals(4.0 / 3.0, result.toDouble(1));
		assertEquals(4.0 / 3.0, result.toDouble(2));
	}

	/** Normalize the sum of opposite vectors scaled differently: normalize((2,0,0) + (0,2,0)). */
	@Test
	public void normalizeXYSum() {
		CollectionProducer sum = add(vector(2.0, 0.0, 0.0), vector(0.0, 2.0, 0.0));
		PackedCollection result = normalize(sum).evaluate();
		double len = Math.sqrt(8.0);
		assertEquals(2.0 / len, result.toDouble(0));
		assertEquals(2.0 / len, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize((1,1,0) + (0,0,1)) = normalize(1,1,1). */
	@Test
	public void normalizeSumToUniform() {
		CollectionProducer sum = add(vector(1.0, 1.0, 0.0), vector(0.0, 0.0, 1.0));
		PackedCollection result = normalize(sum).evaluate();
		double len = Math.sqrt(3.0);
		assertEquals(1.0 / len, result.toDouble(0));
		assertEquals(1.0 / len, result.toDouble(1));
		assertEquals(1.0 / len, result.toDouble(2));
	}

	/** normalize(v) + normalize(-v) should be (0, 0, 0). */
	@Test
	public void addNormalizedOpposites() {
		CollectionProducer sum = add(normalize(vector(3.0, 4.0, 0.0)), normalize(vector(-3.0, -4.0, 0.0)));
		PackedCollection result = sum.evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize((5,0,0) + (0,0,5)) = normalize(5,0,5). */
	@Test
	public void normalizeXZSum() {
		CollectionProducer sum = add(vector(5.0, 0.0, 0.0), vector(0.0, 0.0, 5.0));
		PackedCollection result = normalize(sum).evaluate();
		double len = Math.sqrt(50.0);
		assertEquals(5.0 / len, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(5.0 / len, result.toDouble(2));
	}

	// =========================================================================
	// Tests 41-50: Normalize combined with subtract
	// =========================================================================

	/** normalize(a - b) where a=(2,0,0), b=(0,2,0) => normalize(2,-2,0). */
	@Test
	public void normalizeDifference() {
		CollectionProducer diff = subtract(vector(2.0, 0.0, 0.0), vector(0.0, 2.0, 0.0));
		PackedCollection result = normalize(diff).evaluate();
		double len = Math.sqrt(8.0);
		assertEquals(2.0 / len, result.toDouble(0));
		assertEquals(-2.0 / len, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize(a) - normalize(b) for orthogonal vectors. */
	@Test
	public void subtractNormalized() {
		CollectionProducer diff = subtract(normalize(vector(3.0, 0.0, 0.0)), normalize(vector(0.0, 4.0, 0.0)));
		PackedCollection result = diff.evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(-1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize((4,3,0) - (4,0,0)) = normalize(0,3,0) = (0,1,0). */
	@Test
	public void normalizeDiffToAxis() {
		CollectionProducer diff = subtract(vector(4.0, 3.0, 0.0), vector(4.0, 0.0, 0.0));
		PackedCollection result = normalize(diff).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize(v) - normalize(v) = (0, 0, 0). */
	@Test
	public void subtractSameNormalized() {
		CollectionProducer n = normalize(vector(2.0, 3.0, 6.0));
		CollectionProducer diff = subtract(n, n);
		PackedCollection result = diff.evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize((5,5,5) - (5,5,0)) = normalize(0,0,5) = (0,0,1). */
	@Test
	public void normalizeDiffIsolateZ() {
		CollectionProducer diff = subtract(vector(5.0, 5.0, 5.0), vector(5.0, 5.0, 0.0));
		PackedCollection result = normalize(diff).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** normalize(a - b) for a=(1,2,3), b=(1,2,0) => normalize(0,0,3) = (0,0,1). */
	@Test
	public void normalizeDiffZOnly() {
		CollectionProducer diff = subtract(vector(1.0, 2.0, 3.0), vector(1.0, 2.0, 0.0));
		PackedCollection result = normalize(diff).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** normalize((10,0,0) - (3,0,0)) = normalize(7,0,0) = (1,0,0). */
	@Test
	public void normalizeDiffXOnly() {
		CollectionProducer diff = subtract(vector(10.0, 0.0, 0.0), vector(3.0, 0.0, 0.0));
		PackedCollection result = normalize(diff).evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Subtract normalize(1,0,0) from normalize(1,1,0) — Y component remains. */
	@Test
	public void subtractNormalizedDiffY() {
		CollectionProducer diff = subtract(normalize(vector(1.0, 1.0, 0.0)), normalize(vector(1.0, 0.0, 0.0)));
		PackedCollection result = diff.evaluate();
		assertEquals(1.0 / Math.sqrt(2.0) - 1.0, result.toDouble(0));
		assertEquals(1.0 / Math.sqrt(2.0), result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** normalize((0,6,0) - (0,0,8)) = normalize(0,6,-8), magnitude 10. */
	@Test
	public void normalizeDiffYZ() {
		CollectionProducer diff = subtract(vector(0.0, 6.0, 0.0), vector(0.0, 0.0, 8.0));
		PackedCollection result = normalize(diff).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.6, result.toDouble(1));
		assertEquals(-0.8, result.toDouble(2));
	}

	/** normalize((3,4,12) - (3,4,0)) = normalize(0,0,12) = (0,0,1). */
	@Test
	public void normalizeDiffRemoveXY() {
		CollectionProducer diff = subtract(vector(3.0, 4.0, 12.0), vector(3.0, 4.0, 0.0));
		PackedCollection result = normalize(diff).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	// =========================================================================
	// Tests 51-60: Normalize combined with multiply (scaling)
	// =========================================================================

	/** Normalize then scale by 5 — length should be 5. */
	@Test
	public void normalizeAndScale() {
		CollectionProducer scaled = multiply(normalize(vector(3.0, 4.0, 0.0)), scalar(5.0));
		PackedCollection result = scaled.evaluate();
		assertEquals(3.0, result.toDouble(0));
		assertEquals(4.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Scale by 2 then normalize — should be same as just normalize. */
	@Test
	public void scaleThenNormalize() {
		CollectionProducer scaled = multiply(vector(3.0, 4.0, 0.0), scalar(2.0));
		PackedCollection result = normalize(scaled).evaluate();
		assertEquals(3.0 / 5.0, result.toDouble(0));
		assertEquals(4.0 / 5.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize then scale by 10 — length of result should be 10. */
	@Test
	public void normalizeScaleLength() {
		CollectionProducer scaled = multiply(normalize(vector(1.0, 2.0, 2.0)), scalar(10.0));
		PackedCollection len = length(scaled).evaluate();
		assertEquals(10.0, len.toDouble(0));
	}

	/** Multiply normalize by -1 — should reverse direction. */
	@Test
	public void normalizeNegate() {
		CollectionProducer neg = multiply(normalize(vector(3.0, 4.0, 0.0)), scalar(-1.0));
		PackedCollection result = neg.evaluate();
		assertEquals(-3.0 / 5.0, result.toDouble(0));
		assertEquals(-4.0 / 5.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Element-wise multiply: normalize(a) * normalize(b). */
	@Test
	public void multiplyTwoNormalized() {
		CollectionProducer product = multiply(normalize(vector(1.0, 0.0, 0.0)), normalize(vector(0.0, 1.0, 0.0)));
		PackedCollection result = product.evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Scale (1,1,1) by 100, then normalize — still 1/sqrt(3) per component. */
	@Test
	public void largeScaleThenNormalize() {
		CollectionProducer scaled = multiply(vector(1.0, 1.0, 1.0), scalar(100.0));
		PackedCollection result = normalize(scaled).evaluate();
		double len = Math.sqrt(3.0);
		assertEquals(1.0 / len, result.toDouble(0));
		assertEquals(1.0 / len, result.toDouble(1));
		assertEquals(1.0 / len, result.toDouble(2));
	}

	/** normalize(v) * 0 should be (0, 0, 0). */
	@Test
	public void normalizeScaleZero() {
		CollectionProducer result = multiply(normalize(vector(1.0, 2.0, 3.0)), scalar(0.0));
		PackedCollection r = result.evaluate();
		assertEquals(0.0, r.toDouble(0));
		assertEquals(0.0, r.toDouble(1));
		assertEquals(0.0, r.toDouble(2));
	}

	/** Normalize then scale by 3, then measure length = 3. */
	@Test
	public void normalizeScale3Length() {
		CollectionProducer scaled = multiply(normalize(vector(2.0, 3.0, 6.0)), scalar(3.0));
		PackedCollection len = length(scaled).evaluate();
		assertEquals(3.0, len.toDouble(0));
	}

	/** Element-wise multiply: normalize(1,1,1) * (2,3,4). */
	@Test
	public void normalizeTimesVector() {
		double c = 1.0 / Math.sqrt(3.0);
		CollectionProducer product = multiply(normalize(vector(1.0, 1.0, 1.0)), vector(2.0, 3.0, 4.0));
		PackedCollection result = product.evaluate();
		assertEquals(2.0 * c, result.toDouble(0));
		assertEquals(3.0 * c, result.toDouble(1));
		assertEquals(4.0 * c, result.toDouble(2));
	}

	/** Scale by 0.5 then normalize — should be same as normalizing the original. */
	@Test
	public void halfScaleThenNormalize() {
		CollectionProducer scaled = multiply(vector(6.0, 0.0, 8.0), scalar(0.5));
		PackedCollection result = normalize(scaled).evaluate();
		assertEquals(0.6, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.8, result.toDouble(2));
	}

	// =========================================================================
	// Tests 61-70: Normalize combined with dotProduct
	// =========================================================================

	/** Dot product of two identical normalized vectors should be 1. */
	@Test
	public void dotNormalizedSelf() {
		CollectionProducer n = normalize(vector(3.0, 4.0, 0.0));
		PackedCollection dot = dotProduct(n, n).evaluate();
		assertEquals(1.0, dot.toDouble(0));
	}

	/** Dot product of two orthogonal normalized vectors should be 0. */
	@Test
	public void dotNormalizedOrthogonal() {
		CollectionProducer a = normalize(vector(1.0, 0.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 1.0, 0.0));
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(0.0, dot.toDouble(0));
	}

	/** Dot product of opposite normalized vectors should be -1. */
	@Test
	public void dotNormalizedOpposite() {
		CollectionProducer a = normalize(vector(3.0, 4.0, 0.0));
		CollectionProducer b = normalize(vector(-3.0, -4.0, 0.0));
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(-1.0, dot.toDouble(0));
	}

	/** Dot product of normalized (1,1,0) with normalized (1,0,0) — cosine of 45 degrees. */
	@Test
	public void dotNormalized45Degrees() {
		CollectionProducer a = normalize(vector(1.0, 1.0, 0.0));
		CollectionProducer b = normalize(vector(1.0, 0.0, 0.0));
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(1.0 / Math.sqrt(2.0), dot.toDouble(0));
	}

	/** Dot product of normalized XZ with normalized X — cosine 45. */
	@Test
	public void dotNormalizedXZ_X() {
		CollectionProducer a = normalize(vector(1.0, 0.0, 1.0));
		CollectionProducer b = normalize(vector(1.0, 0.0, 0.0));
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(1.0 / Math.sqrt(2.0), dot.toDouble(0));
	}

	/** Dot product of normalized (1,1,1) with itself should be 1. */
	@Test
	public void dotNormalizedDiagonalSelf() {
		CollectionProducer n = normalize(vector(1.0, 1.0, 1.0));
		PackedCollection dot = dotProduct(n, n).evaluate();
		assertEquals(1.0, dot.toDouble(0));
	}

	/** Dot product of normalized (2,3,6) with normalized (3,6,2). */
	@Test
	public void dotNormalized2_3_6_with_3_6_2() {
		CollectionProducer a = normalize(vector(2.0, 3.0, 6.0));
		CollectionProducer b = normalize(vector(3.0, 6.0, 2.0));
		double dotExpected = (2.0 * 3.0 + 3.0 * 6.0 + 6.0 * 2.0) / (7.0 * 7.0);
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(dotExpected, dot.toDouble(0));
	}

	/** Dot product of normalize(1,0,0) with normalize(0,0,1) — orthogonal, expect 0. */
	@Test
	public void dotNormalizedXZ() {
		CollectionProducer a = normalize(vector(1.0, 0.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 0.0, 1.0));
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(0.0, dot.toDouble(0));
	}

	/** Dot product of normalize(1,2,0) self — should be 1. */
	@Test
	public void dotNormalized1_2_0Self() {
		CollectionProducer n = normalize(vector(1.0, 2.0, 0.0));
		PackedCollection dot = dotProduct(n, n).evaluate();
		assertEquals(1.0, dot.toDouble(0));
	}

	/** Dot product of two normalized vectors at 60 degrees: cos(60) = 0.5. */
	@Test
	public void dotNormalized60Degrees() {
		CollectionProducer a = normalize(vector(1.0, 0.0, 0.0));
		CollectionProducer b = normalize(vector(0.5, Math.sqrt(3.0) / 2.0, 0.0));
		PackedCollection dot = dotProduct(a, b).evaluate();
		assertEquals(0.5, dot.toDouble(0));
	}

	// =========================================================================
	// Tests 71-80: Normalize combined with crossProduct
	// =========================================================================

	/** Cross product of normalized X and Y axes should be Z axis. */
	@Test
	public void crossNormalizedXY() {
		CollectionProducer a = normalize(vector(5.0, 0.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 3.0, 0.0));
		PackedCollection result = crossProduct(a, b).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** Cross product of normalized X and Z should be -Y. */
	@Test
	public void crossNormalizedXZ() {
		CollectionProducer a = normalize(vector(7.0, 0.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 0.0, 4.0));
		PackedCollection result = crossProduct(a, b).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(-1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Cross product of normalized Y and Z should be X. */
	@Test
	public void crossNormalizedYZ() {
		CollectionProducer a = normalize(vector(0.0, 2.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 0.0, 9.0));
		PackedCollection result = crossProduct(a, b).evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Cross of a normalized vector with itself should be (0, 0, 0). */
	@Test
	public void crossNormalizedSelf() {
		CollectionProducer n = normalize(vector(1.0, 2.0, 3.0));
		PackedCollection result = crossProduct(n, n).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize the cross product of two vectors — triangle normal computation. */
	@Test
	public void normalizeOfCross() {
		CollectionProducer cross = crossProduct(vector(1.0, 0.0, 0.0), vector(0.0, 1.0, 0.0));
		PackedCollection result = normalize(cross).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** Normalize cross product of (1,0,0) and (1,1,0) — perpendicular to XY plane portion. */
	@Test
	public void normalizeCrossXXY() {
		CollectionProducer cross = crossProduct(vector(1.0, 0.0, 0.0), vector(1.0, 1.0, 0.0));
		PackedCollection result = normalize(cross).evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** Cross product of normalize(1,1,0) and normalize(0,0,1). */
	@Test
	public void crossNormalizedXYandZ() {
		CollectionProducer a = normalize(vector(1.0, 1.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 0.0, 1.0));
		PackedCollection result = crossProduct(a, b).evaluate();
		double s = 1.0 / Math.sqrt(2.0);
		assertEquals(s, result.toDouble(0));
		assertEquals(-s, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Length of cross(normalize(X), normalize(Y)) should be 1 (sin 90 = 1). */
	@Test
	public void crossNormalizedLength() {
		CollectionProducer cross = crossProduct(normalize(vector(1.0, 0.0, 0.0)), normalize(vector(0.0, 1.0, 0.0)));
		PackedCollection len = length(cross).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Normalize cross of (1,2,0) and (0,0,3) — perpendicular to both. */
	@Test
	public void normalizeCross1_2_0_and_0_0_3() {
		CollectionProducer cross = crossProduct(vector(1.0, 2.0, 0.0), vector(0.0, 0.0, 3.0));
		PackedCollection result = normalize(cross).evaluate();
		PackedCollection len = length(normalize(cross)).evaluate();
		assertEquals(1.0, len.toDouble(0));
	}

	/** Cross(normalize(a), normalize(b)) perpendicular check via dot. */
	@Test
	public void crossNormalizedPerpendicularCheck() {
		CollectionProducer a = normalize(vector(1.0, 0.0, 0.0));
		CollectionProducer b = normalize(vector(0.0, 1.0, 0.0));
		CollectionProducer cross = crossProduct(a, b);
		PackedCollection dotA = dotProduct(cross, a).evaluate();
		PackedCollection dotB = dotProduct(cross, b).evaluate();
		assertEquals(0.0, dotA.toDouble(0));
		assertEquals(0.0, dotB.toDouble(0));
	}

	// =========================================================================
	// Tests 81-90: Normalize with component extraction (x, y, z)
	// =========================================================================

	/** Extract X component of normalized (3, 4, 0). */
	@Test
	public void normalizedXComponent() {
		CollectionProducer n = normalize(vector(3.0, 4.0, 0.0));
		PackedCollection result = x(n).evaluate();
		assertEquals(3.0 / 5.0, result.toDouble(0));
	}

	/** Extract Y component of normalized (3, 4, 0). */
	@Test
	public void normalizedYComponent() {
		CollectionProducer n = normalize(vector(3.0, 4.0, 0.0));
		PackedCollection result = y(n).evaluate();
		assertEquals(4.0 / 5.0, result.toDouble(0));
	}

	/** Extract Z component of normalized (3, 4, 0). */
	@Test
	public void normalizedZComponent() {
		CollectionProducer n = normalize(vector(3.0, 4.0, 0.0));
		PackedCollection result = z(n).evaluate();
		assertEquals(0.0, result.toDouble(0));
	}

	/** Extract X from normalized (1, 1, 1). */
	@Test
	public void normalizedXDiagonal() {
		CollectionProducer n = normalize(vector(1.0, 1.0, 1.0));
		PackedCollection result = x(n).evaluate();
		assertEquals(1.0 / Math.sqrt(3.0), result.toDouble(0));
	}

	/** Reconstruct normalized vector from extracted components. */
	@Test
	public void normalizedReconstructFromComponents() {
		CollectionProducer n = normalize(vector(2.0, 3.0, 6.0));
		CollectionProducer reconstructed = vector(x(n), y(n), z(n));
		PackedCollection result = reconstructed.evaluate();
		assertEquals(2.0 / 7.0, result.toDouble(0));
		assertEquals(3.0 / 7.0, result.toDouble(1));
		assertEquals(6.0 / 7.0, result.toDouble(2));
	}

	/** Sum x+y+z components of normalized (1,2,2) = (1+2+2)/3 = 5/3. */
	@Test
	public void normalizedComponentSum() {
		CollectionProducer n = normalize(vector(1.0, 2.0, 2.0));
		CollectionProducer sum = add(add(x(n), y(n)), z(n));
		PackedCollection result = sum.evaluate();
		assertEquals((1.0 + 2.0 + 2.0) / 3.0, result.toDouble(0));
	}

	/** X component of normalized unit X should be 1. */
	@Test
	public void normalizedUnitXComponent() {
		CollectionProducer n = normalize(vector(42.0, 0.0, 0.0));
		PackedCollection result = x(n).evaluate();
		assertEquals(1.0, result.toDouble(0));
	}

	/** Y component of normalized Y axis should be 1. */
	@Test
	public void normalizedUnitYComponent() {
		CollectionProducer n = normalize(vector(0.0, 17.0, 0.0));
		PackedCollection result = y(n).evaluate();
		assertEquals(1.0, result.toDouble(0));
	}

	/** Z component of normalized Z axis should be 1. */
	@Test
	public void normalizedUnitZComponent() {
		CollectionProducer n = normalize(vector(0.0, 0.0, 99.0));
		PackedCollection result = z(n).evaluate();
		assertEquals(1.0, result.toDouble(0));
	}

	/** Product of x and y components of normalize(1,1,0). */
	@Test
	public void normalizedComponentProduct() {
		CollectionProducer n = normalize(vector(1.0, 1.0, 0.0));
		CollectionProducer product = multiply(x(n), y(n));
		PackedCollection result = product.evaluate();
		assertEquals(0.5, result.toDouble(0));
	}

	// =========================================================================
	// Tests 91-100: Batch normalize and chained operations
	// =========================================================================

	/** Batch normalize two vectors using shape (2, 3). */
	@Test
	public void batchNormalizeTwoVectors() {
		PackedCollection vecs = new PackedCollection(shape(2, 3));
		vecs.setMem(0, 3.0);
		vecs.setMem(1, 0.0);
		vecs.setMem(2, 4.0);
		vecs.setMem(3, 0.0);
		vecs.setMem(4, 5.0);
		vecs.setMem(5, 12.0);
		PackedCollection result = new PackedCollection(shape(2, 3));
		normalize(c(p(vecs))).get().into(result.traverse(1)).evaluate();
		assertEquals(3.0 / 5.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(4.0 / 5.0, result.toDouble(2));
		assertEquals(0.0, result.toDouble(3));
		assertEquals(5.0 / 13.0, result.toDouble(4));
		assertEquals(12.0 / 13.0, result.toDouble(5));
	}

	/** Batch normalize length verification. */
	@Test
	public void batchNormalizedLength() {
		PackedCollection vecs = new PackedCollection(shape(2, 3));
		vecs.setMem(0, 1.0);
		vecs.setMem(1, 2.0);
		vecs.setMem(2, 2.0);
		vecs.setMem(3, 6.0);
		vecs.setMem(4, 0.0);
		vecs.setMem(5, 8.0);
		PackedCollection normalized = new PackedCollection(shape(2, 3));
		normalize(c(p(vecs))).get().into(normalized.traverse(1)).evaluate();
		PackedCollection lengths = new PackedCollection(shape(2, 1));
		length(c(p(normalized))).get().into(lengths.traverse(1)).evaluate();
		assertEquals(1.0, lengths.toDouble(0));
		assertEquals(1.0, lengths.toDouble(1));
	}

	/** Double normalize — normalizing an already-normalized vector. */
	@Test
	public void doubleNormalize() {
		CollectionProducer n = normalize(normalize(vector(3.0, 4.0, 0.0)));
		PackedCollection result = n.evaluate();
		assertEquals(3.0 / 5.0, result.toDouble(0));
		assertEquals(4.0 / 5.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize, cross, normalize pipeline — surface normal computation pattern. */
	@Test
	public void normalizeCrossNormalize() {
		CollectionProducer edge1 = subtract(vector(1.0, 0.0, 0.0), vector(0.0, 0.0, 0.0));
		CollectionProducer edge2 = subtract(vector(0.0, 1.0, 0.0), vector(0.0, 0.0, 0.0));
		CollectionProducer normal = normalize(crossProduct(edge1, edge2));
		PackedCollection result = normal.evaluate();
		assertEquals(0.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(1.0, result.toDouble(2));
	}

	/** Reflect a vector around a normalized surface normal: r = d - 2(d.n)n. */
	@Test
	public void reflectViaNormalize() {
		CollectionProducer d = vector(1.0, -1.0, 0.0);
		CollectionProducer n = normalize(vector(0.0, 1.0, 0.0));
		CollectionProducer dotDN = dotProduct(d, n);
		CollectionProducer reflection = subtract(d, multiply(multiply(n, dotDN), scalar(2.0)));
		PackedCollection result = reflection.evaluate();
		assertEquals(1.0, result.toDouble(0));
		assertEquals(1.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize direction between two points. */
	@Test
	public void normalizeDirection() {
		CollectionProducer from = vector(1.0, 2.0, 3.0);
		CollectionProducer to = vector(4.0, 6.0, 3.0);
		CollectionProducer dir = normalize(subtract(to, from));
		PackedCollection result = dir.evaluate();
		assertEquals(3.0 / 5.0, result.toDouble(0));
		assertEquals(4.0 / 5.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Projection of a onto normalized b: proj = (a.nb) * nb. */
	@Test
	public void projectViaNormalize() {
		CollectionProducer a = vector(3.0, 4.0, 0.0);
		CollectionProducer nb = normalize(vector(1.0, 0.0, 0.0));
		CollectionProducer dot = dotProduct(a, nb);
		CollectionProducer proj = multiply(nb, dot);
		PackedCollection result = proj.evaluate();
		assertEquals(3.0, result.toDouble(0));
		assertEquals(0.0, result.toDouble(1));
		assertEquals(0.0, result.toDouble(2));
	}

	/** Normalize combined with lengthSq — should be 1. */
	@Test
	public void normalizeThenLengthSq() {
		CollectionProducer n = normalize(vector(5.0, 12.0, 0.0));
		PackedCollection result = lengthSq(n).evaluate();
		assertEquals(1.0, result.toDouble(0));
	}

	/** Batch of 3 vectors: normalize and verify all unit length. */
	@Test
	public void batchNormalizeThreeVectors() {
		PackedCollection vecs = new PackedCollection(shape(3, 3));
		vecs.setMem(0, 1.0);
		vecs.setMem(1, 0.0);
		vecs.setMem(2, 0.0);
		vecs.setMem(3, 0.0);
		vecs.setMem(4, 3.0);
		vecs.setMem(5, 4.0);
		vecs.setMem(6, 2.0);
		vecs.setMem(7, 2.0);
		vecs.setMem(8, 1.0);
		PackedCollection normalized = new PackedCollection(shape(3, 3));
		normalize(c(p(vecs))).get().into(normalized.traverse(1)).evaluate();
		PackedCollection lengths = new PackedCollection(shape(3, 1));
		length(c(p(normalized))).get().into(lengths.traverse(1)).evaluate();
		assertEquals(1.0, lengths.toDouble(0));
		assertEquals(1.0, lengths.toDouble(1));
		assertEquals(1.0, lengths.toDouble(2));
	}

	/** Normalize then dot with original — should equal the original length. */
	@Test
	public void dotNormalizedWithOriginal() {
		CollectionProducer v = vector(3.0, 4.0, 0.0);
		CollectionProducer n = normalize(v);
		PackedCollection dot = dotProduct(n, v).evaluate();
		assertEquals(5.0, dot.toDouble(0));
	}
}
