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

package org.almostrealism.render.test;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.HeightField;
import org.almostrealism.geometry.ImplicitSurface;
import org.almostrealism.geometry.SignedDistanceFunction;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.render.ImplicitSurfaceRenderer;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.util.function.DoubleBinaryOperator;

/**
 * Renders implicit surfaces with {@link ImplicitSurfaceRenderer} and compares the hits with
 * the analytic intersections of the camera rays, which are evaluated one pixel at a time.
 */
public class ImplicitSurfaceRendererTest extends TestSuiteBase implements RGBFeatures {
	/** The amplitude of the sinusoidal height field. */
	private static final double AMPLITUDE = 0.2;

	/** The environment color at and below the horizon used by every renderer of this test. */
	private static final double[] HORIZON = { 0.8, 0.85, 0.9 };

	/** The environment color straight up used by every renderer of this test. */
	private static final double[] ZENITH = { 0.25, 0.4, 0.7 };

	/**
	 * The pixels of the height field images whose hit distances are compared, chosen so that
	 * every hit is inside the grid, where the height field follows its samples rather than
	 * the heights at its edges.
	 */
	private static final int[][] HEIGHT_FIELD_SAMPLES = {
			{ 16, 16 }, { 12, 20 }, { 20, 12 }, { 16, 24 }, { 10, 22 }, { 10, 6 }, { 14, 3 }, { 24, 26 }
	};

	/** Returns a camera at {@code (0, 0, 5)} looking at the origin with a unit projection plane. */
	private PinholeCamera sphereCamera() {
		return new PinholeCamera(new Vector(0.0, 0.0, 5.0), new Vector(0.0, 0.0, -1.0),
				new Vector(0.0, 1.0, 0.0), 1.0, 1.0, 1.0);
	}

	/** Returns a camera above and in front of the origin, looking down at it. */
	private PinholeCamera heightFieldCamera() {
		return new PinholeCamera(new Vector(0.0, 3.0, 6.0), new Vector(0.0, -0.45, -1.0),
				new Vector(0.0, 1.0, 0.0), 1.0, 1.0, 1.0);
	}

	/** The unit sphere at the origin, with its analytic normal. */
	private static class UnitSphere implements SignedDistanceFunction {
		/** Returns the distance from the position to the sphere, {@code |p| - 1}. */
		@Override
		public CollectionProducer value(Producer<PackedCollection> position) {
			return length(position).subtract(c(1.0));
		}

		/** Returns the analytic normal of the sphere, {@code p / |p|}. */
		@Override
		public CollectionProducer normal(Producer<PackedCollection> position) {
			return normalize(position);
		}
	}

	/** Returns the unit sphere at the origin, with its analytic normal. */
	private ImplicitSurface unitSphere() {
		return new UnitSphere();
	}

	/**
	 * Returns a renderer of the given surface with one white point light.
	 *
	 * @param surface the surface
	 * @param camera the camera
	 * @param size the width and height of the image
	 * @return the renderer
	 */
	private ImplicitSurfaceRenderer renderer(ImplicitSurface surface, PinholeCamera camera, int size) {
		ImplicitSurfaceRenderer renderer = new ImplicitSurfaceRenderer(surface, camera, size, size);
		renderer.addLight(new PointLight(new Vector(3.0, 4.0, 5.0), 1.0, new RGB(1.0, 1.0, 1.0)));
		renderer.setEnvironment(new RGB(HORIZON[0], HORIZON[1], HORIZON[2]),
				new RGB(ZENITH[0], ZENITH[1], ZENITH[2]));
		return renderer;
	}

	/**
	 * Asserts that a pixel of the image shows the environment in the direction of its ray: the
	 * horizon color blended towards the zenith color by the clamped upward component.
	 *
	 * @param image the {@code (n, 3)} image, in pixel order
	 * @param pixel the index of the pixel
	 * @param r the ray of the pixel, with a unit direction
	 */
	private void assertEnvironment(PackedCollection image, int pixel, double[] r) {
		double elevation = Math.max(0.0, Math.min(1.0, r[4]));
		for (int i = 0; i < 3; i++) {
			assertEquals("Channel " + i + " of pixel " + pixel + " should show the environment",
					HORIZON[i] + (ZENITH[i] - HORIZON[i]) * elevation,
					image.toDouble(3 * pixel + i), 1e-6);
		}
	}

