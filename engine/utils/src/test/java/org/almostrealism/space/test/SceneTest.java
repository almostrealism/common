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

import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.Curve;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.space.Plane;
import org.almostrealism.space.Scene;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Behavioral tests for {@link Scene}, covering camera/light/surface management,
 * cloning, bounding-solid aggregation, and the deprecated combine helpers.
 */
public class SceneTest extends TestSuiteBase {

	/** A default scene has no camera and an empty (non-null) light list. */
	@Test(timeout = 10000)
	public void defaultSceneState() {
		Scene<ShadableSurface> scene = new Scene<>();
		Assert.assertNull(scene.getCamera());
		Assert.assertNotNull(scene.getLights());
		Assert.assertEquals(0, scene.getLights().size());
	}

	/** The camera constructor stores the supplied camera. */
	@Test(timeout = 10000)
	public void cameraConstructor() {
		PinholeCamera camera = new PinholeCamera();
		Scene<ShadableSurface> scene = new Scene<>(camera);
		Assert.assertSame(camera, scene.getCamera());
	}

	/** Lights can be added, retrieved by index, and removed. */
	@Test(timeout = 10000)
	public void lightManagement() {
		Scene<ShadableSurface> scene = new Scene<>();
		PointLight l0 = new PointLight(new Vector(1.0, 1.0, 1.0), 1.0, new RGB(1.0, 1.0, 1.0));
		PointLight l1 = new PointLight(new Vector(2.0, 2.0, 2.0), 1.0, new RGB(1.0, 1.0, 1.0));

		scene.addLight(l0);
		scene.addLight(l1);
		Assert.assertEquals(2, scene.getLights().size());
		Assert.assertSame(l0, scene.getLight(0));
		Assert.assertSame(l1, scene.getLight(1));

		scene.removeLight(0);
		Assert.assertEquals(1, scene.getLights().size());
		Assert.assertSame(l1, scene.getLight(0));
	}

	/** setSurfaces replaces all surfaces and getSurfaces returns them as an array. */
	@Test(timeout = 10000)
	public void surfaceManagement() {
		Scene<ShadableSurface> scene = new Scene<>();
		Plane p0 = new Plane(Plane.XY);
		Plane p1 = new Plane(Plane.XZ);
		scene.setSurfaces(new ShadableSurface[] {p0, p1});

		Assert.assertEquals(2, scene.size());
		ShadableSurface[] surfaces = scene.getSurfaces();
		Assert.assertEquals(2, surfaces.length);
		Assert.assertSame(p0, surfaces[0]);

		scene.setSurfaces(new ShadableSurface[] {p1});
		Assert.assertEquals(1, scene.size());
	}

	/** clone produces an independent scene sharing camera and lights. */
	@Test(timeout = 10000)
	public void cloneSharesCameraAndLights() {
		PinholeCamera camera = new PinholeCamera();
		Scene<ShadableSurface> scene = new Scene<>(camera);
		PointLight light = new PointLight(new Vector(1.0, 1.0, 1.0), 1.0, new RGB(1.0, 1.0, 1.0));
		scene.addLight(light);
		Plane surface = new Plane(Plane.XY);
		scene.add(surface);

		Scene<ShadableSurface> clone = (Scene<ShadableSurface>) scene.clone();
		Assert.assertSame(camera, clone.getCamera());
		Assert.assertSame(light, clone.getLight(0));
		Assert.assertSame(scene.getLights(), clone.getLights());
		Assert.assertTrue(clone.contains(surface));
		// The clone must copy each surface exactly once, preserving size and order.
		Assert.assertEquals(scene.size(), clone.size());
		Assert.assertEquals(1, clone.size());
		Assert.assertSame(surface, clone.get(0));
	}

	/** An empty scene has no bounding solid. */
	@Test(timeout = 10000)
	public void emptySceneBoundingSolidIsNull() {
		Scene<ShadableSurface> scene = new Scene<>();
		Assert.assertNull(scene.calculateBoundingSolid());
	}

	/** A scene of surfaces that report no bounds still yields a null aggregate. */
	@Test(timeout = 10000)
	public void planeSceneBoundingSolidIsNull() {
		Scene<ShadableSurface> scene = new Scene<>();
		scene.add(new Plane(Plane.XY));
		scene.add(new Plane(Plane.XZ));
		Assert.assertNull(scene.calculateBoundingSolid());
	}

	/** combineSurfaces (iterable form) appends the primary surface after the others. */
	@Test(timeout = 10000)
	public void combineSurfacesIterable() {
		Plane primary = new Plane(Plane.XY);
		List<Curve<PackedCollection>> others = new ArrayList<>();
		Plane a = new Plane(Plane.XZ);
		Plane b = new Plane(Plane.YZ);
		others.add(a);
		others.add(b);

		List<Curve<PackedCollection>> combined = Scene.combineSurfaces(primary, others);
		Assert.assertEquals(3, combined.size());
		Assert.assertSame(a, combined.get(0));
		Assert.assertSame(b, combined.get(1));
		Assert.assertSame(primary, combined.get(2));
	}

	/** combineSurfaces (iterator form) appends the primary surface after the others. */
	@Test(timeout = 10000)
	public void combineSurfacesIterator() {
		Plane primary = new Plane(Plane.XY);
		Plane a = new Plane(Plane.XZ);
		List<Curve<PackedCollection>> others = new ArrayList<>(Arrays.asList(a));

		List<Curve<PackedCollection>> combined = Scene.combineSurfaces(primary, others.iterator());
		Assert.assertEquals(2, combined.size());
		Assert.assertSame(a, combined.get(0));
		Assert.assertSame(primary, combined.get(1));
	}

	/** setLights replaces the entire light list. */
	@Test(timeout = 10000)
	public void setLightsReplacesList() {
		Scene<ShadableSurface> scene = new Scene<>();
		scene.addLight(new PointLight(new Vector(0.0, 0.0, 0.0), 1.0, new RGB(1.0, 1.0, 1.0)));

		List<Light> replacement = new ArrayList<>();
		scene.setLights(replacement);
		Assert.assertEquals(0, scene.getLights().size());
		Assert.assertSame(replacement, scene.getLights());
	}
}
