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

package org.almostrealism.render.test;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.projection.ProjectionFeatures;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Intersects camera rays, one per pixel, with a unit sphere at the origin, viewed from
 * {@code (0, 0, 5)} through a projection plane of size 1 at focal length 1. The sphere covers
 * the middle of the image and misses its corners.
 */
public class CameraRayIntersectionTest extends TestSuiteBase implements ProjectionFeatures {
	/** The image width, in pixels. */
	private static final int WIDTH = 16;

	/** The image height, in pixels. */
	private static final int HEIGHT = 16;

	/**
	 * Returns the distance along the camera ray through each pixel position given as the first
	 * argument to the sphere; negative where the ray misses.
	 *
	 * @param positions the shape of the argument: one pixel position, or a batch of them
	 * @return the intersection distance producer
	 */
	private Producer<PackedCollection> distance(TraversalPolicy positions) {
		Vector viewDirection = new Vector(0.0, 0.0, -1.0);
		Vector upDirection = new Vector(0.0, 1.0, 0.0);

		Vector w = viewDirection.divide(viewDirection.length()).minus();

		Vector u = upDirection.crossProduct(w);
		u.divideBy(u.length());

		Vector v = w.crossProduct(u);

		return new Sphere().intersectAt(rayAt(v(positions, 0),
				pair(WIDTH, HEIGHT),
				new Vector(0.0, 0.0, 5.0),
				new Pair(1.0, 1.0),
				0.0, 1.0,
				u, v, w)).getDistance();
	}

	/** The ray through the centre pixel hits the sphere, and the ray through a corner misses it. */
	@Test(timeout = 60000)
	public void centreHitsAndCornerMisses() {
		Evaluable<PackedCollection> ev = distance(Pair.shape()).get();

		double centre = ev.evaluate(new Pair(WIDTH / 2.0, HEIGHT / 2.0)).toDouble(0);
		log("Centre distance: " + centre);
		assertTrue("The centre ray should hit the sphere", centre > 0);

		double corner = ev.evaluate(new Pair(0, 0)).toDouble(0);
		log("Corner distance: " + corner);
		assertTrue("The corner ray should miss the sphere", corner < 0);
	}

	/**
	 * Evaluating every pixel in one kernel gives the same distances as evaluating each pixel
	 * on its own.
	 *
	 * <p>Known issue: compiling the batched camera ray fails with "Could not obtain operator",
	 * caused by an {@link IllegalArgumentException} from {@link TraversalPolicy#subset} when the
	 * ray direction takes a one-dimensional subset of the two-dimensional batch of pixel
	 * positions.</p>
	 */
	@TestProperties(knownIssue = true)
	@Test(timeout = 120000)
	public void kernelMatchesPerPixel() {
		PackedCollection pixels = new PackedCollection(shape(WIDTH * HEIGHT, 2));

		for (int i = 0; i < WIDTH; i++) {
			for (int j = 0; j < HEIGHT; j++) {
				int index = j * WIDTH + i;
				pixels.setMem(2 * index, i);
				pixels.setMem(2 * index + 1, j);
			}
		}

		PackedCollection output = new PackedCollection(WIDTH * HEIGHT).traverse(1);
		distance(shape(WIDTH * HEIGHT, 2)).get().into(output).evaluate(pixels);

		Evaluable<PackedCollection> single = distance(Pair.shape()).get();

		for (int i = 0; i < output.getCount(); i++) {
			Pair pixel = new Pair(pixels.toDouble(2 * i), pixels.toDouble(2 * i + 1));
			double expected = single.evaluate(pixel).toDouble(0);
			assertEquals(expected, output.toDouble(i));
		}
	}
}
