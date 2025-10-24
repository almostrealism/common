/*
 * Copyright 2023 Michael Murray
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

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.geometry.Ray;
import org.almostrealism.projection.OrthographicCamera;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

public class SphereTest implements TestFeatures {
	@Test
	public void rayDotProductsSingleRay() {
		if (skipGeometryIssues) return;

		// Test that dot products work for a single ray in batch mode
		Producer<Ray> ray = v(shape(-1, 6), 0);

		PackedCollection<?> singleRay = new PackedCollection<>(shape(1, 6));
		singleRay.setMem(0, 0, 0, 3, 0, 0, -1); // origin (0,0,3), direction (0,0,-1)

		// Test oDoto (origin dot origin) = 0^2 + 0^2 + 3^2 = 9
		PackedCollection<?> oDotoResult = new PackedCollection<>(shape(1, 1).traverse(1));
		oDoto(ray).get().into(oDotoResult.each()).evaluate(singleRay);
		System.out.println("oDoto: " + oDotoResult.valueAt(0, 0) + " (expected 9.0)");
		Assert.assertEquals(9.0, oDotoResult.valueAt(0, 0), 0.01);

		// Test dDotd (direction dot direction) = 0^2 + 0^2 + (-1)^2 = 1
		PackedCollection<?> dDotdResult = new PackedCollection<>(shape(1, 1).traverse(1));
		dDotd(ray).get().into(dDotdResult.each()).evaluate(singleRay);
		System.out.println("dDotd: " + dDotdResult.valueAt(0, 0) + " (expected 1.0)");
		Assert.assertEquals(1.0, dDotdResult.valueAt(0, 0), 0.01);

		// Test oDotd (origin dot direction) = 0*0 + 0*0 + 3*(-1) = -3
		PackedCollection<?> oDotdResult = new PackedCollection<>(shape(1, 1).traverse(1));
		oDotd(ray).get().into(oDotdResult.each()).evaluate(singleRay);
		System.out.println("oDotd: " + oDotdResult.valueAt(0, 0) + " (expected -3.0)");
		Assert.assertEquals(-3.0, oDotdResult.valueAt(0, 0), 0.01);
	}

	@Test
	public void intersectionTests() {
		Sphere.enableTransform = false; // TODO  This should not be required

		Sphere s = new Sphere();
		ShadableIntersection f = s.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, 1.0));
		PackedCollection<?> distance = f.getDistance().get().evaluate();
		distance.print();
		assertEquals(-1, distance);

		f = s.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, -1.0));
		distance = f.getDistance().get().evaluate();
		distance.print();
		assertEquals(2, distance);

		f = s.intersectAt(ray(0.5, 0.5, 3.0, 0.0, 0.0, -1.0));
		distance = f.getDistance().get().evaluate();
		distance.print();
		assertEquals(2.2928932188134525, distance);

		Ray r = new Ray(f.get(0).get().evaluate(), 0);
		System.out.println(r);

		f = s.intersectAt(ray(0.0, 0.0, -2.0, 57.22891566265059, 72.32037025267255, 404.1157064026493));
		distance = f.getDistance().get().evaluate();
		System.out.println(distance);

		r = new Ray(f.get(0).get().evaluate(), 0);
		System.out.println(r);
	}

	@Test
	public void discriminantSingleRay() {
		if (skipGeometryIssues) return;

		// Test discriminant with a single ray that should hit
		Sphere s = new Sphere();
		s.setSize(0.5);

		// Ray from (0, 0, 3) pointing towards sphere at origin
		Producer<Ray> ray = v(shape(-1, 6), 0);
		Producer<Scalar> d = s.discriminant(ray);

		PackedCollection<?> singleRay = new PackedCollection<>(shape(1, 6));
		singleRay.setMem(0, 0, 0, 3, 0, 0, -1); // origin (0,0,3), direction (0,0,-1)

		PackedCollection<?> result = new PackedCollection<>(shape(1, 1).traverse(1));
		Evaluable<PackedCollection<?>> ev = greaterThan(c(d), c(0.0), c(1.0), c(-1.0)).get();
		ev.into(result.each()).evaluate(singleRay);

		System.out.println("Single ray discriminant test: " + result.valueAt(0, 0));
		System.out.println("Expected: 1.0 (hit), Got: " + result.valueAt(0, 0));
		Assert.assertEquals(1.0, result.valueAt(0, 0), 0.01);
	}

	@Test
	public void discriminantSmallBatch() {
		if (skipGeometryIssues) return;

		// Test discriminant with 3 rays to isolate batch issue
		Sphere s = new Sphere();
		s.setSize(0.5);

		Producer<Ray> ray = v(shape(-1, 6), 0);
		Producer<Scalar> d = s.discriminant(ray);

		// Create 3 rays: 2 that hit, 1 that misses
		PackedCollection<?> rays = new PackedCollection<>(shape(3, 6), 2);
		rays.setMem(0, 0, 0, 3, 0, 0, -1);      // Ray 0: hits (center)
		rays.setMem(6, 0.1, 0.1, 3, 0, 0, -1);  // Ray 1: hits (slightly off-center)
		rays.setMem(12, 5, 5, 3, 0, 0, -1);     // Ray 2: misses (far off to side)

		// Test each component of discriminant formula
		// Formula: oDotd^2 - dDotd * (oDoto - 1)

		PackedCollection<?> oDotdVals = new PackedCollection<>(shape(3, 1).traverse(1));
		oDotd(ray).get().into(oDotdVals.each()).evaluate(rays);
		System.out.println("oDotd values: " + oDotdVals.valueAt(0, 0) + ", " + oDotdVals.valueAt(1, 0) + ", " + oDotdVals.valueAt(2, 0));

		PackedCollection<?> dDotdVals = new PackedCollection<>(shape(3, 1).traverse(1));
		dDotd(ray).get().into(dDotdVals.each()).evaluate(rays);
		System.out.println("dDotd values: " + dDotdVals.valueAt(0, 0) + ", " + dDotdVals.valueAt(1, 0) + ", " + dDotdVals.valueAt(2, 0));

		PackedCollection<?> oDotoVals = new PackedCollection<>(shape(3, 1).traverse(1));
		oDoto(ray).get().into(oDotoVals.each()).evaluate(rays);
		System.out.println("oDoto values: " + oDotoVals.valueAt(0, 0) + ", " + oDotoVals.valueAt(1, 0) + ", " + oDotoVals.valueAt(2, 0));

		// First, check the raw discriminant values
		PackedCollection<?> discriminantValues = new PackedCollection<>(shape(3, 1).traverse(1));
		d.get().into(discriminantValues.each()).evaluate(rays);
		System.out.println("Raw discriminant values:");
		System.out.println("  Ray 0: " + discriminantValues.valueAt(0, 0) + " (should be > 0 for hit)");
		System.out.println("  Ray 1: " + discriminantValues.valueAt(1, 0) + " (should be > 0 for hit)");
		System.out.println("  Ray 2: " + discriminantValues.valueAt(2, 0) + " (should be < 0 for miss)");

		// Now test the conditional
		PackedCollection<?> result = new PackedCollection<>(shape(3, 1).traverse(1));
		Evaluable<PackedCollection<?>> ev = greaterThan(c(d), c(0.0), c(1.0), c(-1.0)).get();
		ev.into(result.each()).evaluate(rays);

		System.out.println("Small batch discriminant test:");
		System.out.println("  Ray 0 (hit): " + result.valueAt(0, 0) + " (expected 1.0)");
		System.out.println("  Ray 1 (hit): " + result.valueAt(1, 0) + " (expected 1.0)");
		System.out.println("  Ray 2 (miss): " + result.valueAt(2, 0) + " (expected -1.0)");

		Assert.assertEquals(1.0, result.valueAt(0, 0), 0.01);
		Assert.assertEquals(1.0, result.valueAt(1, 0), 0.01);
		Assert.assertEquals(-1.0, result.valueAt(2, 0), 0.01);
	}

	@Test
	public void discriminantKernel() {
		if (skipGeometryIssues) return;

		Producer<Ray> ray = v(shape(-1, 6), 0);

		int w = 100;
		int h = 100;

		Sphere s = new Sphere();
		s.setSize(0.5);

		PackedCollection<?> rays = new PackedCollection<>(shape(h, w, 6), 2);
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				rays.setMem(rays.getShape().index(y, x, 0), (x - (w / 2)) * 0.1, (y - (h / 2)) * 0.1, 3, 0, 0, -1);
			}
		}

		PackedCollection<?> destination = new PackedCollection<>(shape(h, w, 1), 2);

		Producer<Scalar> d = s.discriminant(ray); // oDotd(ray).pow(2.0).subtract(dDotd(ray).multiply(oDoto(ray).add(-1.0)));
		Evaluable<PackedCollection<?>> ev = greaterThan(c(d), c(0.0), c(1.0), c(-1.0)).get();
		ev.into(destination.each()).evaluate(rays);

		int hits = 0;

		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				hits += destination.valueAt(y, x, 0) > 0.0 ? 1 : 0;
			}
		}

		System.out.println(hits + " hits (expected 305)");
		Assert.assertEquals(305, hits);
	}

	@Test
	public void intersectionSingleRay() {
		if (skipGeometryIssues) return;

		// Test full intersection with a single ray that should hit
		Sphere s = new Sphere();

		ShadableIntersection f = s.intersectAt(v(shape(-1, 6), 0));

		// Ray from (0, 0, 3) pointing towards sphere at origin
		PackedCollection<?> singleRay = new PackedCollection<>(shape(1, 6));
		singleRay.setMem(0, 0, 3, 0, 0, -1); // origin (0,0,3), direction (0,0,-1)

		PackedCollection<?> destination = new PackedCollection<>(shape(1, 1).traverse(1));
		Evaluable<PackedCollection<?>> ev = f.getDistance().get();
		ev.into(destination.each()).evaluate(singleRay);

		double distance = destination.valueAt(0, 0);
		System.out.println("Single ray intersection test: " + distance);
		System.out.println("Expected: ~2.0 (3 - radius of 1), Got: " + distance);
		Assert.assertTrue("Distance should be positive", distance > 0.0);
		Assert.assertEquals(2.0, distance, 0.1);
	}

	@Test
	public void intersectionKernel() {
		if (skipGeometryIssues) return;

		int w = 100;
		int h = 100;

		Sphere s = new Sphere();

		ShadableIntersection f = s.intersectAt(v(shape(-1, 6), 0));

		PackedCollection<?> rays = new PackedCollection<>(shape(h, w, 6), 2);
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				rays.setMem(rays.getShape().index(y, x, 0), (x - (w / 2)) * 0.1, (y - (h / 2)) * 0.1, 3, 0, 0, -1);
			}
		}

		PackedCollection<?> destination = new PackedCollection<>(shape(h, w, 1), 2);

		Evaluable<PackedCollection<?>> ev = f.getDistance().get();
		ev.into(destination.each()).evaluate(rays);

		int hits = 0;

		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				hits += destination.valueAt(y, x, 0) > 0.0 ? 1 : 0;
			}
		}

		System.out.println(hits + " hits (expected 305)");
		Assert.assertEquals(305, hits);
	}

	// @Test
	public void cameraIntersectionKernel() {
		int w = 100;
		int h = 100;

		OrthographicCamera c = new OrthographicCamera();
		c.setLocation(new Vector(0.0, 0.0, 3.0));
		c.setViewDirection(new Vector(0.0, 0.0, -1.0));
		c.setProjectionDimensions(c.getProjectionWidth(), c.getProjectionWidth());

		Sphere s = new Sphere();
		ShadableIntersection f = s.intersectAt(v(shape(h, w, 6), 0));

		c.rayAt(v(shape(w * h, 2), 0), pair(w, h));

		PackedCollection<?> positions = new PackedCollection<>(shape(w * h, 2), 1);
		// TODO  Setup positions

		PackedCollection<?> destination = new PackedCollection<>(shape(h, w, 2), 2);

		Evaluable<PackedCollection<?>> ev = f.getDistance().get();
		ev.into(destination).evaluate(positions);

		int hits = 0;

		for (int i = 0; i < w * h; i++) {
			hits += destination.toDouble(destination.getShape().index(i, 0)) > 0.0 ? 1 : 0;
		}

		System.out.println(hits + " hits");
		// Assert.assertEquals(4900, hits);
	}
}