	/**
	 * Returns the camera ray through one pixel, evaluated on its own, as
	 * {@code {ox, oy, oz, dx, dy, dz}}.
	 *
	 * @param rays the camera ray of a single screen position
	 * @param size the width and height of the image
	 * @param row the row of the pixel, counted from the top
	 * @param column the column of the pixel
	 * @return the ray
	 */
	private double[] ray(Evaluable<PackedCollection> rays, int size, int row, int column) {
		PackedCollection r = rays.evaluate(new Pair(column, size - 1 - row));
		return new double[] { r.toDouble(0), r.toDouble(1), r.toDouble(2),
				r.toDouble(3), r.toDouble(4), r.toDouble(5) };
	}

	/**
	 * Returns the distance along a ray to the unit sphere at the origin, or -1 for a miss.
	 *
	 * @param r the ray, with a unit direction
	 * @return the distance
	 */
	private static double sphereDistance(double[] r) {
		double b = r[0] * r[3] + r[1] * r[4] + r[2] * r[5];
		double c = r[0] * r[0] + r[1] * r[1] + r[2] * r[2] - 1.0;
		double disc = b * b - c;
		if (disc < 0) return -1.0;
		double t = -b - Math.sqrt(disc);
		return t > 0 ? t : -1.0;
	}

	/**
	 * Returns the distance along a ray to the surface {@code y = h(x, z)}, by scanning for
	 * the first sign change and bisecting it, or -1 for a miss.
	 *
	 * @param r the ray
	 * @param h the height function
	 * @return the distance
	 */
	private static double heightDistance(double[] r, DoubleBinaryOperator h) {
		double step = 0.005;
		double previous = 0.0;
		for (double t = step; t < 40.0; t += step) {
			if (r[1] + t * r[4] - h.applyAsDouble(r[0] + t * r[3], r[2] + t * r[5]) <= 0) {
				double lo = previous;
				double hi = t;
				for (int i = 0; i < 60; i++) {
					double m = 0.5 * (lo + hi);
					if (r[1] + m * r[4] - h.applyAsDouble(r[0] + m * r[3], r[2] + m * r[5]) > 0) {
						lo = m;
					} else {
						hi = m;
					}
				}

				return 0.5 * (lo + hi);
			}

			previous = t;
		}

		return -1.0;
	}

	/**
	 * Returns an {@code [n, n]} grid of heights covering {@code [-8, 8]} along {@code x} and
	 * {@code z}, computed on the device: {@code AMPLITUDE sin(x) cos(z)}, or a constant.
	 *
	 * @param n the number of nodes along each axis
	 * @param sinusoidal whether the heights are sinusoidal rather than constant
	 * @param level the constant height, or the mean height of the sinusoid
	 * @return the heights
	 */
	private PackedCollection heights(int n, boolean sinusoidal, double level) {
		double spacing = 16.0 / (n - 1);
		CollectionProducer index = integers(0, n * n);
		CollectionProducer x = floor(index.divide(c((double) n))).multiply(c(spacing)).add(c(-8.0));
		CollectionProducer z = mod(index, c((double) n)).multiply(c(spacing)).add(c(-8.0));
		CollectionProducer h = sinusoidal ?
				sin(x).multiply(cos(z)).multiply(c(AMPLITUDE)).add(c(level)) :
				x.multiply(c(0.0)).add(c(level));

		PackedCollection heights = new PackedCollection(shape(n, n));
		h.reshape(shape(n, n)).get().into(heights).evaluate();
		return heights;
	}

	/**
	 * The set of pixels where the ray-marched sphere is hit matches the analytic projection
	 * of the sphere to within one pixel, the hit distances match the analytic distances, the
	 * centre pixel is lit and the corners show the background.
	 */
	@Test(timeout = 600000)
	public void sphereSilhouette() {
		int size = 32;
		PinholeCamera camera = sphereCamera();
		ImplicitSurfaceRenderer renderer = renderer(unitSphere(), camera, size);
		try {
			assertSphereSilhouette(renderer, camera, size);
		} finally {
			renderer.destroy();
		}
	}

