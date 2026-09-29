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
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.space.Plane;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link Plane}, covering orientation type handling, particle
 * vertices, normal computation, and construction defaults.
 */
public class PlaneGeometryTest extends TestSuiteBase {

	/** The default plane is an XY plane. */
	@Test(timeout = 10000)
	public void defaultTypeIsXY() {
		Plane p = new Plane();
		Assert.assertEquals(Plane.XY, p.getType());
	}

	/** The type constructor stores each valid orientation code. */
	@Test(timeout = 10000)
	public void typeConstructor() {
		Assert.assertEquals(Plane.XZ, new Plane(Plane.XZ).getType());
		Assert.assertEquals(Plane.YZ, new Plane(Plane.YZ).getType());
	}

	/** The color constructor stores both the orientation and the surface color. */
	@Test(timeout = 10000)
	public void colorConstructor() {
		Plane p = new Plane(Plane.YZ, new RGB(0.1, 0.2, 0.3));
		Assert.assertEquals(Plane.YZ, p.getType());
		Assert.assertEquals(0.2, p.getColor().getGreen(), 1e-12);
	}

	/** setType rejects codes that are not one of the three plane orientations. */
	@Test(timeout = 10000, expected = IllegalArgumentException.class)
	public void setTypeRejectsInvalidCode() {
		new Plane().setType(99);
	}

	/** Particle vertices differ per orientation, always lying in the plane. */
	@Test(timeout = 10000)
	public void particleVerticesPerOrientation() {
		// XY plane: all z == 0
		for (double[] v : new Plane(Plane.XY).getParticleVertices()) {
			Assert.assertEquals(0.0, v[2], 1e-12);
		}
		// XZ plane: all y == 0
		for (double[] v : new Plane(Plane.XZ).getParticleVertices()) {
			Assert.assertEquals(0.0, v[1], 1e-12);
		}
		// YZ plane: all x == 0
		for (double[] v : new Plane(Plane.YZ).getParticleVertices()) {
			Assert.assertEquals(0.0, v[0], 1e-12);
		}
	}

	/** The XY plane normal points along +Z. */
	@Test(timeout = 20000)
	public void xyNormalPointsAlongZ() {
		Plane p = new Plane(Plane.XY);
		Producer<PackedCollection> normal = p.getNormalAt(vector(0.0, 0.0, 0.0));
		PackedCollection n = normal.get().evaluate();
		Assert.assertEquals(0.0, n.toDouble(0), 1e-9);
		Assert.assertEquals(0.0, n.toDouble(1), 1e-9);
		Assert.assertEquals(1.0, n.toDouble(2), 1e-9);
	}

	/** The YZ plane normal points along +X. */
	@Test(timeout = 20000)
	public void yzNormalPointsAlongX() {
		Plane p = new Plane(Plane.YZ);
		Producer<PackedCollection> normal = p.getNormalAt(vector(0.0, 0.0, 0.0));
		PackedCollection n = normal.get().evaluate();
		Assert.assertEquals(1.0, n.toDouble(0), 1e-9);
		Assert.assertEquals(0.0, n.toDouble(1), 1e-9);
		Assert.assertEquals(0.0, n.toDouble(2), 1e-9);
	}

	/** expect() evaluates to zero (a point on the plane). */
	@Test(timeout = 20000)
	public void expectIsZero() {
		Plane p = new Plane(Plane.XY);
		PackedCollection result = p.expect().get().evaluate();
		Assert.assertEquals(0.0, result.toDouble(0), 1e-9);
	}
}
