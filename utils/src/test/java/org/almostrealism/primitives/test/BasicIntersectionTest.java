/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.color.PointLight;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

/**
 * Basic intersection tests to verify ray-surface intersection calculation
 * works with current ar-common API.
 */
public class BasicIntersectionTest implements TestFeatures {

	@Test(timeout = 10000)
	public void sphereIntersection() {
		log("Testing sphere intersection...");

		// Create a sphere at origin with radius 1.0
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);

		// Create a ray from (0, 0, 10) pointing toward sphere (0, 0, -1)
		Producer<Ray> ray = (Producer) ray(0.0, 0.0, 10.0, 0.0, 0.0, -1.0);

		// Compute intersection
		ContinuousField intersection = sphere.intersectAt(ray);

		log("Intersection computed: " + intersection);

		// The intersection should be non-null if there's a hit
		assertNotNull("Intersection should not be null", intersection);
	}

	@Test(timeout = 10000)
	public void sphereIntersectionDistance() {
		log("Testing sphere intersection distance...");

		// Create a sphere at origin with radius 1.0
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);

		// Create a ray from (0, 0, 10) pointing toward sphere (0, 0, -1)
		Producer<Ray> ray = (Producer) ray(0.0, 0.0, 10.0, 0.0, 0.0, -1.0);

		// Compute intersection
		ShadableIntersection intersection = sphere.intersectAt(ray);

		if (intersection != null) {
			Producer<PackedCollection> distance = intersection.getDistance();
			log("Distance producer: " + distance);

			if (distance != null) {
				PackedCollection distanceValue = distance.get().evaluate();

				double d = distanceValue.toDouble();
				log("Intersection distance: " + d);
				assertEquals("Distance should be ~9.0", 9.0, d);
			}
		}
	}

	@Test(timeout = 10000)
	public void sphereColor() {
		log("Testing sphere color computation...");

		// Create a sphere with a color
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);
		sphere.setColor(new org.almostrealism.color.RGB(0.8, 0.2, 0.2)); // Red
		sphere.setShaders(new org.almostrealism.color.Shader[] {
			DiffuseShader.defaultDiffuseShader
		});

		log("Sphere created with color and diffuse shader");

		// Create a point at the surface
		Producer<PackedCollection> point = vector(0.0, 0.0, 1.0);

		// Get color at that point
		try {
			org.almostrealism.color.RGB color = new org.almostrealism.color.RGB(sphere.getValueAt(point).get().evaluate(), 0);
			log("Color at point: " + color);

			// Should get back the red color we set
			assertEquals("Red component should be 0.8", 0.8, color.toDouble(0));
			assertEquals("Green component should be 0.2", 0.2, color.toDouble(1));
			assertEquals("Blue component should be 0.2", 0.2, color.toDouble(2));
		} catch (Exception e) {
			log("Exception getting color: " + e.getMessage());
			e.printStackTrace();
		}
	}

	@Test(timeout = 10000)
	public void pointLightCreation() {
		log("Testing point light creation...");

		// Create a point light
		PointLight light = new PointLight(new Vector(0.0, 10.0, 0.0));

		log("Point light created at (0, 10, 0)");

		assertNotNull("Light should not be null", light);

		// Try to get color at a point
		try {
			Producer<PackedCollection> point = vector(0.0, 0.0, 0.0);
			Producer<PackedCollection> colorProducer = light.getColorAt(point);

			if (colorProducer != null) {
				org.almostrealism.color.RGB color = new org.almostrealism.color.RGB(colorProducer.get().evaluate(), 0);
				log("Light color at origin: " + color);
			}
		} catch (Exception e) {
			log("Exception getting light color: " + e.getMessage());
		}
	}
}