	/**
	 * Renders the unit sphere and checks its silhouette, hit distances, centre brightness and
	 * corners, as described by {@link #sphereSilhouette()}.
	 *
	 * @param renderer the renderer of the unit sphere
	 * @param camera the camera of the renderer
	 * @param size the width and height of the image
	 */
	private void assertSphereSilhouette(ImplicitSurfaceRenderer renderer, PinholeCamera camera, int size) {
		OperationList frame = renderer.frame();
		OperationList pixels = (OperationList) frame.get(1);
		assertTrue("The per-pixel operations should compile to one kernel",
				pixels.isUniform() && pixels.isComputation());
		frame.get().run();

		PackedCollection distance = renderer.getDistance();
		PackedCollection image = renderer.getImage();
		Evaluable<PackedCollection> rays = camera.rayAt(v(Pair.shape(), 0), pair(size, size)).get();

		boolean[][] expected = new boolean[size][size];
		double[][] expectedDistance = new double[size][size];
		for (int row = 0; row < size; row++) {
			for (int col = 0; col < size; col++) {
				expectedDistance[row][col] = sphereDistance(ray(rays, size, row, col));
				expected[row][col] = expectedDistance[row][col] > 0;
			}
		}

		int mismatches = 0;
		double maxError = 0.0;
		for (int row = 0; row < size; row++) {
			for (int col = 0; col < size; col++) {
				double t = distance.toDouble(row * size + col);
				boolean actual = t > 0;

				if (actual != expected[row][col]) {
					mismatches++;
					assertTrue("Pixel (" + row + ", " + col + ") is more than one pixel from the silhouette",
							onBoundary(expected, row, col));
				} else if (actual) {
					maxError = Math.max(maxError, Math.abs(t - expectedDistance[row][col]));
				}
			}
		}

		log("Silhouette mismatches: " + mismatches + ", largest distance error: " + maxError);
		assertTrue("The hit distances should match the analytic distances", maxError < 1e-3);

		int centre = (size / 2) * size + size / 2;
		double centreDistance = distance.toDouble(centre);
		log("Centre distance: " + centreDistance);
		assertEquals(expectedDistance[size / 2][size / 2], centreDistance, 1e-3);

		double centreBrightness = image.toDouble(3 * centre) + image.toDouble(3 * centre + 1) +
				image.toDouble(3 * centre + 2);
		log("Centre brightness: " + centreBrightness);
		assertTrue("The centre pixel should be lit", centreBrightness > 0.6);

		int[] corners = { 0, size - 1, (size - 1) * size, size * size - 1 };
		for (int corner : corners) {
			assertTrue("A corner should miss the sphere", distance.toDouble(corner) < 0);
			assertEquals(0.0, renderer.getReflectance().toDouble(corner));
			assertEnvironment(image, corner, ray(rays, size, corner / size, corner % size));
		}
	}

	/**
	 * Returns whether a pixel has a neighbour on the other side of the expected silhouette.
	 *
	 * @param expected the expected hits
	 * @param row the row of the pixel
	 * @param col the column of the pixel
	 * @return whether the pixel is on the boundary of the expected hits
	 */
	private static boolean onBoundary(boolean[][] expected, int row, int col) {
		for (int r = Math.max(0, row - 1); r <= Math.min(expected.length - 1, row + 1); r++) {
			for (int c = Math.max(0, col - 1); c <= Math.min(expected[r].length - 1, col + 1); c++) {
				if (expected[r][c] != expected[row][col]) return true;
			}
		}

		return false;
	}

