/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.primitives.test;

import org.almostrealism.projection.ThinLensCamera;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.Intersection;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RealizableImage;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.computations.RankedChoiceEvaluable;
import org.almostrealism.space.CachedMeshIntersectionKernel;
import org.almostrealism.space.DefaultVertexData;
import org.almostrealism.space.Mesh;
import org.almostrealism.space.MeshData;
import org.almostrealism.space.Triangle;
import org.almostrealism.CodeFeatures;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MeshIntersectionTest implements TestFeatures {
	private MeshData data;
	private Producer<Ray> ray;

	private int width, height;

	protected Mesh mesh() {
		DefaultVertexData data = new DefaultVertexData(5, 3);
		data.getVertices().set(0, new Vector(0.0, 1.0, 0.0));
		data.getVertices().set(1, new Vector(-1.0, -1.0, 0.0));
		data.getVertices().set(2, new Vector(1.0, -1.0, 0.0));
		data.getVertices().set(3, new Vector(-1.0, 1.0, -1.0));
		data.getVertices().set(4, new Vector(1.0, 1.0, -1.0));
		data.setTriangle(0, 0, 1, 2);
		data.setTriangle(1, 3, 1, 0);
		data.setTriangle(2, 0, 2, 4);

		return new Mesh(data);
	}

	protected Producer<Ray> camera() {
		ThinLensCamera c = new ThinLensCamera();
		c.setLocation(new Vector(0.0, 0.0, 10.0));
		c.setViewDirection(new Vector(0.0, 0.0, -1.0));
		c.setProjectionDimensions(c.getProjectionWidth(), c.getProjectionWidth() * 1.6);
		c.setFocalLength(0.05);
		c.setFocus(10.0);
		c.setLensRadius(0.2);

		width = 100;
		height = (int)(c.getProjectionHeight() * (width / c.getProjectionWidth()));
		return (Producer) c.rayAt((Producer) v(Pair.shape(), 0), (Producer) pair(width, height));
	}

	@Before
	public void init() {
		data = mesh().getMeshData();
		ray = camera();
	}

	@Test
	public void intersectAt() {
		if (skipKnownIssues) return;

		CachedMeshIntersectionKernel kernel = new CachedMeshIntersectionKernel(data, ray);

		PackedCollection input = RealizableImage.generateKernelInput(0, 0, width, height);
		PackedCollection distances = new PackedCollection(shape(input.getCount(), 2));
		kernel.setDimensions(width, height, 1, 1);
		kernel.into(distances).evaluate(input);

		Evaluable<Vector> closestNormal = kernel.getClosestNormal();

		int pos = 0;
		System.out.println("distance(" + pos + ") = " + distances.valueAt(pos, 0));
		Assert.assertEquals(-1.0, distances.valueAt(pos, 0), Math.pow(10, -10));

		pos = (height / 2) * width + width / 2;
		System.out.println("distance(" + pos + ") = " + distances.valueAt(pos, 0));
		Assert.assertEquals(1.0, distances.valueAt(pos, 0), Math.pow(10, -10));

		Vector n = closestNormal.evaluate(input.get(pos));
		System.out.println("normal(" + pos + ") = " + n);
		Assert.assertEquals(0.0, n.toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(0.0, n.toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(1.0, n.toDouble(2), Math.pow(10, -10));

		pos = (height / 2) * width + 3 * width / 8;
		System.out.println("distance(" + pos + ") = " + distances.valueAt(pos, 0));
		Assert.assertEquals(1.042412281036377, distances.valueAt(pos, 0), Math.pow(10, -10));

		n = closestNormal.evaluate(input.get(pos));
		System.out.println("normal(" + pos + ") = " + n);
		Assert.assertEquals(-0.6666666865348816, n.toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(0.3333333432674408, n.toDouble(1), Math.pow(10, -10));
		Assert.assertEquals(0.6666666865348816, n.toDouble(2), Math.pow(10, -10));
	}

	@Test
	public void triangleIntersectAtKernel() {
		PackedCollection in = Ray.bank(1);
		PackedCollection distances = new PackedCollection(shape(1, 2));

		in.set(0, 0.0, 0.0, 1.0, 0.0, 0.0, -1.0);
		Triangle.intersectAt.into(distances).evaluate(in, data);
		System.out.println("distance = " + distances.valueAt(0, 0));
		Assert.assertEquals(1.0, distances.valueAt(0, 0), Math.pow(10, -10));

		PackedCollection out = Pair.bank(1);
		PackedCollection conf = Pair.bank(1);
		conf.set(0, new Pair(1, Intersection.e));
		RankedChoiceEvaluable.highestRank.into(out).evaluate(distances, conf);
		System.out.println("highest rank: " + out.get(0));
		Assert.assertEquals(1.0, out.get(0).toDouble(0), Math.pow(10, -10));
		Assert.assertEquals(0.0, out.get(0).toDouble(1), Math.pow(10, -10));
	}
}
