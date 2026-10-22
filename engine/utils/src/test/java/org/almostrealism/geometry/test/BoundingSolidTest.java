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

package org.almostrealism.geometry.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.geometry.Positioned;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link BoundingSolid} can actually be constructed and used.
 *
 * <p>These tests pin down two independent defects: the construction path that
 * every caller of {@link BoundingSolid} depends on (reachable in production via
 * {@link Sphere#calculateBoundingSolid()}), and the running-maximum seed used by
 * {@link BoundingSolid#getBounds(Positioned[])} for point sets whose coordinates
 * are not all positive.</p>
 */
public class BoundingSolidTest extends TestSuiteBase {

	/** A minimal immutable {@link Positioned} used only to feed {@code getBounds}. */
	private static final class Point implements Positioned {
		/** The {x, y, z} position of this point. */
		private final float[] p;

		/**
		 * Constructs a point at the given coordinates.
		 *
		 * @param x the x-coordinate
		 * @param y the y-coordinate
		 * @param z the z-coordinate
		 */
		Point(double x, double y, double z) {
			p = new float[] { (float) x, (float) y, (float) z };
		}

		@Override
		public void setPosition(float x, float y, float z) {
			p[0] = x;
			p[1] = y;
			p[2] = z;
		}

		@Override
		public float[] getPosition() {
			return p;
		}
	}

	/**
	 * The construction path reachable from production code: a sphere must be able
	 * to report its axis-aligned bounding box. Before the fix this threw
	 * {@link UnsupportedOperationException} from the {@link BoundingSolid}
	 * constructor.
	 */
	@Test(timeout = 10000)
	public void sphereBoundingSolidHasCorrectExtents() {
		Sphere sphere = new Sphere(new Vector(1.0, 2.0, 3.0), 2.0);
		BoundingSolid bounds = sphere.calculateBoundingSolid();

		Assert.assertEquals(-1.0, bounds.minX, 1e-9);
		Assert.assertEquals(3.0, bounds.maxX, 1e-9);
		Assert.assertEquals(0.0, bounds.minY, 1e-9);
		Assert.assertEquals(4.0, bounds.maxY, 1e-9);
		Assert.assertEquals(1.0, bounds.minZ, 1e-9);
		Assert.assertEquals(5.0, bounds.maxZ, 1e-9);

		Assert.assertEquals(4.0, bounds.dx, 1e-9);
		Assert.assertEquals(4.0, bounds.dy, 1e-9);
		Assert.assertEquals(4.0, bounds.dz, 1e-9);

		Assert.assertNotNull(bounds.center);
		Assert.assertEquals(1.0, bounds.center.getX(), 1e-9);
		Assert.assertEquals(2.0, bounds.center.getY(), 1e-9);
		Assert.assertEquals(3.0, bounds.center.getZ(), 1e-9);
	}

	/**
	 * The direct constructor must compute the centre as the midpoint of the
	 * extents rather than throwing.
	 */
	@Test(timeout = 10000)
	public void constructorComputesCenterMidpoint() {
		BoundingSolid bounds = new BoundingSolid(-2.0, 4.0, 0.0, 10.0, -6.0, -2.0);

		Assert.assertEquals(1.0, bounds.center.getX(), 1e-9);
		Assert.assertEquals(5.0, bounds.center.getY(), 1e-9);
		Assert.assertEquals(-4.0, bounds.center.getZ(), 1e-9);
		Assert.assertEquals(6.0 * 10.0 * 4.0, bounds.volume, 1e-9);
	}

	/**
	 * {@code getBounds} must return the true extents even when every coordinate is
	 * negative. The running maxima were seeded with {@link Double#MIN_VALUE} (the
	 * smallest <em>positive</em> double), so for an all-negative point set the
	 * computed maximum collapsed to that tiny positive value instead of the real
	 * (negative) maximum.
	 */
	@Test(timeout = 10000)
	public void getBoundsHandlesNegativeCoordinates() {
		Positioned[] points = {
				new Point(-5.0, -5.0, -5.0),
				new Point(-1.0, -2.0, -3.0)
		};

		BoundingSolid bounds = BoundingSolid.getBounds(points);

		Assert.assertEquals(-5.0, bounds.minX, 1e-9);
		Assert.assertEquals(-1.0, bounds.maxX, 1e-9);
		Assert.assertEquals(-5.0, bounds.minY, 1e-9);
		Assert.assertEquals(-2.0, bounds.maxY, 1e-9);
		Assert.assertEquals(-5.0, bounds.minZ, 1e-9);
		Assert.assertEquals(-3.0, bounds.maxZ, 1e-9);
	}
}
