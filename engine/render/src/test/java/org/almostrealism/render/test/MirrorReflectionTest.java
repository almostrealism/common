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
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.Shader;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.rayshade.ReflectionShader;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RayIntersectionEngine;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.render.RayTracedScene;
import org.almostrealism.space.Plane;
import org.almostrealism.space.Scene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Renders a perfect mirror sphere that reflects a red diffuse plane behind the camera, outside
 * the camera's direct view, and checks the reflection pixel by pixel against an analytic trace
 * of the same scene: every pixel of the mirror whose reflected ray hits the lit red plane must
 * be red, and every pixel of the mirror whose reflected ray misses it must not be. The mirror
 * reflects the plane in a disc around its centre (where the reflected rays head back toward
 * the camera) and reflects empty space around its rim.
 *
 * <p>Each pixel's camera ray is obtained from the same {@link PinholeCamera#rayAt} the renderer
 * uses, so the expectation shares the renderer's pixel-to-ray mapping and only the
 * intersection, reflection and closest-hit selection are checked independently.</p>
 */
public class MirrorReflectionTest extends TestSuiteBase implements RGBFeatures {
	/** The width and height of the image, in pixels. */
	private static final int SIZE = 24;

	/**
	 * The radius of the mirror sphere, which is centred at the origin. A unit sphere at the
	 * origin has an identity transform, which keeps the reflected ray's graph small enough to
	 * compile quickly.
	 */
	private static final double MIRROR_RADIUS = 1.0;

	/** The z coordinate of the red plane, which is parallel to the image and behind the camera. */
	private static final double RED_PLANE_Z = 15.0;

	/** The location of the point light, between the camera and the red plane. */
	private static final Vector LIGHT = new Vector(0.0, 0.0, 12.0);

	/** The location of the camera, which looks down the negative z axis. */
	private static final Vector CAMERA = new Vector(0.0, 0.0, 10.0);

	/** The value {@link #trace} gives a mirror pixel whose reflected ray misses the red plane. */
	private static final double REFLECTS_NOTHING = -1.0;

	/**
	 * The smallest diffuse factor at which a point of the red plane counts as well lit. Points
	 * below it are dark, so they are not expected to read as red.
	 */
	private static final double WELL_LIT = 0.2;

	/**
	 * Returns the distance along a ray to its nearest intersection with a sphere in front of
	 * its origin, or a negative value when it misses.
	 *
	 * @param origin    the origin of the ray
	 * @param direction the direction of the ray
	 * @param center    the centre of the sphere
	 * @param radius    the radius of the sphere
	 * @return the distance to the nearest intersection, or -1 for a miss
	 */
	private static double hitDistance(Vector origin, Vector direction, Vector center, double radius) {
		Vector o = origin.subtract(center);
		double a = direction.dotProduct(direction);
		double b = o.dotProduct(direction);
		double c = o.dotProduct(o) - radius * radius;
		double disc = b * b - a * c;
		if (disc < 0) return -1;

		double near = (-b - Math.sqrt(disc)) / a;
		double far = (-b + Math.sqrt(disc)) / a;
		if (near > 1e-6) return near;
		return far > 1e-6 ? far : -1;
	}

	/**
	 * Renders the mirror sphere and the red plane under a white point light of the given
	 * intensity.
	 *
	 * @param lightIntensity the intensity of the point light
	 * @return the rendered image, indexed by x and then y
	 */
	private RGB[][] render(double lightIntensity) {
		Scene<ShadableSurface> scene = new Scene<>();

		Sphere mirror = new Sphere(new Vector(0.0, 0.0, 0.0), MIRROR_RADIUS, new RGB(0.0, 0.0, 0.0));
		mirror.setShaders(new Shader[] { new ReflectionShader(1.0, white()) });
		mirror.calculateTransform();
		scene.add(mirror);

		Plane red = new Plane(Plane.XY, new RGB(1.0, 0.0, 0.0));
		red.setLocation(new Vector(0.0, 0.0, RED_PLANE_Z));
		red.setShadeFront(true);
		red.setShadeBack(true);
		red.setShaders(new Shader[] { DiffuseShader.defaultDiffuseShader });
		scene.add(red);

		scene.addLight(new PointLight(LIGHT, lightIntensity, new RGB(1.0, 1.0, 1.0)));

		PinholeCamera camera = camera();
		scene.setCamera(camera);

		RenderParameters params = new RenderParameters();
		params.width = SIZE;
		params.height = SIZE;
		params.dx = SIZE;
		params.dy = SIZE;
		params.ssWidth = 1;
		params.ssHeight = 1;

		return new RayTracedScene(new RayIntersectionEngine(scene, new FogParameters()), camera, params)
				.realize(params).get().evaluate();
	}

	/**
	 * Returns the camera that looks at the mirror from {@link #CAMERA} down the negative z axis.
	 *
	 * @return a new camera
	 */
	private static PinholeCamera camera() {
		PinholeCamera camera = new PinholeCamera();
		camera.setLocation(CAMERA);
		camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
		camera.setFocalLength(0.05);
		camera.setProjectionDimensions(0.02, 0.02);
		return camera;
	}

