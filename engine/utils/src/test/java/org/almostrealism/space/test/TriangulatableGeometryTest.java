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

package org.almostrealism.space.test;

import org.almostrealism.algebra.Vector;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.TriangulatableGeometry;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link TriangulatableGeometry#triangulate()}, verifying that
 * the produced {@link Mesh} inherits the geometry's location, size, scale, and rotation.
 */
public class TriangulatableGeometryTest extends TestSuiteBase {

	/** The mesh produced by triangulate() copies the source geometry settings. */
	@Test(timeout = 10000)
	public void triangulateCopiesGeometry() {
		TriangulatableGeometry g = new TriangulatableGeometry();
		g.setLocation(new Vector(4.0, 5.0, 6.0));
		g.setSize(3.0);
		g.setScaleCoefficients(1.5, 2.5, 3.5);
		g.setRotationCoefficients(0.4, 0.5, 0.6);

		Mesh m = g.triangulate();

		Assert.assertNotNull(m);
		Assert.assertEquals(4.0, m.getLocation().getX(), 1e-12);
		Assert.assertEquals(5.0, m.getLocation().getY(), 1e-12);
		Assert.assertEquals(6.0, m.getLocation().getZ(), 1e-12);
		Assert.assertEquals(3.0, m.getSize(), 1e-12);
		Assert.assertEquals(1.5, m.getScaleCoefficients()[0], 1e-12);
		Assert.assertEquals(2.5, m.getScaleCoefficients()[1], 1e-12);
		Assert.assertEquals(3.5, m.getScaleCoefficients()[2], 1e-12);
		Assert.assertEquals(0.4, m.rotateX, 1e-12);
		Assert.assertEquals(0.5, m.rotateY, 1e-12);
		Assert.assertEquals(0.6, m.rotateZ, 1e-12);
	}

	/** triangulate() returns a fresh mesh each time, not a shared instance. */
	@Test(timeout = 10000)
	public void triangulateReturnsFreshMesh() {
		TriangulatableGeometry g = new TriangulatableGeometry();
		Mesh a = g.triangulate();
		Mesh b = g.triangulate();
		Assert.assertNotSame(a, b);
	}
}
