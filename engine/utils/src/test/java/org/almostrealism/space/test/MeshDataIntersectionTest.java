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
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

/**
 * Behavioral tests for {@link MeshData}'s ray-triangle intersection, verifying
 * intersection distances against a known single-triangle {@link Mesh}.
 */
public class MeshDataIntersectionTest extends TestSuiteBase {

	/**
	 * Builds a single-triangle mesh in the z = 0 plane, large enough to be hit by an
	 * axis-aligned ray near the origin.
	 *
	 * @return the mesh backed by vertex data
	 */
	private Mesh planeTriangle() {
		DefaultVertexData data = new DefaultVertexData(3, 1);
		data.getVertices().set(0, new Vector(0.0, 100.0, 0.0));
		data.getVertices().set(1, new Vector(-100.0, -100.0, 0.0));
		data.getVertices().set(2, new Vector(100.0, -100.0, 0.0));
		data.setTriangle(0, 0, 1, 2);
		return new Mesh(data);
	}

	/** A ray one unit in front of the triangle reports a distance of 1.0 at triangle 0. */
	@Test(timeout = 30000)
	public void evaluateIntersectionDistance() {
		MeshData md = planeTriangle().getMeshData();

		Producer<Ray> ray = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, -1.0));
		Pair result = md.evaluateIntersection(ray.get(), new Object[0]);

		Assert.assertEquals(1.0, result.getA(), 1e-4);
		Assert.assertEquals(0.0, result.getB(), 1e-9);
	}

	/** A ray that misses the triangle yields a strictly negative intersection distance. */
	@Test(timeout = 30000)
	public void evaluateIntersectionMiss() {
		MeshData md = planeTriangle().getMeshData();

		// Origin projects outside the triangle in x/y, so the ray misses even though
		// its -z direction still points at the z = 0 plane the triangle lies in.
		Producer<Ray> ray = (Producer) ray(vector(500.0, 500.0, 1.0), vector(0.0, 0.0, -1.0));
		Pair result = md.evaluateIntersection(ray.get(), new Object[0]);

		// evaluateIntersection documents a negative distance for no intersection; a
		// zero result would mean a kernel left its output at the default and must fail.
		Assert.assertTrue("Missing ray should report a negative distance, was " + result.getA(),
				result.getA() < 0.0);
	}

	/** The scalar kernel writes the intersection distance into the destination bank. */
	@Test(timeout = 30000)
	public void evaluateIntersectionKernelScalar() {
		MeshData md = planeTriangle().getMeshData();
		PackedCollection distances = new PackedCollection(shape(1, 1).traverse(1));

		Producer<Ray> ray = (Producer) ray(vector(0.0, 1.0, 1.0), vector(0.0, 0.0, -1.0));
		md.evaluateIntersectionKernelScalar(ray.get(), distances, new MemoryData[0]);

		Assert.assertEquals(1.0, distances.get(0).toDouble(), 1e-4);
	}
}
