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

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.Shader;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.rayshade.ReflectionShader;
import org.almostrealism.rayshade.RefractionShader;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RayIntersectionEngine;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.render.RayTracedScene;
import org.almostrealism.space.Plane;
import org.almostrealism.space.Scene;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Renders a sphere carrying only a {@link ReflectionShader} or only a {@link RefractionShader},
 * above a lit diffuse floor and against a black background. Either shader should show the floor
 * somewhere in the sphere's disc, so the disc must not be entirely black.
 *
 * <p>Known issues, both raised while building the scene's producer in
 * {@code RayTracedScene.realize}, before any pixel is evaluated:</p>
 * <ul>
 *   <li>Reflection: the reflected ray's {@code LightingEngineAggregator} intersects the ray with
 *       the floor, and {@code RayFeatures.origin} fails with "Subset cannot be performed without a
 *       TraversalPolicy" because the reflected ray producer has no shape.</li>
 *   <li>Refraction: {@code LightingEngine} multiplies the shaded colour, and
 *       {@code GeneratedColorProducer.getShape} throws a {@link ClassCastException} casting the
 *       lambda that {@link RefractionShader} returns to {@code io.almostrealism.collect.Shape}.</li>
 * </ul>
 */
public class ReflectionRefractionRenderTest extends TestSuiteBase implements RGBFeatures {
	/** The width and height of the image, in pixels. */
	private static final int SIZE = 32;

	/** The radius of the sphere's disc in the image, in pixels; the view is 8 units across. */
	private static final double DISC_RADIUS = SIZE / 8.0;

	/**
	 * Renders the scene with the given shader on the sphere.
	 *
	 * @param shader the sphere's only shader
	 * @return the image, indexed by column and then row
	 */
	private RGB[][] render(Shader shader) {
		Scene<ShadableSurface> scene = new Scene<>();

		Sphere sphere = new Sphere(new Vector(0.0, 0.0, 0.0), 1.0, new RGB(0.3, 0.3, 0.3));
		sphere.setShaders(new Shader[] { shader });
		sphere.calculateTransform();
		scene.add(sphere);

		Plane floor = new Plane(Plane.XZ, new RGB(0.9, 0.9, 0.9));
		floor.setLocation(new Vector(0.0, -1.5, 0.0));
		floor.setShaders(new Shader[] { DiffuseShader.defaultDiffuseShader });
		scene.add(floor);

		scene.addLight(new PointLight(new Vector(0.0, 10.0, 5.0), 1.0, new RGB(1.0, 1.0, 1.0)));

		PinholeCamera camera = new PinholeCamera();
		camera.setLocation(new Vector(0.0, 0.0, 10.0));
		camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
		camera.setFocalLength(0.05);
		camera.setProjectionDimensions(0.04, 0.04);
		scene.setCamera(camera);

		RenderParameters params = new RenderParameters();
		params.width = SIZE;
		params.height = SIZE;
		params.dx = SIZE;
		params.dy = SIZE;
		params.ssWidth = 1;
		params.ssHeight = 1;

		RayTracedScene r = new RayTracedScene(new RayIntersectionEngine(scene, new FogParameters()), camera, params);
		return r.realize(params).get().evaluate();
	}

	/**
	 * Asserts that the image is the expected size, has no NaN components, and is not entirely black
	 * within the sphere's disc.
	 *
	 * @param image the rendered image
	 */
	private void assertDiscShowsScene(RGB[][] image) {
		assertEquals(SIZE, image.length);

		int lit = 0;
		double centre = (SIZE - 1) / 2.0;

		for (int x = 0; x < image.length; x++) {
			assertEquals(SIZE, image[x].length);

			for (int y = 0; y < image[x].length; y++) {
				RGB c = image[x][y];
				assertNotNull(c);
				assertFalse("NaN at " + x + ", " + y,
						Double.isNaN(c.getRed()) || Double.isNaN(c.getGreen()) || Double.isNaN(c.getBlue()));

				boolean inDisc = Math.hypot(x - centre, y - centre) < DISC_RADIUS;
				if (inDisc && c.getRed() + c.getGreen() + c.getBlue() > 0.03) lit++;
			}
		}

		log("Lit pixels in the sphere's disc: " + lit);
		assertTrue("The sphere's disc is entirely black", lit > 0);
	}

	/** A mirror sphere reflects the lit floor. */
	@TestProperties(knownIssue = true)
	@Test(timeout = 120000)
	public void reflectiveSphereShowsFloor() {
		assertDiscShowsScene(render(new ReflectionShader(0.8, rgb(0.8))));
	}

	/** A refractive sphere shows the lit floor through it. */
	@TestProperties(knownIssue = true)
	@Test(timeout = 120000)
	public void refractiveSphereShowsFloor() {
		assertDiscShowsScene(render(new RefractionShader()));
	}
}
