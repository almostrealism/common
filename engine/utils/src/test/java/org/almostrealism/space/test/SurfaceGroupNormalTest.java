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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.space.SurfaceGroup;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link SurfaceGroup#getNormalAt(Producer)}, verifying that the
 * method correctly delegates to the child surface containing the queried point.
 */
public class SurfaceGroupNormalTest extends TestSuiteBase {

	/** Verifies that an empty group returns null for any query point. */
	@Test(timeout = 5000)
	public void emptyGroupReturnsNull() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		Producer<PackedCollection> normal = group.getNormalAt(vector(1.0, 0.0, 0.0));
		Assert.assertNull("Empty group should return null", normal);
	}

	/** Verifies that a group with a single sphere returns the correct outward normal. */
	@Test(timeout = 5000)
	public void singleSphereNormal() {
		Sphere sphere = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0);
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		group.addSurface(sphere);

		Producer<PackedCollection> point = vector(1.0, 0.0, 0.0);
		Producer<PackedCollection> normal = group.getNormalAt(point);
		Assert.assertNotNull("Normal should not be null", normal);

		PackedCollection result = normal.get().evaluate();
		Assert.assertNotNull("Evaluated normal should not be null", result);

		double nx = result.toDouble(0);
		double ny = result.toDouble(1);
		double nz = result.toDouble(2);

		Assert.assertEquals("Normal X", 1.0, nx, 0.01);
		Assert.assertEquals("Normal Y", 0.0, ny, 0.01);
		Assert.assertEquals("Normal Z", 0.0, nz, 0.01);
	}

	/** Verifies that the closest child sphere is selected when two spheres are present. */
	@Test(timeout = 5000)
	public void twoSpheresSelectsCorrectChild() {
		Sphere sphereA = new Sphere(new Vector(5.0, 0.0, 0.0), 1.0);
		Sphere sphereB = new Sphere(new Vector(-5.0, 0.0, 0.0), 1.0);
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		group.addSurface(sphereA);
		group.addSurface(sphereB);

		// Point on sphere A: (6, 0, 0) is on the +X surface
		// Expected normal: (6-5, 0, 0) = (1, 0, 0)
		Producer<PackedCollection> normalA = group.getNormalAt(vector(6.0, 0.0, 0.0));
		PackedCollection resultA = normalA.get().evaluate();
		Assert.assertEquals("Sphere A normal X", 1.0, resultA.toDouble(0), 0.01);
		Assert.assertEquals("Sphere A normal Y", 0.0, resultA.toDouble(1), 0.01);
		Assert.assertEquals("Sphere A normal Z", 0.0, resultA.toDouble(2), 0.01);

		// Point on sphere B: (-6, 0, 0) is on the -X surface
		// Expected normal: (-6-(-5), 0, 0) = (-1, 0, 0)
		Producer<PackedCollection> normalB = group.getNormalAt(vector(-6.0, 0.0, 0.0));
		PackedCollection resultB = normalB.get().evaluate();
		Assert.assertEquals("Sphere B normal X", -1.0, resultB.toDouble(0), 0.01);
		Assert.assertEquals("Sphere B normal Y", 0.0, resultB.toDouble(1), 0.01);
		Assert.assertEquals("Sphere B normal Z", 0.0, resultB.toDouble(2), 0.01);
	}

	/** Verifies correct child selection along the Y axis. */
	@Test(timeout = 5000)
	public void twoSpheresYAxis() {
		Sphere top = new Sphere(new Vector(0.0, 5.0, 0.0), 1.0);
		Sphere bottom = new Sphere(new Vector(0.0, -5.0, 0.0), 1.0);
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		group.addSurface(top);
		group.addSurface(bottom);

		// Point on top sphere: (0, 6, 0) → normal = (0, 1, 0)
		Producer<PackedCollection> normalTop = group.getNormalAt(vector(0.0, 6.0, 0.0));
		PackedCollection resultTop = normalTop.get().evaluate();
		Assert.assertEquals("Top normal X", 0.0, resultTop.toDouble(0), 0.01);
		Assert.assertEquals("Top normal Y", 1.0, resultTop.toDouble(1), 0.01);
		Assert.assertEquals("Top normal Z", 0.0, resultTop.toDouble(2), 0.01);

		// Point on bottom sphere: (0, -6, 0) → normal = (0, -1, 0)
		Producer<PackedCollection> normalBot = group.getNormalAt(vector(0.0, -6.0, 0.0));
		PackedCollection resultBot = normalBot.get().evaluate();
		Assert.assertEquals("Bottom normal X", 0.0, resultBot.toDouble(0), 0.01);
		Assert.assertEquals("Bottom normal Y", -1.0, resultBot.toDouble(1), 0.01);
		Assert.assertEquals("Bottom normal Z", 0.0, resultBot.toDouble(2), 0.01);
	}

	/** Verifies correct normal computation for a point at 45 degrees on a unit sphere. */
	@Test(timeout = 5000)
	public void diagonalSphereNormal() {
		Sphere sphere = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0);
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		group.addSurface(sphere);

		// Point on sphere at 45 degrees in XY plane:
		// (1/sqrt(2), 1/sqrt(2), 0) → normal = same direction
		double d = 1.0 / Math.sqrt(2.0);
		Producer<PackedCollection> normal = group.getNormalAt(vector(d, d, 0.0));
		PackedCollection result = normal.get().evaluate();

		Assert.assertEquals("Diagonal normal X", d, result.toDouble(0), 0.01);
		Assert.assertEquals("Diagonal normal Y", d, result.toDouble(1), 0.01);
		Assert.assertEquals("Diagonal normal Z", 0.0, result.toDouble(2), 0.01);
	}
}