	/**
	 * A camera inside the sphere sees no hit: every ray starts where the surface value is
	 * negative, so there is no bracket with a positive near end and every pixel misses. Every
	 * pixel shows the environment, even though the sphere normal at the camera position, the
	 * centre of the sphere, is undefined.
	 */
	@Test(timeout = 600000)
	public void cameraInsideSurfaceMisses() {
		int size = 8;
		PinholeCamera camera = new PinholeCamera(new Vector(0.0, 0.0, 0.0), new Vector(0.0, 0.0, -1.0),
				new Vector(0.0, 1.0, 0.0), 1.0, 1.0, 1.0);
		ImplicitSurfaceRenderer renderer = renderer(unitSphere(), camera, size);
		try {
			renderer.frame().get().run();

			PackedCollection distance = renderer.getDistance();
			PackedCollection reflectance = renderer.getReflectance();
			PackedCollection image = renderer.getImage();
			Evaluable<PackedCollection> rays = camera.rayAt(v(Pair.shape(), 0), pair(size, size)).get();
			for (int i = 0; i < size * size; i++) {
				assertEquals("Pixel " + i + " should miss", -1.0, distance.toDouble(i), 0.0);
				assertEquals(0.0, reflectance.toDouble(i), 0.0);
				assertEnvironment(image, i, ray(rays, size, i / size, i % size));
			}
		} finally {
			renderer.destroy();
		}
	}

	/** A height field rejects a grid spacing of zero. */
	@Test(timeout = 60000, expected = IllegalArgumentException.class)
	public void heightFieldRejectsZeroSpacing() {
		new HeightField(cp(new PackedCollection(shape(4, 4))), 0.0, 0.0, 0.0, 1.0);
	}

	/** A height field rejects a negative grid spacing. */
	@Test(timeout = 60000, expected = IllegalArgumentException.class)
	public void heightFieldRejectsNegativeSpacing() {
		new HeightField(cp(new PackedCollection(shape(4, 4))), 0.0, 0.0, 1.0, -1.0);
	}

	/** The hit distances on a flat height field match the analytic plane intersection. */
	@Test(timeout = 600000)
	public void flatHeightField() {
		double level = 0.25;
		assertHeightFieldDistances(heights(33, false, level), (x, z) -> level, 1e-3);
	}

	/** The hit distances on a sinusoidal height field match the analytic intersection. */
	@Test(timeout = 600000)
	public void sinusoidalHeightField() {
		assertHeightFieldDistances(heights(257, true, 0.0),
				(x, z) -> AMPLITUDE * Math.sin(x) * Math.cos(z), 1e-3);
	}

	/**
	 * Renders a height field over {@code [-8, 8]} and compares the hit distances at the
	 * sample pixels with the analytic intersections of the same rays.
	 *
	 * @param heights the {@code [n, n]} heights
	 * @param h the analytic height function
	 * @param tolerance the largest allowed difference between the distances
	 */
	private void assertHeightFieldDistances(PackedCollection heights, DoubleBinaryOperator h, double tolerance) {
		int size = 32;
		int n = heights.getShape().length(0);
		double spacing = 16.0 / (n - 1);

		PinholeCamera camera = heightFieldCamera();
		HeightField surface = new HeightField(cp(heights), -8.0, -8.0, spacing, spacing);
		ImplicitSurfaceRenderer renderer = renderer(surface, camera, size);
		renderer.setMarchSteps(96);
		try {
			renderer.frame().get().run();

			PackedCollection distance = renderer.getDistance();
			Evaluable<PackedCollection> rays = camera.rayAt(v(Pair.shape(), 0), pair(size, size)).get();

			for (int[] sample : HEIGHT_FIELD_SAMPLES) {
				double[] r = ray(rays, size, sample[0], sample[1]);
				double expected = heightDistance(r, h);
				double actual = distance.toDouble(sample[0] * size + sample[1]);
				log("Pixel (" + sample[0] + ", " + sample[1] + "): expected " + expected + ", actual " + actual);

				assertTrue("The ray should hit the height field", expected > 0);
				assertTrue("The hit should be inside the grid",
						Math.abs(r[0] + expected * r[3]) < 8.0 && Math.abs(r[2] + expected * r[5]) < 8.0);
				assertEquals(expected, actual, tolerance);
			}
		} finally {
			renderer.destroy();
			heights.destroy();
		}
	}

	/**
	 * The Fresnel reflectance at the edge of the sphere, where the rays graze the surface,
	 * exceeds the reflectance at its centre, where they meet it at normal incidence.
	 */
	@Test(timeout = 600000)
	public void fresnelGrazingExceedsNormal() {
		int size = 32;
		double f0 = 0.04;
		PinholeCamera camera = sphereCamera();
		ImplicitSurfaceRenderer renderer = renderer(unitSphere(), camera, size);
		renderer.setNormalReflectance(f0);
		try {
			assertFresnel(renderer, camera, size, f0);
		} finally {
			renderer.destroy();
		}
	}

