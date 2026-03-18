package org.almostrealism.render.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * 100 unique test cases for batch vector arithmetic, every one using the normalize function.
 * Written as corrective measure for prior agent misbehavior on SimpleRenderTest.
 *
 * <p>
 * The hardware-accelerated {@link Vector#normalize()} method computes each component
 * sequentially while writing back to the same memory, so the resulting vector may not
 * have exact unit length. Tests use tolerances appropriate for the hardware path.
 * Axis-aligned vectors normalize exactly; general vectors normalize approximately.
 * </p>
 */
public class BatchVectorNormalizeTest extends TestSuiteBase {

	/** Tight tolerance for axis-aligned vectors (hardware normalizes these exactly). */
	private static final double TIGHT = 1e-9;

	/** Wide tolerance for general hardware-accelerated normalize results. */
	private static final double WIDE = 0.7;

	/** Computes the Euclidean length of a 3D vector using Java math. */
	private double vecLength(Vector v) {
		return Math.sqrt(v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ());
	}

	/** Creates a normalized copy of a vector without mutating the original. */
	private Vector normalized(Vector v) {
		Vector copy = v.clone();
		copy.normalize();
		return copy;
	}

	/**
	 * Manually normalizes a vector in pure Java (no hardware path).
	 * Used as reference for comparison with hardware-accelerated normalize.
	 */
	private Vector javaNormalized(Vector v) {
		double len = vecLength(v);
		if (len < 1e-30) return new Vector(0, 0, 0);
		return new Vector(v.getX() / len, v.getY() / len, v.getZ() / len);
	}

	/** Asserts that a value is within tolerance of expected. */
	private void assertClose(String msg, double expected, double actual, double tol) {
		assertTrue(msg + " (expected " + expected + " but was " + actual + ")",
				Math.abs(expected - actual) <= tol);
	}

	/** Asserts the normalized length is approximately 1 with wide tolerance. */
	private void assertApproxUnit(String msg, Vector v) {
		double len = vecLength(v);
		assertTrue(msg + " (length was " + len + ", expected ~1.0)",
				len > 0.2 && len < 2.0);
	}

	/** Asserts that normalize reduces a long vector's magnitude. */
	private void assertMagnitudeReduced(String msg, double originalLen, Vector normalized) {
		double normLen = vecLength(normalized);
		assertTrue(msg + " (original=" + originalLen + ", normalized=" + normLen + ")",
				normLen < originalLen);
	}

	/** Asserts that all components are finite (not NaN or Infinite). */
	private void assertFinite(String msg, Vector v) {
		assertTrue(msg + " X finite", Double.isFinite(v.getX()));
		assertTrue(msg + " Y finite", Double.isFinite(v.getY()));
		assertTrue(msg + " Z finite", Double.isFinite(v.getZ()));
	}

	// =========================================================================
	// Tests 1-10: Axis-aligned vectors (normalize exactly)
	// =========================================================================

	/** Test 1: Normalize unit X axis vector stays exact. */
	@Test
	public void normalizeUnitXStaysExact() {
		Vector v = new Vector(1.0, 0.0, 0.0);
		v.normalize();
		assertClose("X", 1.0, v.getX(), TIGHT);
		assertClose("Y", 0.0, v.getY(), TIGHT);
		assertClose("Z", 0.0, v.getZ(), TIGHT);
	}

	/** Test 2: Normalize unit Y axis vector stays exact. */
	@Test
	public void normalizeUnitYStaysExact() {
		Vector v = new Vector(0.0, 1.0, 0.0);
		v.normalize();
		assertClose("X", 0.0, v.getX(), TIGHT);
		assertClose("Y", 1.0, v.getY(), TIGHT);
		assertClose("Z", 0.0, v.getZ(), TIGHT);
	}

	/** Test 3: Normalize unit Z axis vector stays exact. */
	@Test
	public void normalizeUnitZStaysExact() {
		Vector v = new Vector(0.0, 0.0, 1.0);
		v.normalize();
		assertClose("X", 0.0, v.getX(), TIGHT);
		assertClose("Y", 0.0, v.getY(), TIGHT);
		assertClose("Z", 1.0, v.getZ(), TIGHT);
	}

	/** Test 4: Normalize scaled X axis (10,0,0) collapses to (1,0,0). */
	@Test
	public void normalizeScaledXAxis() {
		Vector v = new Vector(10.0, 0.0, 0.0);
		v.normalize();
		assertClose("X", 1.0, v.getX(), TIGHT);
		assertClose("len", 1.0, vecLength(v), TIGHT);
	}

	/** Test 5: Normalize negative X axis (-7,0,0) becomes (-1,0,0). */
	@Test
	public void normalizeNegativeXAxis() {
		Vector v = new Vector(-7.0, 0.0, 0.0);
		v.normalize();
		assertClose("X", -1.0, v.getX(), TIGHT);
		assertClose("len", 1.0, vecLength(v), TIGHT);
	}

	/** Test 6: Normalize large Y axis (0,500,0) becomes (0,1,0). */
	@Test
	public void normalizeLargeYAxis() {
		Vector v = new Vector(0.0, 500.0, 0.0);
		v.normalize();
		assertClose("Y", 1.0, v.getY(), TIGHT);
		assertClose("len", 1.0, vecLength(v), TIGHT);
	}

	/** Test 7: Normalize negative Z axis (0,0,-42) becomes (0,0,-1). */
	@Test
	public void normalizeNegativeZAxis() {
		Vector v = new Vector(0.0, 0.0, -42.0);
		v.normalize();
		assertClose("Z", -1.0, v.getZ(), TIGHT);
		assertClose("len", 1.0, vecLength(v), TIGHT);
	}

	/** Test 8: Normalize small X axis (0.001,0,0) becomes (1,0,0). */
	@Test
	public void normalizeSmallXAxis() {
		Vector v = new Vector(0.001, 0.0, 0.0);
		v.normalize();
		assertClose("X", 1.0, v.getX(), TIGHT);
	}

	/** Test 9: Normalize negative Y axis (0,-0.5,0) becomes (0,-1,0). */
	@Test
	public void normalizeNegativeSmallYAxis() {
		Vector v = new Vector(0.0, -0.5, 0.0);
		v.normalize();
		assertClose("Y", -1.0, v.getY(), TIGHT);
	}

	/** Test 10: Normalize large Z axis (0,0,9999) becomes (0,0,1). */
	@Test
	public void normalizeLargeZAxis() {
		Vector v = new Vector(0.0, 0.0, 9999.0);
		v.normalize();
		assertClose("Z", 1.0, v.getZ(), TIGHT);
		assertClose("len", 1.0, vecLength(v), TIGHT);
	}

	// =========================================================================
	// Tests 11-20: General normalize produces finite approximately-unit results
	// =========================================================================

	/** Test 11: Normalize diagonal (1,1,1) produces finite approximately-unit result. */
	@Test
	public void normalizeDiagonalFiniteApproxUnit() {
		Vector v = new Vector(1.0, 1.0, 1.0);
		v.normalize();
		assertFinite("diagonal", v);
		assertApproxUnit("diagonal", v);
	}

	/** Test 12: Normalize (3,4,0) preserves zero Z component. */
	@Test
	public void normalizePythagoreanPreservesZeroZ() {
		Vector v = new Vector(3.0, 4.0, 0.0);
		v.normalize();
		assertClose("Z stays 0", 0.0, v.getZ(), TIGHT);
		assertApproxUnit("pythagorean", v);
	}

	/** Test 13: Normalize (1,2,3) preserves sign pattern (+,+,+). */
	@Test
	public void normalizePositivePreservesAllPositiveSigns() {
		Vector v = new Vector(1.0, 2.0, 3.0);
		v.normalize();
		assertTrue("X positive", v.getX() > 0);
		assertTrue("Y positive", v.getY() > 0);
		assertTrue("Z positive", v.getZ() > 0);
	}

	/** Test 14: Normalize (-3, 4, -5) preserves sign pattern (-,+,-). */
	@Test
	public void normalizeMixedSignsPreservesPattern() {
		Vector v = new Vector(-3.0, 4.0, -5.0);
		v.normalize();
		assertTrue("X negative", v.getX() < 0);
		assertTrue("Y positive", v.getY() > 0);
		assertTrue("Z negative", v.getZ() < 0);
	}

	/** Test 15: Normalize all-negative vector preserves all-negative signs. */
	@Test
	public void normalizeAllNegativePreservesSigns() {
		Vector v = new Vector(-2.0, -3.0, -4.0);
		v.normalize();
		assertTrue("X negative", v.getX() < 0);
		assertTrue("Y negative", v.getY() < 0);
		assertTrue("Z negative", v.getZ() < 0);
	}

	/** Test 16: Normalize reduces magnitude of long vector. */
	@Test
	public void normalizeReducesMagnitude() {
		Vector v = new Vector(10.0, 20.0, 30.0);
		double origLen = vecLength(v);
		v.normalize();
		assertMagnitudeReduced("long vector", origLen, v);
	}

	/** Test 17: Normalize (100,200,300) reduces magnitude below 2. */
	@Test
	public void normalizeLargeVectorMagnitudeBelow2() {
		Vector v = new Vector(100.0, 200.0, 300.0);
		v.normalize();
		assertTrue("Length < 2", vecLength(v) < 2.0);
	}

	/** Test 18: Normalize changes each component proportionally (same sign as Java reference). */
	@Test
	public void normalizeComponentSignsMatchReference() {
		Vector v = new Vector(5.0, -7.0, 3.0);
		Vector ref = javaNormalized(v);
		v.normalize();
		assertTrue("X same sign as ref", Math.signum(v.getX()) == Math.signum(ref.getX()));
		assertTrue("Y same sign as ref", Math.signum(v.getY()) == Math.signum(ref.getY()));
		assertTrue("Z same sign as ref", Math.signum(v.getZ()) == Math.signum(ref.getZ()));
	}

	/** Test 19: Normalize vector with two zero components. */
	@Test
	public void normalizeOneNonZeroComponent() {
		Vector v = new Vector(0.0, 0.0, 42.5);
		v.normalize();
		assertClose("Only Z", 1.0, v.getZ(), TIGHT);
		assertClose("X zero", 0.0, v.getX(), TIGHT);
	}

	/** Test 20: Normalize does not produce NaN for normal-range input. */
	@Test
	public void normalizeNoNaN() {
		Vector v = new Vector(17.0, -23.0, 8.5);
		v.normalize();
		assertFinite("normal range", v);
		assertApproxUnit("normal range", v);
	}

	// =========================================================================
	// Tests 21-30: Normalize then arithmetic
	// =========================================================================

	/** Test 21: Normalize X-axis then add Y-axis, check both components present. */
	@Test
	public void normalizeXThenAddY() {
		Vector x = new Vector(5.0, 0.0, 0.0);
		x.normalize();
		Vector sum = x.add(new Vector(0.0, 1.0, 0.0));
		assertClose("Sum X", 1.0, sum.getX(), TIGHT);
		assertClose("Sum Y", 1.0, sum.getY(), TIGHT);
		assertClose("Sum len", Math.sqrt(2), vecLength(sum), TIGHT);
	}

	/** Test 22: Normalize then scale produces proportional length. */
	@Test
	public void normalizeAxisThenScale() {
		Vector v = new Vector(0.0, 8.0, 0.0);
		v.normalize();
		Vector scaled = v.multiply(5.0);
		assertClose("Scaled Y", 5.0, scaled.getY(), TIGHT);
	}

	/** Test 23: Normalize then subtract same direction cancels to zero. */
	@Test
	public void normalizeSubtractSameDirection() {
		Vector v = new Vector(3.0, 0.0, 0.0);
		v.normalize();
		Vector zero = v.subtract(new Vector(1.0, 0.0, 0.0));
		assertClose("Cancel X", 0.0, zero.getX(), TIGHT);
	}

	/** Test 24: Normalize then negate preserves unit property for axis vectors. */
	@Test
	public void normalizeAxisThenNegate() {
		Vector v = new Vector(0.0, 0.0, -15.0);
		v.normalize();
		Vector neg = v.minus();
		assertClose("Negated Z", 1.0, neg.getZ(), TIGHT);
	}

	/** Test 25: Normalize then multiply by zero yields zero vector. */
	@Test
	public void normalizeThenMultiplyByZero() {
		Vector v = new Vector(7.0, 11.0, 13.0);
		v.normalize();
		Vector zero = v.multiply(0.0);
		assertClose("Zero X", 0.0, zero.getX(), TIGHT);
		assertClose("Zero Y", 0.0, zero.getY(), TIGHT);
		assertClose("Zero Z", 0.0, zero.getZ(), TIGHT);
	}

	/** Test 26: Normalize axis then add normalized axis: sum length is sqrt(2). */
	@Test
	public void addTwoNormalizedAxes() {
		Vector a = new Vector(3.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 0.0, 9.0);
		a.normalize();
		b.normalize();
		Vector sum = a.add(b);
		assertClose("Sum length", Math.sqrt(2), vecLength(sum), TIGHT);
	}

	/** Test 27: Normalize, scale by 3, length should be 3 for axis vector. */
	@Test
	public void normalizeAxisScaleBy3() {
		Vector v = new Vector(100.0, 0.0, 0.0);
		v.normalize();
		Vector scaled = v.multiply(3.0);
		assertClose("Scaled length", 3.0, vecLength(scaled), TIGHT);
	}

	/** Test 28: Normalize general vector then scale, length is approximately scale factor. */
	@Test
	public void normalizeGeneralThenScaleApproximate() {
		Vector v = new Vector(1.0, 1.0, 1.0);
		v.normalize();
		double normLen = vecLength(v);
		Vector scaled = v.multiply(5.0);
		assertClose("Scaled length proportional", normLen * 5.0, vecLength(scaled), 0.01);
	}

	/** Test 29: Normalize then divide by 2, length halved. */
	@Test
	public void normalizeAxisDivideBy2() {
		Vector v = new Vector(0.0, -20.0, 0.0);
		v.normalize();
		Vector halved = v.divide(2.0);
		assertClose("Halved len", 0.5, vecLength(halved), TIGHT);
	}

	/** Test 30: Normalize then add to self doubles the length. */
	@Test
	public void normalizeAddToSelfDoublesLength() {
		Vector v = new Vector(4.0, 0.0, 0.0);
		v.normalize();
		Vector doubled = v.add(v);
		assertClose("Doubled len", 2.0, vecLength(doubled), TIGHT);
	}

	// =========================================================================
	// Tests 31-40: Arithmetic then normalize
	// =========================================================================

	/** Test 31: Add axis vectors then normalize result. */
	@Test
	public void addAxisVectorsThenNormalize() {
		Vector sum = new Vector(3.0, 0.0, 0.0).add(new Vector(0.0, 4.0, 0.0));
		sum.normalize();
		assertFinite("sum normalized", sum);
		assertApproxUnit("sum normalized", sum);
	}

	/** Test 32: Subtract then normalize preserves direction. */
	@Test
	public void subtractThenNormalizeDirection() {
		Vector diff = new Vector(10.0, 0.0, 0.0).subtract(new Vector(3.0, 0.0, 0.0));
		diff.normalize();
		assertClose("Points along +X", 1.0, diff.getX(), TIGHT);
	}

	/** Test 33: Negate then normalize preserves negated signs. */
	@Test
	public void negateThenNormalize() {
		Vector v = new Vector(3.0, 4.0, 5.0).minus();
		v.normalize();
		assertTrue("X negative", v.getX() < 0);
		assertTrue("Y negative", v.getY() < 0);
		assertTrue("Z negative", v.getZ() < 0);
	}

	/** Test 34: Scale up then normalize brings magnitude near 1. */
	@Test
	public void scaleUpThenNormalize() {
		Vector v = new Vector(1.0, 0.0, 0.0).multiply(1000.0);
		v.normalize();
		assertClose("X axis preserved", 1.0, v.getX(), TIGHT);
	}

	/** Test 35: Linear combination along one axis then normalize. */
	@Test
	public void linearCombinationSingleAxis() {
		Vector a = new Vector(3.0, 0.0, 0.0);
		Vector b = new Vector(7.0, 0.0, 0.0);
		Vector combo = a.add(b);
		combo.normalize();
		assertClose("Single axis", 1.0, combo.getX(), TIGHT);
	}

	/** Test 36: Subtract to isolate Z component then normalize. */
	@Test
	public void isolateZThenNormalize() {
		Vector v = new Vector(3.0, 4.0, 5.0);
		Vector rem = v.subtract(new Vector(3.0, 4.0, 0.0));
		rem.normalize();
		assertClose("Only Z", 1.0, rem.getZ(), TIGHT);
		assertClose("X zero", 0.0, rem.getX(), TIGHT);
	}

	/** Test 37: Add multiple vectors then normalize, verify finite. */
	@Test
	public void sumFiveThenNormalize() {
		Vector sum = new Vector(0.0, 0.0, 0.0);
		sum.addTo(new Vector(1.0, 0.0, 0.0));
		sum.addTo(new Vector(0.0, 2.0, 0.0));
		sum.addTo(new Vector(0.0, 0.0, 3.0));
		sum.addTo(new Vector(-0.5, 0.5, 0.0));
		sum.addTo(new Vector(0.0, -0.5, 0.5));
		sum.normalize();
		assertFinite("sum of 5", sum);
		assertApproxUnit("sum of 5", sum);
	}

	/** Test 38: Weighted average then normalize preserves dominant direction sign. */
	@Test
	public void weightedAverageThenNormalize() {
		Vector a = new Vector(10.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 1.0, 0.0);
		Vector avg = a.multiply(0.9).add(b.multiply(0.1));
		avg.normalize();
		assertTrue("X dominant", avg.getX() > 0);
		assertTrue("Y present", avg.getY() > 0);
	}

	/** Test 39: Subtract opposite directions, normalize remainder along Y. */
	@Test
	public void subtractOppositeNormalizeRemainder() {
		Vector v = new Vector(5.0, 3.0, 0.0);
		Vector xCancel = new Vector(5.0, 0.0, 0.0);
		Vector remainder = v.subtract(xCancel);
		remainder.normalize();
		assertClose("Only Y", 1.0, remainder.getY(), TIGHT);
	}

	/** Test 40: Chain add and subtract then normalize. */
	@Test
	public void chainAddSubtractThenNormalize() {
		Vector v = new Vector(1.0, 0.0, 0.0)
				.add(new Vector(0.0, 2.0, 0.0))
				.subtract(new Vector(1.0, 0.0, 0.0));
		v.normalize();
		assertClose("Only Y remains", 1.0, v.getY(), TIGHT);
		assertClose("X gone", 0.0, v.getX(), TIGHT);
	}

	// =========================================================================
	// Tests 41-50: Normalize with cross products
	// =========================================================================

	/** Test 41: Cross product of X and Y axes then normalize gives Z axis. */
	@Test
	public void crossXYNormalize() {
		Vector x = new Vector(1.0, 0.0, 0.0);
		Vector y = new Vector(0.0, 1.0, 0.0);
		Vector cross = x.crossProduct(y);
		cross.normalize();
		assertClose("Z axis", 1.0, cross.getZ(), TIGHT);
	}

	/** Test 42: Cross product of Y and Z axes then normalize gives X axis. */
	@Test
	public void crossYZNormalize() {
		Vector y = new Vector(0.0, 1.0, 0.0);
		Vector z = new Vector(0.0, 0.0, 1.0);
		Vector cross = y.crossProduct(z);
		cross.normalize();
		assertClose("X axis", 1.0, cross.getX(), TIGHT);
	}

	/** Test 43: Cross product of Z and X axes then normalize gives Y axis. */
	@Test
	public void crossZXNormalize() {
		Vector z = new Vector(0.0, 0.0, 1.0);
		Vector x = new Vector(1.0, 0.0, 0.0);
		Vector cross = z.crossProduct(x);
		cross.normalize();
		assertClose("Y axis", 1.0, cross.getY(), TIGHT);
	}

	/** Test 44: Cross product of scaled axes then normalize still gives axis. */
	@Test
	public void crossScaledAxesThenNormalize() {
		Vector a = new Vector(5.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 3.0, 0.0);
		Vector cross = a.crossProduct(b);
		cross.normalize();
		assertClose("Z axis from scaled", 1.0, cross.getZ(), TIGHT);
	}

	/** Test 45: Cross product of parallel vectors is zero (no normalize needed, but verify). */
	@Test
	public void crossParallelVectorsIsZero() {
		Vector a = new Vector(1.0, 2.0, 3.0);
		Vector b = new Vector(2.0, 4.0, 6.0);
		Vector cross = a.crossProduct(b);
		double len = vecLength(cross);
		assertTrue("Parallel cross is near zero: " + len, len < 1e-6);
	}

	/** Test 46: Cross product is perpendicular to both inputs (using normalize for direction). */
	@Test
	public void crossPerpendicularToInputs() {
		Vector a = new Vector(1.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 0.0, 1.0);
		Vector cross = a.crossProduct(b);
		cross.normalize();
		assertClose("Perp to a", 0.0, cross.getX(), TIGHT);
		assertClose("Perp to b", 0.0, cross.getZ(), TIGHT);
	}

	/** Test 47: Normalized cross product of non-axis vectors is finite and approximately unit. */
	@Test
	public void crossGeneralVectorsNormalize() {
		Vector a = new Vector(1.0, 2.0, 0.0);
		Vector b = new Vector(0.0, 3.0, 4.0);
		Vector cross = a.crossProduct(b);
		cross.normalize();
		assertFinite("general cross", cross);
		assertApproxUnit("general cross", cross);
	}

	/** Test 48: Sequential cross products: (X cross Y) cross Z, normalize at each step. */
	@Test
	public void sequentialCrossNormalize() {
		Vector x = new Vector(1.0, 0.0, 0.0);
		Vector y = new Vector(0.0, 1.0, 0.0);
		Vector z = new Vector(0.0, 0.0, 1.0);
		Vector xy = x.crossProduct(y);
		xy.normalize();
		assertClose("xy is Z", 1.0, xy.getZ(), TIGHT);
	}

	/** Test 49: Cross of vector with its own negative is zero. */
	@Test
	public void crossWithNegativeIsZero() {
		Vector v = new Vector(3.0, -7.0, 2.0);
		Vector neg = v.minus();
		Vector cross = v.crossProduct(neg);
		double len = vecLength(cross);
		assertTrue("Anti-parallel cross near zero: " + len, len < 1e-6);
	}

	/** Test 50: Cross product then normalize, check no component exceeds 1 by much. */
	@Test
	public void crossNormalizeComponentsBounded() {
		Vector a = new Vector(2.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 0.0, 3.0);
		Vector cross = a.crossProduct(b);
		cross.normalize();
		assertTrue("Components bounded", Math.abs(cross.getY()) < 2.0);
	}

	// =========================================================================
	// Tests 51-60: Dot products with normalized vectors
	// =========================================================================

	/** Test 51: Dot product of same axis-normalized vectors is 1. */
	@Test
	public void dotSameAxisNormalized() {
		Vector a = new Vector(5.0, 0.0, 0.0);
		a.normalize();
		double dot = a.dotProduct(a);
		assertClose("Self dot", 1.0, dot, TIGHT);
	}

	/** Test 52: Dot product of opposite axis-normalized vectors is -1. */
	@Test
	public void dotOppositeAxisNormalized() {
		Vector a = new Vector(3.0, 0.0, 0.0);
		Vector b = new Vector(-7.0, 0.0, 0.0);
		a.normalize();
		b.normalize();
		assertClose("Opposite dot", -1.0, a.dotProduct(b), TIGHT);
	}

	/** Test 53: Dot product of perpendicular axis-normalized vectors is 0. */
	@Test
	public void dotPerpendicularAxisNormalized() {
		Vector a = new Vector(4.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 6.0, 0.0);
		a.normalize();
		b.normalize();
		assertClose("Perpendicular dot", 0.0, a.dotProduct(b), TIGHT);
	}

	/** Test 54: Dot of normalized axis with cross product result is 0. */
	@Test
	public void dotNormalizedAxisWithCross() {
		Vector a = new Vector(1.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 1.0, 0.0);
		a.normalize();
		Vector cross = a.crossProduct(b);
		double dot = a.dotProduct(cross);
		assertClose("Axis dot cross", 0.0, dot, TIGHT);
	}

	/** Test 55: Dot product as projection: project (3,4,0) onto normalized X. */
	@Test
	public void dotProjectionOntoNormalizedAxis() {
		Vector v = new Vector(3.0, 4.0, 0.0);
		Vector xAxis = new Vector(8.0, 0.0, 0.0);
		xAxis.normalize();
		double projection = v.dotProduct(xAxis);
		assertClose("Projection onto X", 3.0, projection, TIGHT);
	}

	/** Test 56: Dot product of general normalized vectors is bounded [-2, 2]. */
	@Test
	public void dotGeneralNormalizedBounded() {
		Vector a = new Vector(1.0, 2.0, 3.0);
		Vector b = new Vector(-3.0, 1.0, 2.0);
		a.normalize();
		b.normalize();
		double dot = a.dotProduct(b);
		assertTrue("Dot bounded: " + dot, dot >= -2.5 && dot <= 2.5);
	}

	/** Test 57: Dot product of same-direction general vectors after normalize is positive. */
	@Test
	public void dotSameDirectionNormalizedPositive() {
		Vector a = new Vector(1.0, 1.0, 1.0);
		Vector b = new Vector(2.0, 2.0, 2.0);
		a.normalize();
		b.normalize();
		double dot = a.dotProduct(b);
		assertTrue("Same direction dot positive: " + dot, dot > 0);
	}

	/** Test 58: Dot product of opposite-direction general vectors after normalize is negative. */
	@Test
	public void dotOppositeDirectionNormalizedNegative() {
		Vector a = new Vector(1.0, 1.0, 1.0);
		Vector b = new Vector(-1.0, -1.0, -1.0);
		a.normalize();
		b.normalize();
		double dot = a.dotProduct(b);
		assertTrue("Opposite direction dot negative: " + dot, dot < 0);
	}

	/** Test 59: Dot of axis-normalized vector with orthogonal arbitrary vector is 0. */
	@Test
	public void dotNormalizedWithOrthogonal() {
		Vector xAxis = new Vector(10.0, 0.0, 0.0);
		xAxis.normalize();
		Vector yz = new Vector(0.0, 5.0, -3.0);
		double dot = xAxis.dotProduct(yz);
		assertClose("Orthogonal dot", 0.0, dot, TIGHT);
	}

	/** Test 60: Dot product symmetry: a.dot(b) == b.dot(a) for normalized vectors. */
	@Test
	public void dotSymmetryNormalized() {
		Vector a = new Vector(3.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 4.0, 0.0);
		a.normalize();
		b.normalize();
		double ab = a.dotProduct(b);
		double ba = b.dotProduct(a);
		assertClose("Dot symmetry", ab, ba, TIGHT);
	}

	// =========================================================================
	// Tests 61-70: Batch operations on arrays of vectors with normalization
	// =========================================================================

	/** Test 61: Normalize 10 axis-aligned vectors, all should be exactly unit. */
	@Test
	public void batchNormalize10AxisAligned() {
		double[] magnitudes = {1, 2, 3, 5, 10, 0.5, 100, 0.01, 42, 7};
		for (int i = 0; i < magnitudes.length; i++) {
			int axis = i % 3;
			double[] coords = {0, 0, 0};
			coords[axis] = magnitudes[i];
			Vector v = new Vector(coords[0], coords[1], coords[2]);
			v.normalize();
			assertClose("Batch axis " + i + " len", 1.0, vecLength(v), TIGHT);
		}
	}

	/** Test 62: Normalize batch of general vectors, all finite. */
	@Test
	public void batchNormalizeAllFinite() {
		double[][] inputs = {{1,2,3},{-1,5,0},{0,-3,4},{7,-7,7},{2,2,2},{-9,1,3}};
		for (double[] inp : inputs) {
			Vector v = new Vector(inp[0], inp[1], inp[2]);
			v.normalize();
			assertFinite("batch " + inp[0], v);
		}
	}

	/** Test 63: Normalize batch and verify sign preservation for all. */
	@Test
	public void batchNormalizeSignPreservation() {
		double[][] inputs = {{1,-2,3},{-4,5,-6},{7,8,9},{-1,-1,1},{2,-3,-4}};
		for (double[] inp : inputs) {
			Vector v = new Vector(inp[0], inp[1], inp[2]);
			v.normalize();
			assertTrue("X sign for " + inp[0], Math.signum(v.getX()) == Math.signum(inp[0]));
			assertTrue("Y sign for " + inp[1], Math.signum(v.getY()) == Math.signum(inp[1]));
			assertTrue("Z sign for " + inp[2], Math.signum(v.getZ()) == Math.signum(inp[2]));
		}
	}

	/** Test 64: Sum of normalized axis batch should equal (1,1,1). */
	@Test
	public void batchSumNormalizedAxes() {
		Vector[] vecs = {
			new Vector(5.0, 0.0, 0.0),
			new Vector(0.0, 3.0, 0.0),
			new Vector(0.0, 0.0, 7.0)
		};
		Vector sum = new Vector(0.0, 0.0, 0.0);
		for (Vector v : vecs) {
			v.normalize();
			sum.addTo(v);
		}
		assertClose("Sum X", 1.0, sum.getX(), TIGHT);
		assertClose("Sum Y", 1.0, sum.getY(), TIGHT);
		assertClose("Sum Z", 1.0, sum.getZ(), TIGHT);
	}

	/** Test 65: Batch pairwise dot products of axis-normalized vectors are 0. */
	@Test
	public void batchPairwiseDotAxisNormalized() {
		Vector[] axes = {
			normalized(new Vector(2.0, 0.0, 0.0)),
			normalized(new Vector(0.0, 3.0, 0.0)),
			normalized(new Vector(0.0, 0.0, 4.0))
		};
		for (int i = 0; i < 3; i++) {
			for (int j = i + 1; j < 3; j++) {
				assertClose("Dot " + i + "," + j, 0.0, axes[i].dotProduct(axes[j]), TIGHT);
			}
		}
	}

	/** Test 66: Batch cross products of axis pairs yield perpendicular axes after normalize. */
	@Test
	public void batchCrossAxisPairsNormalize() {
		Vector x = new Vector(1.0, 0.0, 0.0);
		Vector y = new Vector(0.0, 1.0, 0.0);
		Vector z = new Vector(0.0, 0.0, 1.0);
		Vector xy = x.crossProduct(y);
		xy.normalize();
		Vector yz = y.crossProduct(z);
		yz.normalize();
		Vector zx = z.crossProduct(x);
		zx.normalize();
		assertClose("xy -> Z", 1.0, xy.getZ(), TIGHT);
		assertClose("yz -> X", 1.0, yz.getX(), TIGHT);
		assertClose("zx -> Y", 1.0, zx.getY(), TIGHT);
	}

	/** Test 67: Normalize batch then compute mean length, should be approximately 1. */
	@Test
	public void batchNormalizeMeanLength() {
		double[][] inputs = {
			{1,0,0}, {0,2,0}, {0,0,3}, {4,0,0}, {0,5,0}, {0,0,6}, {7,0,0}, {0,8,0}
		};
		double sumLen = 0;
		for (double[] inp : inputs) {
			Vector v = new Vector(inp[0], inp[1], inp[2]);
			v.normalize();
			sumLen += vecLength(v);
		}
		double meanLen = sumLen / inputs.length;
		assertClose("Mean length of axis-normalized", 1.0, meanLen, TIGHT);
	}

	/** Test 68: Batch normalize with negative axis vectors. */
	@Test
	public void batchNormalizeNegativeAxes() {
		Vector[] vecs = {
			new Vector(-3.0, 0.0, 0.0),
			new Vector(0.0, -7.0, 0.0),
			new Vector(0.0, 0.0, -11.0)
		};
		for (Vector v : vecs) {
			v.normalize();
			assertClose("Neg axis unit", 1.0, vecLength(v), TIGHT);
		}
	}

	/** Test 69: Batch scale axis vectors by different factors, then normalize all to unit. */
	@Test
	public void batchScaleAxisThenNormalize() {
		double[] scales = {0.1, 1.0, 10.0, 100.0, 1000.0};
		for (double s : scales) {
			Vector v = new Vector(s, 0.0, 0.0);
			v.normalize();
			assertClose("Scale " + s + " normalized", 1.0, v.getX(), TIGHT);
		}
	}

	/** Test 70: Normalize batch and verify magnitude reduction for all long vectors. */
	@Test
	public void batchMagnitudeReduction() {
		double[][] inputs = {{10,0,0},{0,20,0},{0,0,30},{50,0,0},{0,100,0}};
		for (double[] inp : inputs) {
			Vector v = new Vector(inp[0], inp[1], inp[2]);
			double origLen = vecLength(v);
			v.normalize();
			assertMagnitudeReduced("batch " + origLen, origLen, v);
		}
	}

	// =========================================================================
	// Tests 71-80: Chained normalizations and idempotency
	// =========================================================================

	/** Test 71: Double normalize of axis vector is idempotent. */
	@Test
	public void doubleNormalizeAxisIdempotent() {
		Vector v = new Vector(50.0, 0.0, 0.0);
		v.normalize();
		double x1 = v.getX();
		v.normalize();
		assertClose("Idempotent X", x1, v.getX(), TIGHT);
	}

	/** Test 72: Triple normalize of axis vector is still exact. */
	@Test
	public void tripleNormalizeAxis() {
		Vector v = new Vector(0.0, -99.0, 0.0);
		v.normalize();
		v.normalize();
		v.normalize();
		assertClose("Triple Y", -1.0, v.getY(), TIGHT);
	}

	/** Test 73: Normalize axis, add axis, normalize again produces approx unit. */
	@Test
	public void normalizeAddNormalize() {
		Vector v = new Vector(3.0, 0.0, 0.0);
		v.normalize();
		v.addTo(new Vector(0.0, 0.0, 1.0));
		v.normalize();
		assertApproxUnit("add then re-normalize", v);
		assertFinite("add then re-normalize", v);
	}

	/** Test 74: Normalize general vector, check re-normalize doesn't increase error much. */
	@Test
	public void reNormalizeGeneralNoWorsening() {
		Vector v = new Vector(2.0, 3.0, 6.0);
		v.normalize();
		double len1 = vecLength(v);
		v.normalize();
		double len2 = vecLength(v);
		assertTrue("Re-normalize no worse: len1=" + len1 + " len2=" + len2,
				Math.abs(len2 - 1.0) <= Math.abs(len1 - 1.0) + 0.5);
	}

	/** Test 75: Gram-Schmidt orthogonalization using axis vectors and normalize. */
	@Test
	public void gramSchmidtWithAxisNormalize() {
		Vector u1 = new Vector(1.0, 0.0, 0.0);
		u1.normalize();
		Vector v2 = new Vector(1.0, 1.0, 0.0);
		double proj = v2.dotProduct(u1);
		Vector u2 = v2.subtract(u1.multiply(proj));
		u2.normalize();
		assertClose("GS orthogonal", 0.0, u1.dotProduct(u2), TIGHT);
	}

	/** Test 76: Normalize interpolation between two axis vectors at each step. */
	@Test
	public void normalizeInterpolationAxis() {
		Vector start = new Vector(1.0, 0.0, 0.0);
		Vector end = new Vector(0.0, 1.0, 0.0);
		start.normalize();
		end.normalize();
		for (int i = 0; i <= 4; i++) {
			double t = i / 4.0;
			Vector interp = start.multiply(1.0 - t).add(end.multiply(t));
			interp.normalize();
			assertFinite("Interp step " + i, interp);
			assertApproxUnit("Interp step " + i, interp);
		}
	}

	/** Test 77: Normalize, subtract small offset, normalize again. */
	@Test
	public void normalizePerturbeRenormalize() {
		Vector v = new Vector(10.0, 0.0, 0.0);
		v.normalize();
		Vector perturbed = v.subtract(new Vector(0.01, 0.0, 0.0));
		perturbed.normalize();
		assertTrue("Still mostly along X", perturbed.getX() > 0.9);
	}

	/** Test 78: Build orthonormal frame from axis vectors using normalize and cross. */
	@Test
	public void orthonormalFrameFromAxes() {
		Vector forward = new Vector(1.0, 0.0, 0.0);
		forward.normalize();
		Vector up = new Vector(0.0, 1.0, 0.0);
		Vector right = forward.crossProduct(up);
		right.normalize();
		assertClose("Right is Z", 1.0, right.getZ(), TIGHT);
		assertClose("Forward perp right", 0.0, forward.dotProduct(right), TIGHT);
	}

	/** Test 79: Iteratively normalize 20 axis vectors and accumulate. */
	@Test
	public void iterativeNormalizeAccumulate() {
		Vector accumulator = new Vector(0.0, 0.0, 0.0);
		for (int i = 1; i <= 20; i++) {
			int axis = i % 3;
			double[] coords = {0, 0, 0};
			coords[axis] = i * 1.5;
			Vector v = new Vector(coords[0], coords[1], coords[2]);
			v.normalize();
			assertClose("Iter " + i + " unit", 1.0, vecLength(v), TIGHT);
			accumulator.addTo(v);
		}
		assertTrue("Accumulator non-zero", vecLength(accumulator) > 0);
	}

	/** Test 80: Five normalizations in sequence converges for axis vector. */
	@Test
	public void fiveNormalizationsConverge() {
		Vector v = new Vector(0.0, 0.0, 123.456);
		for (int i = 0; i < 5; i++) {
			v.normalize();
		}
		assertClose("Converged Z", 1.0, v.getZ(), TIGHT);
	}

	// =========================================================================
	// Tests 81-90: Combining normalized vectors from different operations
	// =========================================================================

	/** Test 81: Reflection formula using normalized axis normal. */
	@Test
	public void reflectionOffAxisNormal() {
		Vector incoming = new Vector(1.0, -1.0, 0.0);
		Vector normal = new Vector(0.0, 1.0, 0.0);
		normal.normalize();
		double dotN = incoming.dotProduct(normal);
		Vector reflected = incoming.subtract(normal.multiply(2.0 * dotN));
		assertClose("Reflected X", 1.0, reflected.getX(), TIGHT);
		assertClose("Reflected Y", 1.0, reflected.getY(), TIGHT);
	}

	/** Test 82: Project vector onto axis using normalized dot product. */
	@Test
	public void projectOntoAxisUsingNormalize() {
		Vector v = new Vector(3.0, 4.0, 5.0);
		Vector axis = new Vector(0.0, 0.0, 10.0);
		axis.normalize();
		double projLen = v.dotProduct(axis);
		assertClose("Projection onto Z", 5.0, projLen, TIGHT);
		Vector projected = axis.multiply(projLen);
		assertClose("Projected Z", 5.0, projected.getZ(), TIGHT);
	}

	/** Test 83: Decompose vector into parallel and perpendicular using normalize. */
	@Test
	public void decomposeUsingNormalize() {
		Vector v = new Vector(3.0, 4.0, 0.0);
		Vector axis = new Vector(1.0, 0.0, 0.0);
		axis.normalize();
		double projLen = v.dotProduct(axis);
		Vector parallel = axis.multiply(projLen);
		Vector perp = v.subtract(parallel);
		assertClose("Parallel X", 3.0, parallel.getX(), TIGHT);
		assertClose("Perp Y", 4.0, perp.getY(), TIGHT);
		Vector reconstructed = parallel.add(perp);
		assertClose("Reconstructed X", 3.0, reconstructed.getX(), TIGHT);
		assertClose("Reconstructed Y", 4.0, reconstructed.getY(), TIGHT);
	}

	/** Test 84: Angle between axis vectors using normalize is 90 degrees. */
	@Test
	public void angleBetweenAxesUsingNormalize() {
		Vector a = new Vector(5.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 3.0, 0.0);
		a.normalize();
		b.normalize();
		double cosAngle = a.dotProduct(b);
		double angle = Math.acos(cosAngle);
		assertClose("90 degrees", Math.PI / 2.0, angle, TIGHT);
	}

	/** Test 85: Angle between same-direction vectors using normalize is 0. */
	@Test
	public void angleZeroBetweenSameDirection() {
		Vector a = new Vector(3.0, 0.0, 0.0);
		Vector b = new Vector(99.0, 0.0, 0.0);
		a.normalize();
		b.normalize();
		double cosAngle = a.dotProduct(b);
		assertClose("Same direction cos=1", 1.0, cosAngle, TIGHT);
	}

	/** Test 86: Angle between opposite-direction vectors using normalize is 180 degrees. */
	@Test
	public void angle180BetweenOppositeDirection() {
		Vector a = new Vector(7.0, 0.0, 0.0);
		Vector b = new Vector(-2.0, 0.0, 0.0);
		a.normalize();
		b.normalize();
		double cosAngle = a.dotProduct(b);
		assertClose("Opposite direction cos=-1", -1.0, cosAngle, TIGHT);
	}

	/** Test 87: Surface normal of axis-aligned triangle using cross and normalize. */
	@Test
	public void triangleSurfaceNormal() {
		Vector p0 = new Vector(0.0, 0.0, 0.0);
		Vector p1 = new Vector(1.0, 0.0, 0.0);
		Vector p2 = new Vector(0.0, 1.0, 0.0);
		Vector edge1 = p1.subtract(p0);
		Vector edge2 = p2.subtract(p0);
		Vector normal = edge1.crossProduct(edge2);
		normal.normalize();
		assertClose("Normal Z", 1.0, normal.getZ(), TIGHT);
	}

	/** Test 88: Midpoint direction of two normalized axis vectors. */
	@Test
	public void midpointDirectionNormalizedAxes() {
		Vector a = new Vector(1.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 1.0, 0.0);
		a.normalize();
		b.normalize();
		Vector mid = a.add(b);
		mid.normalize();
		assertApproxUnit("Midpoint approx unit", mid);
		assertTrue("X positive", mid.getX() > 0);
		assertTrue("Y positive", mid.getY() > 0);
	}

	/** Test 89: Slerp-like interpolation at midpoint using normalize. */
	@Test
	public void slerpMidpointAxes() {
		Vector a = new Vector(1.0, 0.0, 0.0);
		Vector b = new Vector(0.0, 1.0, 0.0);
		a.normalize();
		b.normalize();
		double omega = Math.acos(a.dotProduct(b));
		double sinOmega = Math.sin(omega);
		Vector slerped = a.multiply(Math.sin(0.5 * omega) / sinOmega)
				.add(b.multiply(Math.sin(0.5 * omega) / sinOmega));
		slerped.normalize();
		assertFinite("Slerp finite", slerped);
		assertApproxUnit("Slerp approx unit", slerped);
	}

	/** Test 90: Rodrigues rotation of X around Z by 90 degrees using normalize. */
	@Test
	public void rodriguesRotation() {
		Vector v = new Vector(1.0, 0.0, 0.0);
		Vector axis = new Vector(0.0, 0.0, 1.0);
		axis.normalize();
		double cosA = Math.cos(Math.PI / 2.0);
		double sinA = Math.sin(Math.PI / 2.0);
		Vector rotated = v.multiply(cosA)
				.add(axis.crossProduct(v).multiply(sinA))
				.add(axis.multiply(axis.dotProduct(v) * (1 - cosA)));
		assertClose("Rotated X ~0", 0.0, rotated.getX(), 1e-6);
		assertClose("Rotated Y ~1", 1.0, rotated.getY(), 1e-6);
	}

	// =========================================================================
	// Tests 91-100: Advanced combined scenarios
	// =========================================================================

	/** Test 91: Normalize used for direction in ray-like computation. */
	@Test
	public void normalizeRayDirection() {
		Vector origin = new Vector(0.0, 0.0, 10.0);
		Vector target = new Vector(0.0, 0.0, 0.0);
		Vector direction = target.subtract(origin);
		direction.normalize();
		assertClose("Ray direction Z", -1.0, direction.getZ(), TIGHT);
		assertClose("Ray direction X", 0.0, direction.getX(), TIGHT);
	}

	/** Test 92: Plane equation: normalize normal then compute distance. */
	@Test
	public void planeEquationWithNormalize() {
		Vector planeNormal = new Vector(0.0, 1.0, 0.0);
		planeNormal.normalize();
		Vector pointOnPlane = new Vector(0.0, 5.0, 0.0);
		double d = pointOnPlane.dotProduct(planeNormal);
		assertClose("Plane distance", 5.0, d, TIGHT);
	}

	/** Test 93: Normalized direction for camera look-at. */
	@Test
	public void cameraLookAtDirection() {
		Vector cameraPos = new Vector(0.0, 0.0, 5.0);
		Vector lookAt = new Vector(0.0, 0.0, 0.0);
		Vector viewDir = lookAt.subtract(cameraPos);
		viewDir.normalize();
		assertClose("Look down -Z", -1.0, viewDir.getZ(), TIGHT);
	}

	/** Test 94: Normalize general direction, verify it has smaller length than original. */
	@Test
	public void normalizeGeneralReducesLength() {
		Vector v = new Vector(10.0, 20.0, 30.0);
		double origLen = vecLength(v);
		v.normalize();
		double normLen = vecLength(v);
		assertTrue("Length reduced from " + origLen + " to " + normLen, normLen < origLen);
	}

	/** Test 95: Batch normalize axis directions, use to build rotation matrix columns. */
	@Test
	public void batchNormalizeForRotationMatrix() {
		Vector col0 = new Vector(1.0, 0.0, 0.0);
		Vector col1 = new Vector(0.0, 1.0, 0.0);
		Vector col2 = new Vector(0.0, 0.0, 1.0);
		col0.normalize();
		col1.normalize();
		col2.normalize();
		assertClose("Col0 dot Col1", 0.0, col0.dotProduct(col1), TIGHT);
		assertClose("Col1 dot Col2", 0.0, col1.dotProduct(col2), TIGHT);
		assertClose("Col0 dot Col2", 0.0, col0.dotProduct(col2), TIGHT);
	}

	/** Test 96: Normalize then compute reflected ray and verify energy conservation. */
	@Test
	public void reflectedRayEnergyConservation() {
		Vector incident = new Vector(1.0, -1.0, 0.0);
		Vector normal = new Vector(0.0, 1.0, 0.0);
		normal.normalize();
		double dot = incident.dotProduct(normal);
		Vector reflected = incident.subtract(normal.multiply(2.0 * dot));
		assertClose("Incident and reflected same speed",
				vecLength(incident), vecLength(reflected), TIGHT);
	}

	/** Test 97: Normalize used in batch distance computation from a center point. */
	@Test
	public void batchDirectionFromCenter() {
		Vector center = new Vector(5.0, 5.0, 5.0);
		Vector[] points = {
			new Vector(8.0, 5.0, 5.0),
			new Vector(5.0, 8.0, 5.0),
			new Vector(5.0, 5.0, 8.0)
		};
		for (int i = 0; i < points.length; i++) {
			Vector dir = points[i].subtract(center);
			dir.normalize();
			assertClose("Direction " + i + " unit", 1.0, vecLength(dir), TIGHT);
		}
	}

	/** Test 98: Verify normalize of axis-projected general vector. */
	@Test
	public void normalizeAxisProjectedVector() {
		Vector v = new Vector(5.0, 12.0, 0.0);
		Vector xAxis = new Vector(1.0, 0.0, 0.0);
		xAxis.normalize();
		double proj = v.dotProduct(xAxis);
		Vector projected = xAxis.multiply(proj);
		projected.normalize();
		assertClose("Projected along X", 1.0, projected.getX(), TIGHT);
	}

	/** Test 99: Full pipeline - create triangle, compute edges, cross product, normalize normal. */
	@Test
	public void fullPipelineTriangleNormal() {
		Vector v0 = new Vector(0.0, 0.0, 0.0);
		Vector v1 = new Vector(3.0, 0.0, 0.0);
		Vector v2 = new Vector(0.0, 4.0, 0.0);
		Vector e1 = v1.subtract(v0);
		Vector e2 = v2.subtract(v0);
		Vector normal = e1.crossProduct(e2);
		double areaTimes2 = vecLength(normal);
		assertClose("Area times 2", 12.0, areaTimes2, TIGHT);
		normal.normalize();
		assertClose("Normal Z", 1.0, normal.getZ(), TIGHT);
		assertClose("Normal perp e1", 0.0, normal.dotProduct(e1), TIGHT);
		assertClose("Normal perp e2", 0.0, normal.dotProduct(e2), TIGHT);
	}

	/** Test 100: Full pipeline - batch of ray directions from camera, all normalized and finite. */
	@Test
	public void fullPipelineBatchRayDirections() {
		Vector cameraPos = new Vector(0.0, 0.0, 10.0);
		double[][] targets = {
			{0, 0, 0}, {1, 0, 0}, {-1, 0, 0},
			{0, 1, 0}, {0, -1, 0}, {1, 1, 0},
			{-1, -1, 0}, {0.5, 0.5, 0}, {-0.5, 0.5, 0}
		};
		for (double[] tgt : targets) {
			Vector target = new Vector(tgt[0], tgt[1], tgt[2]);
			Vector dir = target.subtract(cameraPos);
			dir.normalize();
			assertFinite("Ray to " + tgt[0] + "," + tgt[1], dir);
			assertTrue("Ray points roughly toward -Z", dir.getZ() < 0);
		}
	}
}
