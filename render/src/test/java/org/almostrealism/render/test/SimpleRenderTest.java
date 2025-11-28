package org.almostrealism.render.test;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;

import org.almostrealism.color.PointLight;
import org.almostrealism.color.Shader;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RayIntersectionEngine;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.render.RayTracedScene;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RealizableImage;
import org.almostrealism.geometry.Ray;
import org.almostrealism.primitives.Sphere;
import org.almostrealism.raytrace.IntersectionalLightingEngine;
import org.almostrealism.raytrace.LightingEngineAggregator;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.Scene;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.texture.ImageCanvas;
import org.almostrealism.util.TestFeatures;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Simple rendering tests to verify the ray tracing pipeline can produce output.
 */
public class SimpleRenderTest implements TestFeatures {
	int width = 640;
	int height = 640;

	@Test
	public void testSinglePixelRendering() {
		log("Testing single pixel rendering with transforms enabled...");

		// Create sphere at origin
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);
		sphere.setColor(new RGB(0.8, 0.2, 0.2)); // Red
		((AbstractSurface) sphere).setShaders(new org.almostrealism.color.Shader[] {
			DiffuseShader.defaultDiffuseShader
		});
		sphere.calculateTransform();
		log("Sphere created at origin, size 1.0, transforms enabled");

		// DEBUG: Print the actual transform matrix
		org.almostrealism.geometry.TransformMatrix transform = sphere.getTransform(true);
		if (transform != null) {
			double[] tmData = transform.toArray(0, 16);
			log("Sphere transform matrix:");
			for (int i = 0; i < 4; i++) {
				log("  [" + tmData[i*4] + ", " + tmData[i*4+1] + ", " + tmData[i*4+2] + ", " + tmData[i*4+3] + "]");
			}
		} else {
			log("Sphere transform is NULL");
		}

		// Create light
		PointLight light = new PointLight(new Vector(5.0, 5.0, 10.0));
		light.setColor(new RGB(1.0, 1.0, 1.0));
		light.setIntensity(1.0);
		log("Light created at (5, 5, 10)");

		// Create scene
		Scene<ShadableSurface> scene = new Scene<>();
		scene.add(sphere);
		scene.addLight(light);

		// Create camera pointing at sphere
		PinholeCamera camera = new PinholeCamera();
		camera.setLocation(new Vector(0.0, 0.0, 10.0));
		camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
		camera.setFocalLength(0.05);
		camera.setProjectionDimensions(0.04, 0.04);
		scene.setCamera(camera);

		// Create render parameters for just center pixel
		RenderParameters params = new RenderParameters();
		params.width = 1;  // Single pixel!
		params.height = 1;
		params.dx = 1;
		params.dy = 1;
		params.ssWidth = 1;
		params.ssHeight = 1;

		log("Render parameters: 1x1 (single pixel)");

		// Create ray traced scene
		RayTracedScene rayTracedScene = new RayTracedScene(
			new RayIntersectionEngine(scene, new FogParameters()),
			camera,
			params
		);

		log("Starting single pixel render...");

		// Enable verbose logging
		LightingEngineAggregator.enableVerbose = true;

		// Render
		RealizableImage realizableImage = rayTracedScene.realize(params);
		RGB[][] imageData = realizableImage.get().evaluate();

		// Check result
		RGB pixel = imageData[0][0];
		log("Pixel RGB: (" + pixel.getRed() + ", " + pixel.getGreen() + ", " + pixel.getBlue() + ")");

		boolean isNonBlack = pixel.getRed() > 0.01 || pixel.getGreen() > 0.01 || pixel.getBlue() > 0.01;
		log("Is non-black: " + isNonBlack);