	/**
	 * Renders the unit sphere and compares the reflectance at its centre and at its edge, as
	 * described by {@link #fresnelGrazingExceedsNormal()}.
	 *
	 * @param renderer the renderer of the unit sphere
	 * @param camera the camera of the renderer
	 * @param size the width and height of the image
	 * @param f0 the reflectance at normal incidence
	 */
	private void assertFresnel(ImplicitSurfaceRenderer renderer, PinholeCamera camera, int size, double f0) {
		renderer.frame().get().run();

		PackedCollection distance = renderer.getDistance();
		PackedCollection reflectance = renderer.getReflectance();
		int row = size / 2;

		int edge = -1;
		for (int col = 0; col < size && edge < 0; col++) {
			if (distance.toDouble(row * size + col) > 0) edge = col;
		}

		assertTrue("The middle row should hit the sphere", edge >= 0);

		double normal = reflectance.toDouble(row * size + size / 2);
		double grazing = reflectance.toDouble(row * size + edge);
		log("Reflectance at normal incidence: " + normal + ", at the edge: " + grazing);

		// The Schlick reflectance of the edge pixel, from the analytic hit on the unit
		// sphere, where the normal is the hit position itself
		double[] r = ray(camera.rayAt(v(Pair.shape(), 0), pair(size, size)).get(), size, row, edge);
		double t = sphereDistance(r);
		double cosine = 0.0;
		for (int i = 0; i < 3; i++) {
			cosine -= r[i + 3] * (r[i] + t * r[i + 3]);
		}

		double expected = f0 + (1 - f0) * Math.pow(1 - cosine, 5);
		log("Expected reflectance at the edge: " + expected);

		assertEquals(f0, normal, 1e-3);
		assertEquals(expected, grazing, 1e-3);
		assertTrue("Grazing reflectance should exceed normal reflectance", grazing > normal + 0.01);
	}

	/**
	 * Records the time to render a frame of a height field at 128 by 128 pixels, after the
	 * frame is compiled once, and writes the image.
	 */
	@Test(timeout = 900000)
	public void frameTime() {
		int size = 128;
		PackedCollection heights = heights(129, true, 0.0);
		HeightField surface = new HeightField(cp(heights), -8.0, -8.0, 0.125, 0.125);
		ImplicitSurfaceRenderer renderer = renderer(surface, heightFieldCamera(), size);
		renderer.setBaseColor(new RGB(0.05, 0.05, 0.06));
		renderer.setSpecular(0.8, 128.0);
		try {
			assertFrameTime(renderer, size);
		} finally {
			renderer.destroy();
			heights.destroy();
		}
	}

	/**
	 * Compiles and times the frames of the height field renderer and checks that the image
	 * shows both the surface and the environment, as described by {@link #frameTime()}.
	 *
	 * @param renderer the renderer of the height field
	 * @param size the width and height of the image
	 */
	private void assertFrameTime(ImplicitSurfaceRenderer renderer, int size) {
		long start = System.nanoTime();
		Runnable frame = renderer.frame().get();
		long compiled = System.nanoTime();
		frame.run();

		int frames = 5;
		long first = System.nanoTime();
		for (int i = 0; i < frames; i++) {
			frame.run();
		}
		long end = System.nanoTime();

		log("Compile: " + (compiled - start) / 1e6 + " ms, first frame: " + (first - compiled) / 1e6 +
				" ms, per frame at " + size + "x" + size + ": " + (end - first) / 1e6 / frames + " ms");

		new File("results").mkdirs();
		saveRgb("results/implicit-surface-height-field.png", cp(renderer.getImage())).get().run();

		PackedCollection distance = renderer.getDistance();
		int hits = 0;
		for (int i = 0; i < size * size; i++) {
			if (distance.toDouble(i) > 0) hits++;
		}

		log("Pixels that hit the surface: " + hits);
		assertTrue("Some pixels should hit the surface", hits > size * size / 4);
		assertTrue("Some pixels should show the environment", hits < size * size);
	}
}
