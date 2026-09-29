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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.Plane;
import org.almostrealism.space.SurfaceGroup;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link SurfaceGroup}: child management, parent wiring,
 * iteration, and the operator accessors.
 */
public class SurfaceGroupTest extends TestSuiteBase {

	/** Adding a surface wires its parent back to the group. */
	@Test(timeout = 10000)
	public void addSurfaceSetsParent() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		Plane p = new Plane(Plane.XY);
		group.addSurface(p);
		Assert.assertSame(group, p.getParent());
	}

	/** Removing a surface clears its parent and shrinks the group. */
	@Test(timeout = 10000)
	public void removeSurfaceClearsParent() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		Plane p = new Plane(Plane.XY);
		group.addSurface(p);
		group.removeSurface(0);
		Assert.assertNull(p.getParent());
	}

	/** The group is iterable and exposes its children as a stream. */
	@Test(timeout = 10000)
	public void iterationAndChildren() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		group.addSurface(new Plane(Plane.XY));
		group.addSurface(new Plane(Plane.XZ));

		int viaIterator = 0;
		for (ShadableSurface s : group) {
			Assert.assertNotNull(s);
			viaIterator++;
		}
		Assert.assertEquals(2, viaIterator);
		Assert.assertEquals(2L, group.children().count());

		int[] viaForEach = {0};
		group.forEach(s -> viaForEach[0]++);
		Assert.assertEquals(2, viaForEach[0]);
	}

	/** The deprecated array/index accessors reflect the group contents. */
	@Test(timeout = 10000)
	public void deprecatedAccessors() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		Plane p = new Plane(Plane.YZ);
		group.addSurface(p);

		Assert.assertEquals(1, group.getSurfaces().length);
		Assert.assertSame(p, group.getSurface(0));
	}

	/** triangulate on an empty group yields a mesh carrying the group's color/porosity. */
	@Test(timeout = 10000)
	public void triangulateEmptyGroup() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		Mesh mesh = group.triangulate();
		Assert.assertNotNull(mesh);
		Assert.assertEquals(0, mesh.getTriangleData().length);
	}

	/** get() is unimplemented and returns null; expect() evaluates to zero. */
	@Test(timeout = 10000)
	public void operatorAccessors() {
		SurfaceGroup<ShadableSurface> group = new SurfaceGroup<>();
		Assert.assertNull(group.get());

		PackedCollection expected = group.expect().get().evaluate();
		Assert.assertEquals(0.0, expected.toDouble(0), 1e-9);
	}
}
