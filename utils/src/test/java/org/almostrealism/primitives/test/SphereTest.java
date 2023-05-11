/*
 * Copyright 2018 Michael Murray
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
	public void intersectionTests() {
		Sphere s = new Sphere();
		ShadableIntersection f = s.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, 1.0));
		Scalar distance = f.getDistance().get().evaluate();
		System.out.println(distance);
		assertEquals(-1, distance);

		f = s.intersectAt(ray(0.0, 0.0, 3.0, 0.0, 0.0, -1.0));
		distance = f.getDistance().get().evaluate();
		System.out.println(distance);
		assertEquals(2, distance);

		Ray r = f.get(0).get().evaluate();
		System.out.println(r);

		f = s.intersectAt(ray(0.0, 0.0, -2.0, 57.22891566265059, 72.32037025267255, 404.1157064026493));
		distance = f.getDistance().get().evaluate();
		System.out.println(distance);

		r = f.get(0).get().evaluate();
		System.out.println(r);
	}

	@Test
	public void intersectionKernel() {
		int w = 100;
		int h = 100;

		Sphere s = new Sphere();
		s.setSize(0.5);

		ShadableIntersection f = s.intersectAt(v(shape(6), 0));

		PackedCollection<?> rays = new PackedCollection<>(shape(h, w, 6), 2);
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				rays.setMem(rays.getShape().index(y, x, 0), x - (w / 2), y - (h / 2), 3, 0, 0, -1);
			}
		}

		PackedCollection<?> destination = new PackedCollection<>(shape(h, w, 2), 2);

		Evaluable<Scalar> ev = s.discriminant(v(shape(6), 0)).get(); // f.getDistance().get();
		ev.into(destination).evaluate(rays);

		int hits = 0;

		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				hits += destination.valueAt(y, x, 0) > 0.0 ? 1 : 0;
			}
		}

		System.out.println(hits + " hits");
		// Assert.assertEquals(4900, hits);
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

		c.rayAt(pair(v(shape(w * h, 2), 0)), pair(w, h));

		PackedCollection<?> positions = new PackedCollection<>(shape(w * h, 2), 1);
		// TODO  Setup positions

		PackedCollection<?> destination = new PackedCollection<>(shape(h, w, 2), 2);

		Evaluable<Scalar> ev = f.getDistance().get();
		ev.into(destination).evaluate(positions);

		int hits = 0;

		for (int i = 0; i < w * h; i++) {
			hits += destination.toDouble(destination.getShape().index(i, 0)) > 0.0 ? 1 : 0;
		}

		System.out.println(hits + " hits");
		// Assert.assertEquals(4900, hits);
	}
}