	/**
	 * Traces every pixel of the image analytically and returns, for each, what its camera ray
	 * should show: {@link Double#NaN} when the ray misses the mirror, {@link #REFLECTS_NOTHING}
	 * when the reflected ray misses the red plane, and otherwise the diffuse factor (the cosine
	 * between the plane's normal and the direction to the light) of the point of the red plane
	 * the reflected ray reaches.
	 *
	 * @return the analytic expectation, indexed by x and then y
	 */
	private double[][] trace() {
		Evaluable<PackedCollection> rays = camera().rayAt(v(shape(-1, 2), 0), pair(SIZE, SIZE)).get();
		double[][] expected = new double[SIZE][SIZE];

		for (int x = 0; x < SIZE; x++) {
			for (int y = 0; y < SIZE; y++) {
				PackedCollection ray = rays.evaluate(new Pair(x, y));
				Vector direction = new Vector(ray.toDouble(3), ray.toDouble(4), ray.toDouble(5));

				double t = hitDistance(CAMERA, direction, new Vector(0.0, 0.0, 0.0), MIRROR_RADIUS);
				if (t < 0) {
					expected[x][y] = Double.NaN;
					continue;
				}

				Vector point = CAMERA.add(direction.multiply(t));
				Vector normal = point.divide(point.length());
				Vector reflected = direction.subtract(normal.multiply(2.0 * direction.dotProduct(normal)));

				if (reflected.getZ() > 0) {
					Vector q = point.add(reflected.multiply((RED_PLANE_Z - point.getZ()) / reflected.getZ()));
					Vector toLight = LIGHT.subtract(q);
					expected[x][y] = -toLight.getZ() / toLight.length();
				} else {
					expected[x][y] = REFLECTS_NOTHING;
				}
			}
		}

		return expected;
	}

	/**
	 * A mirror reflects the red plane exactly where an analytic trace says it should.
	 */
	@Test(timeout = 300000)
	public void reflectionAppearsWhereGeometryPredicts() {
		RGB[][] image = render(1.0);
		double[][] expected = trace();

		int expectedRed = 0, matchedRed = 0, expectedOther = 0, matchedOther = 0;

		for (int x = 0; x < SIZE; x++) {
			for (int y = 0; y < SIZE; y++) {
				double diffuse = expected[x][y];
				if (Double.isNaN(diffuse)) continue;

				RGB c = image[x][y];
				boolean isRed = c.getRed() > 0.05 && c.getRed() > 4 * c.getGreen() && c.getRed() > 4 * c.getBlue();

				if (diffuse != REFLECTS_NOTHING) {
					if (diffuse < WELL_LIT) continue;

					expectedRed++;
					if (isRed) {
						matchedRed++;
					} else {
						log("Expected red at " + x + ", " + y + " but found " + c);
					}
				} else {
					expectedOther++;
					if (!isRed) matchedOther++;
				}
			}
		}

		log("Mirror pixels reflecting the red plane: " + matchedRed + " of " + expectedRed + " are red");
		log("Mirror pixels reflecting nothing: " + matchedOther + " of " + expectedOther + " are not red");

		assertTrue("The red plane should be reflected in some of the mirror", expectedRed > 4);
		assertTrue("The mirror should also reflect empty space", expectedOther > 4);
		assertTrue("Pixels predicted to reflect the red plane must be red",
				matchedRed >= 0.9 * expectedRed);
		assertTrue("Pixels predicted to reflect empty space must not be red",
				matchedOther >= 0.9 * expectedOther);
	}

	/**
	 * The reflection of a diffuse surface is lit by the light exactly once: with a perfect,
	 * untinted mirror (reflectance 1, white tint) under a light of intensity one half, every
	 * mirror pixel that reflects a well-lit point of the red plane has a red channel equal to
	 * that point's own diffuse shading, {@code 0.5 * cos(theta)}. Applying the light's colour a
	 * second time at the mirror would halve it.
	 */
	@Test(timeout = 300000)
	public void reflectionIsLitOnce() {
		double intensity = 0.5;
		RGB[][] image = render(intensity);
		double[][] expected = trace();

		int checked = 0, matched = 0;

		for (int x = 0; x < SIZE; x++) {
			for (int y = 0; y < SIZE; y++) {
				double diffuse = expected[x][y];
				if (Double.isNaN(diffuse) || diffuse == REFLECTS_NOTHING || diffuse < WELL_LIT) continue;

				double red = image[x][y].getRed();
				double target = intensity * diffuse;
				checked++;

				if (Math.abs(red - target) <= 0.1 * target) {
					matched++;
				} else {
					log("Expected red " + target + " at " + x + ", " + y + " but found " + image[x][y]);
				}
			}
		}

		log("Mirror pixels with the expected brightness: " + matched + " of " + checked);

		assertTrue("Some of the mirror should reflect a well-lit part of the red plane", checked > 4);
		assertTrue("Reflected pixels must be lit by the light exactly once", matched >= 0.9 * checked);
	}
}