		assertTrue("Single pixel should be non-black", isNonBlack);
	}

	@Test
	public void testRankCacheComparison() {
		log("Comparing working intersection test vs broken rank cache...");

		// Create sphere at origin
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);
		sphere.setColor(new RGB(1.0, 1.0, 1.0));
		((AbstractSurface) sphere).setShaders(new org.almostrealism.color.Shader[] {
			DiffuseShader.defaultDiffuseShader
		});
		sphere.calculateTransform();
		log("Created sphere at origin, radius 1.0");

		// Create light
		PointLight light = new PointLight(new Vector(5.0, 5.0, 10.0));
		light.setColor(new RGB(1.0, 1.0, 1.0));
		light.setIntensity(1.0);

		// Create camera
		PinholeCamera camera = new PinholeCamera(
			new Vector(0.0, 0.0, 10.0),  // location
			new Vector(0.0, 0.0, -1.0),  // viewing direction
			new Vector(0.0, 1.0, 0.0)    // up direction
		);
		camera.setFocalLength(0.1);
		camera.setProjectionDimensions(0.36, 0.24);

		// Disable hardware acceleration to test non-accelerated path
		boolean originalHwAccel = PinholeCamera.enableHardwareAcceleration;
		PinholeCamera.enableHardwareAcceleration = false;
		log("Disabled hardware acceleration for camera rays");

		// Create scene
		Scene<ShadableSurface> scene = new Scene<>();
		scene.add(sphere);
		scene.addLight(light);
		scene.setCamera(camera);

		// TEST 1: Working approach - static ray
		log("\n=== TEST 1: Static ray (working) ===");
		CollectionProducer staticRay = ray(0.0, 0.0, 10.0, 0.0, 0.0, -1.0);
		org.almostrealism.geometry.ShadableIntersection staticIntersection = sphere.intersectAt(staticRay);
		org.almostrealism.collect.PackedCollection staticDistance = staticIntersection.getDistance().get().evaluate();
		double staticDistValue = staticDistance.toDouble(0);
		log("Static ray distance: " + staticDistValue);
		log("Expected: ~9.0");

		// TEST 2: Dynamic ray from camera - evaluate with pixel position
		log("\n=== TEST 2: Dynamic ray from camera (should work) ===");
		CollectionProducer pixelPos = pair(0.0, 0.0);
		CollectionProducer screenDim = pair(1.0, 1.0);
		CollectionProducer dynamicRay = camera.rayAt(pixelPos, screenDim);
		org.almostrealism.geometry.ShadableIntersection dynamicIntersection = sphere.intersectAt(dynamicRay);
		org.almostrealism.collect.PackedCollection dynamicDistance = dynamicIntersection.getDistance().get().evaluate();
		double dynamicDistValue = dynamicDistance.toDouble(0);
		log("Dynamic ray distance: " + dynamicDistValue);
		log("Expected: ~9.0");

		// TEST 3: Dynamic ray with variable input - like rank cache does
		log("\n=== TEST 3: Dynamic ray with v(shape(-1, 2), 0) (rank cache approach) ===");
		Producer<?> variablePixelPos = v(shape(-1, 2), 0);
		CollectionProducer constantScreenDim = pair(1.0, 1.0);
		CollectionProducer variableRay = camera.rayAt(variablePixelPos, constantScreenDim);
		org.almostrealism.geometry.ShadableIntersection variableIntersection = sphere.intersectAt(variableRay);

		// Create input like initRankCache does
		org.almostrealism.collect.PackedCollection input =
			org.almostrealism.algebra.Pair.bank(1);
		input.get(0).setMem(new double[] { 0.0, 0.0 });  // Single pixel at (0, 0)

		// Evaluate with batch input like rank cache does
		org.almostrealism.collect.PackedCollection rankCollection =
			new org.almostrealism.collect.PackedCollection(shape(1, 1).traverse(1));
		variableIntersection.getDistance().get().into(rankCollection.each()).evaluate(input);

		double variableDistValue = rankCollection.valueAt(0, 0);
		log("Variable ray distance: " + variableDistValue);
		log("Expected: ~9.0");
		log("Actual: " + (variableDistValue < 0 ? "MISS (-1.0)" : variableDistValue));

		// Verify results
		assertTrue("Static ray should hit sphere", staticDistValue > 0);
		assertTrue("Static ray distance should be ~9.0 (was " + staticDistValue + ")",
			Math.abs(staticDistValue - 9.0) < 0.1);

		assertTrue("Dynamic ray should hit sphere", dynamicDistValue > 0);
		assertTrue("Dynamic ray distance should be ~9.0 (was " + dynamicDistValue + ")",
			Math.abs(dynamicDistValue - 9.0) < 0.1);

		assertTrue("Variable ray (rank cache approach) should hit sphere (distance was " + variableDistValue + ")",
			variableDistValue > 0);
		assertTrue("Variable ray distance should be ~9.0 (was " + variableDistValue + ")",
			Math.abs(variableDistValue - 9.0) < 0.1);

		// Restore hardware acceleration setting
		PinholeCamera.enableHardwareAcceleration = originalHwAccel;
	}

	@Test
	public void testCameraRayDirection() {
		log("Testing camera ray direction computation...");

		// Create camera at (0, 0, 10) looking down -Z
		PinholeCamera camera = new PinholeCamera(
			new Vector(0.0, 0.0, 10.0),
			new Vector(0.0, 0.0, -1.0),
			new Vector(0.0, 1.0, 0.0)
		);
		camera.setFocalLength(0.1);
		camera.setProjectionDimensions(0.36, 0.24);

		// Test with hardware acceleration
		log("\n=== With hardware acceleration ===");
		CollectionProducer pixelPos = pair(0.0, 0.0);
		CollectionProducer screenDim = pair(1.0, 1.0);
		CollectionProducer rayProducer = camera.rayAt(pixelPos, screenDim);

		// Evaluate the ray
		org.almostrealism.collect.PackedCollection rayData = rayProducer.get().evaluate();
		log("Ray data count: " + rayData.getCount());
		log("Ray data memory size: " + rayData.getMemLength());

		// Extract origin and direction
		// Ray format: [origin_x, origin_y, origin_z, direction_x, direction_y, direction_z]
		double ox = rayData.toDouble(0);
		double oy = rayData.toDouble(1);
		double oz = rayData.toDouble(2);
		double dx = rayData.toDouble(3);
		double dy = rayData.toDouble(4);
		double dz = rayData.toDouble(5);

		log(String.format("Ray origin: (%.6f, %.6f, %.6f)", ox, oy, oz));
		log(String.format("Ray direction: (%.6f, %.6f, %.6f)", dx, dy, dz));

		// Expected:
		// Origin: (0, 0, 10) - camera location
		// Direction: Should point towards (0, 0, -1) approximately (normalized)
		log("\nExpected:");
		log("Origin: (0.000000, 0.000000, 10.000000)");
		log("Direction: (0.000000, 0.000000, <negative value close to -1>)");

		// Check origin
		assertTrue("Ray origin X should be ~0 (was " + ox + ")", Math.abs(ox - 0.0) < 0.01);
		assertTrue("Ray origin Y should be ~0 (was " + oy + ")", Math.abs(oy - 0.0) < 0.01);
		assertTrue("Ray origin Z should be ~10 (was " + oz + ")", Math.abs(oz - 10.0) < 0.01);

		// Check direction (should point towards -Z, may include X/Y components due to projection)
		assertTrue("Ray direction Z should be negative (was " + dz + ")", dz < 0.0);

		// Verify direction is normalized (length ~= 1.0)
		double len = Math.sqrt(dx*dx + dy*dy + dz*dz);
		log("Ray direction length: " + len);
		assertTrue("Ray direction should be normalized (length was " + len + ")", Math.abs(len - 1.0) < 0.01);

		log("\nTest passed!");
	}

	@Test
	public void testShaderIsolated() {
		log("Testing shader/lighting calculation in isolation...");

		// Create sphere at origin with white diffuse color
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);
		sphere.setColor(new RGB(1.0, 1.0, 1.0)); // White surface
		((AbstractSurface) sphere).setShaders(new org.almostrealism.color.Shader[] {
			DiffuseShader.defaultDiffuseShader
		});
		log("Created white sphere at origin, radius 1.0");

		// Create light source at (5, 5, 5) - above and to the side
		PointLight light = new PointLight(new Vector(5.0, 5.0, 5.0));
		light.setColor(new RGB(1.0, 1.0, 1.0));
		light.setIntensity(1.0);
		log("Created white light at (5, 5, 5)");

		// Create a camera ray from (0, 0, 10) pointing towards sphere at (0, 0, -1)
		// This should hit the sphere at approximately (0, 0, 1) on the front surface
		CollectionProducer testRay = ray(0.0, 0.0, 10.0, 0.0, 0.0, -1.0);
		log("Created ray: origin (0, 0, 10), direction (0, 0, -1)");

		// Create shader context
		org.almostrealism.color.ShaderContext context = new org.almostrealism.color.ShaderContext(sphere, light);
		log("Created shader context");

		// Create lighting engine directly
		IntersectionalLightingEngine engine =
			new IntersectionalLightingEngine(
				testRay, sphere, java.util.Collections.emptyList(), light, java.util.Collections.emptyList(), context);
		log("Created IntersectionalLightingEngine");

		// Get the color producer
		Producer<PackedCollection> colorProducer = engine.getProducer();
		log("Got color producer: " + colorProducer);

		// Evaluate with a pixel position argument (doesn't matter which, just need to match expected args)
		org.almostrealism.algebra.Pair pixelPos = new org.almostrealism.algebra.Pair(32.0, 32.0);
		Object result = colorProducer.get().evaluate(pixelPos);

		// Handle PackedCollection -> RGB conversion
		RGB color;
		if (result instanceof RGB) {
			color = (RGB) result;
		} else if (result instanceof org.almostrealism.collect.PackedCollection) {
			color = new RGB((org.almostrealism.collect.PackedCollection) result, 0);
		} else {
			throw new IllegalStateException("Unexpected result type: " + result.getClass().getName());
		}

		log("Color evaluated: RGB(" + color.getRed() + ", " + color.getGreen() + ", " + color.getBlue() + ")");

		// Check that color is non-black
		boolean isNonBlack = color.getRed() > 0.01 || color.getGreen() > 0.01 || color.getBlue() > 0.01;
		log("Is non-black: " + isNonBlack);

		assertTrue("Shader should produce non-black color for lit sphere", isNonBlack);
	}

	@Test
	public void debugIntersection() {
		log("Testing direct sphere intersection...");

		// Create sphere at origin
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);

		// Create a ray from camera position (0, 0, 10) pointing towards sphere (0, 0, -1)
		CollectionProducer testRay = ray(0.0, 0.0, 10.0, 0.0, 0.0, -1.0);

		log("Ray created: origin (0, 0, 10), direction (0, 0, -1)");
		log("Sphere at origin (0, 0, 0), size 1.0");

		// Get intersection
		org.almostrealism.geometry.ShadableIntersection intersection = sphere.intersectAt(testRay);

		// Evaluate the distance
		org.almostrealism.collect.PackedCollection distance = intersection.getDistance().get().evaluate();

		log("Distance value: " + distance.toDouble(0));
		log("Expected: around 9.0 (10 - radius of 1)");

		double distVal = distance.toDouble(0);
		assertTrue("Distance should be positive (ray hits sphere)", distVal > 0);
		assertTrue("Distance should be ~9.0 (was " + distVal + ")", Math.abs(distVal - 9.0) < 0.1);
	}

	@Test
	public void debugCameraRay() {
		log("Testing camera ray generation...");

		// Create camera same as in renderSingleSphere
		PinholeCamera camera = new PinholeCamera();
		camera.setLocation(new Vector(0.0, 0.0, 10.0));
		camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
		camera.setFocalLength(0.05);
		camera.setProjectionDimensions(0.1, 0.1);

		log("Camera at (0, 0, 10), looking at (0, 0, -1)");
		log("Focal length: 0.05, projection: 0.1x0.1");

		// Generate ray for center pixel using width x height screen
		// Center pixel is at (32, 32) in the width x height grid
		CollectionProducer centerPos = pair(width / 2.0, height / 2.0);
		CollectionProducer screenDim = pair(width, height);

		CollectionProducer cameraRay = camera.rayAt(centerPos, screenDim);

		// Evaluate the ray - use Ray view wrapper
		Ray ray = new Ray(cameraRay.get().evaluate(), 0);

		log("Camera ray origin: (" + ray.getOrigin().getX() + ", " + ray.getOrigin().getY() + ", " + ray.getOrigin().getZ() + ")");
		log("Camera ray direction: (" + ray.getDirection().getX() + ", " + ray.getDirection().getY() + ", " + ray.getDirection().getZ() + ")");
		log("Expected: origin (0, 0, 10), direction pointing towards -Z");

		// Verify direction is pointing towards -Z (doesn't need to be normalized for intersection)
		assertTrue("Direction Z should be negative (pointing towards sphere)", ray.getDirection().getZ() < 0);

		// Now test intersection with this camera ray
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);

		org.almostrealism.geometry.ShadableIntersection intersection = sphere.intersectAt(cameraRay);
		org.almostrealism.collect.PackedCollection distance = intersection.getDistance().get().evaluate();

		log("Intersection distance from camera ray: " + distance.toDouble(0));
		double dist = distance.toDouble(0);
		assertTrue("Ray should hit sphere (distance > 0)", dist > 0);
		log("Camera ray correctly hits sphere at distance " + dist);
	}

	@Test
	public void renderSingleSphere() throws Exception {
		log("Creating simple scene with one sphere...");

		Sphere.enableTransform = false; // TODO  This should not be required

		// Create scene
		Scene<ShadableSurface> scene = new Scene<>();

		// Create a sphere
		Sphere sphere = new Sphere();
		sphere.setLocation(new Vector(0.0, 0.0, 0.0));
		sphere.setSize(1.0);
		sphere.setColor(new RGB(0.8, 0.2, 0.2)); // Red sphere
		sphere.setShaders(new Shader[] { DiffuseShader.defaultDiffuseShader });

		// Ensure transform is calculated before adding to scene
		sphere.calculateTransform();
		log("Sphere transform calculated: " + (sphere.getTransform() != null));

		scene.add(sphere);

		// Add a point light
		PointLight light = new PointLight(new Vector(5.0, 5.0, 10.0));
		scene.addLight(light);

		log("Scene created with sphere and light");

		// Create camera
		try {
			PinholeCamera camera = new PinholeCamera();
			camera.setLocation(new Vector(0.0, 0.0, 10.0));
			camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
			camera.setFocalLength(0.05);
			camera.setProjectionDimensions(0.04, 0.04);  // Zoomed in from 0.1

			scene.setCamera(camera);
			log("Camera created and configured");

			// Create render parameters for a small image
			RenderParameters params = new RenderParameters();
			params.width = width;
			params.height = height;
			params.dx = width;
			params.dy = height;
			params.ssWidth = 1;
			params.ssHeight = 1;

			log("Render parameters: 64x64, no supersampling");

			// Create ray traced scene
			RayTracedScene rayTracedScene = new RayTracedScene(
				new RayIntersectionEngine(scene, new FogParameters()),
				camera,
				params
			);

			log("Starting render...");

			// Render the image
			RealizableImage realizableImage = rayTracedScene.realize(params);

			log("Evaluating image...");

			// Evaluate to get RGB data
			RGB[][] imageData = realizableImage.get().evaluate();

			log("Image evaluated successfully!");
			log("Image size: " + imageData.length + "x" + imageData[0].length);

			// Check that we got some non-black pixels
			int nonBlackPixels = 0;
			for (int x = 0; x < imageData.length; x++) {
				for (int y = 0; y < imageData[x].length; y++) {
					RGB pixel = imageData[x][y];
					if (pixel != null && (pixel.getRed() > 0.01 || pixel.getGreen() > 0.01 || pixel.getBlue() > 0.01)) {
						nonBlackPixels++;
					}
				}
			}

			log("Non-black pixels: " + nonBlackPixels);

			// Try to save the image
			try {
				File outputDir = new File("results");
				if (!outputDir.exists()) {
					outputDir.mkdirs();
				}

				File outputFile = new File("results/simple-sphere-test.jpg");
				log("Saving image to: " + outputFile.getAbsolutePath());

				ImageCanvas.encodeImageFile(v(imageData).get(), outputFile, ImageCanvas.JPEGEncoding);

				log("Image saved successfully!");
			} catch (IOException e) {
				log("Warning: Could not save image: " + e.getMessage());
			}

			assertTrue("Should have some non-black pixels", nonBlackPixels > 0);
		} catch (Exception e) {
			log("Exception during render: " + e.getMessage());
			e.printStackTrace();
			throw e;
		}
	}

	@Test
	public void renderTwoSpheres() throws Exception {
		log("Creating scene with two spheres...");

		// Create scene
		Scene<ShadableSurface> scene = new Scene<>();

		// Create first sphere (red)
		Sphere sphere1 = new Sphere();
		sphere1.setLocation(new Vector(-1.5, 0.0, 0.0));
		sphere1.setSize(1.0);
		sphere1.setColor(new RGB(0.8, 0.2, 0.2)); // Red
		((AbstractSurface) sphere1).setShaders(new org.almostrealism.color.Shader[] {
			DiffuseShader.defaultDiffuseShader
		});
		sphere1.calculateTransform();

		// Create second sphere (green)
		Sphere sphere2 = new Sphere();
		sphere2.setLocation(new Vector(1.5, 0.0, 0.0));
		sphere2.setSize(1.0);
		sphere2.setColor(new RGB(0.2, 0.8, 0.2)); // Green
		((AbstractSurface) sphere2).setShaders(new org.almostrealism.color.Shader[] {
			DiffuseShader.defaultDiffuseShader
		});
		sphere2.calculateTransform();

		scene.add(sphere1);
		scene.add(sphere2);

		// Add a point light
		PointLight light = new PointLight(new Vector(0.0, 3.0, 3.0));
		scene.addLight(light);

		log("Scene created with two spheres and light");

		// Create camera - very close to capture large view of both spheres
		PinholeCamera camera = new PinholeCamera();
		camera.setLocation(new Vector(0.0, 0.0, 3.0));
		camera.setViewDirection(new Vector(0.0, 0.0, -1.0));
		camera.setFocalLength(0.05);
		camera.setProjectionDimensions(0.2, 0.2);

		scene.setCamera(camera);

		// Create render parameters
		RenderParameters params = new RenderParameters();
		params.width = 128;
		params.height = 128;
		params.dx = 128;
		params.dy = 128;
		params.ssWidth = 1;
		params.ssHeight = 1;

		log("Render parameters: 128x128");

		// Create and render
		RayTracedScene rayTracedScene = new RayTracedScene(
			new RayIntersectionEngine(scene, new FogParameters()),
			camera,
			params
		);

		log("Starting render...");
		RealizableImage realizableImage = rayTracedScene.realize(params);

		log("Evaluating image...");
		RGB[][] imageData = realizableImage.get().evaluate();

		log("Image evaluated! Size: " + imageData.length + "x" + imageData[0].length);

		// Check that we got some non-black pixels
		int nonBlackPixels = 0;
		for (int x = 0; x < imageData.length; x++) {
			for (int y = 0; y < imageData[x].length; y++) {
				RGB pixel = imageData[x][y];
				if (pixel != null && (pixel.getRed() > 0.01 || pixel.getGreen() > 0.01 || pixel.getBlue() > 0.01)) {
					nonBlackPixels++;
				}
			}
		}

		log("Non-black pixels: " + nonBlackPixels);

		// Try to save the image
		try {
			File outputDir = new File("results");
			if (!outputDir.exists()) {
				outputDir.mkdirs();
			}

			File outputFile = new File("results/two-spheres-test.jpg");
			log("Saving image to: " + outputFile.getAbsolutePath());

			ImageCanvas.encodeImageFile(v(imageData).get(), outputFile, ImageCanvas.JPEGEncoding);

			log("Image saved successfully!");
		} catch (Exception e) {
			log("Warning: Could not save image: " + e.getMessage());
		}
	}
}
