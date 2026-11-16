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

package org.almostrealism.primitives.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.primitives.CSG;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Tests {@link CSG}: the interval arithmetic used by its boolean combinations, the validation of
 * the operation code, the union intersection, and the operations that are not yet implemented.
 */
public class CSGTest extends TestSuiteBase {
	/** The tolerance for comparing interval endpoints. */
	private static final double TOLERANCE = 1e-9;

	/** An empty set of distances has no interval, which is reported as a degenerate {@code {0, 0}}. */
	@Test(timeout = 30000)
	public void intervalOfEmptyIsDegenerate() {
		double[] i = new CSG(new Sphere(), new Sphere(), CSG.UNION).interval(new double[0]);
		assertEquals(0.0, i[0], TOLERANCE);
		assertEquals(0.0, i[1], TOLERANCE);
	}

	/** The interval of several distances runs from the smallest to the largest. */
	@Test(timeout = 30000)
	public void intervalSpansMinToMax() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.UNION);

		double[] single = csg.interval(new double[] { 3.0 });
		assertEquals(3.0, single[0], TOLERANCE);
		assertEquals(3.0, single[1], TOLERANCE);

		double[] many = csg.interval(new double[] { 5.0, 1.0, 3.0, 9.0, 2.0 });
		assertEquals(1.0, many[0], TOLERANCE);
		assertEquals(9.0, many[1], TOLERANCE);
	}

	/** The difference of an interval and one lying strictly inside it leaves the two ends. */
	@Test(timeout = 30000)
	public void intervalDifferenceLeavesEnds() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.DIFFERENCE);
		double[][] d = csg.intervalDifference(new double[] { 0.0, 10.0 }, new double[] { 3.0, 7.0 });

		assertEquals(0.0, d[0][0], TOLERANCE);
		assertEquals(3.0, d[0][1], TOLERANCE);
		assertEquals(7.0, d[1][0], TOLERANCE);
		assertEquals(10.0, d[1][1], TOLERANCE);
	}

	/** Subtracting an interval that covers the whole of another erases both ends. */
	@Test(timeout = 30000)
	public void intervalDifferenceOfCoveredIsEmpty() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.DIFFERENCE);
		double[][] d = csg.intervalDifference(new double[] { 5.0, 6.0 }, new double[] { 0.0, 10.0 });

		assertEquals(0.0, d[0][0], TOLERANCE);
		assertEquals(0.0, d[0][1], TOLERANCE);
		assertEquals(0.0, d[1][0], TOLERANCE);
		assertEquals(0.0, d[1][1], TOLERANCE);
	}

	/** The intersection of two overlapping intervals is their common span. */
	@Test(timeout = 30000)
	public void intervalIntersectionIsCommonSpan() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.INTERSECTION);
		double[] i = csg.intervalIntersection(new double[] { 0.0, 10.0 }, new double[] { 3.0, 7.0 });

		assertEquals(3.0, i[0], TOLERANCE);
		assertEquals(7.0, i[1], TOLERANCE);
	}

	/** The type code must be one of union, difference or intersection. */
	@Test(timeout = 30000, expected = IllegalArgumentException.class)
	public void illegalTypeIsRejected() {
		new CSG(new Sphere(), new Sphere(), 7);
	}

	/** A CSG has no single normal, so it returns null, and it is not itself evaluable. */
	@Test(timeout = 30000)
	public void normalAndOperatorsAreNull() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.UNION);
		assertTrue(csg.getNormalAt(vector(0.0, 0.0, 0.0)) == null);
		assertTrue(csg.expect() == null);
		assertTrue(csg.get() == null);
	}

	/** A union reports the closest intersection of its two operands. */
	@Test(timeout = 30000)
	public void unionIntersectsClosestOperand() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.UNION);

		ContinuousField f = csg.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, -1.0));
		assertTrue(f != null);

		// The ray enters the unit sphere at (0, 0, 1), the nearer of the two hits (distance 2, not 4).
		Vector hit = new Vector(f.get(0).get().evaluate(), 0);
		assertEquals(0.0, hit.getX(), TOLERANCE);
		assertEquals(0.0, hit.getY(), TOLERANCE);
		assertEquals(1.0, hit.getZ(), TOLERANCE);
	}

	/** The difference operation is declared but not yet implemented. */
	@Test(timeout = 30000, expected = RuntimeException.class)
	public void differenceIsNotImplemented() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.DIFFERENCE);
		csg.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, -1.0));
	}

	/** The intersection operation is declared but not yet implemented. */
	@Test(timeout = 30000, expected = RuntimeException.class)
	public void intersectionIsNotImplemented() {
		CSG csg = new CSG(new Sphere(), new Sphere(), CSG.INTERSECTION);
		csg.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, -1.0));
	}
}
