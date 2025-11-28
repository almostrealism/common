/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.space;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.almostrealism.color.ShadableSurface;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.geometry.Camera;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.geometry.Curve;

/**
 * A {@link Scene} represents a complete 3D scene containing surfaces, lights, and a camera
 * for ray tracing and rendering.
 *
 * <p>{@link Scene} extends {@link SurfaceList} to provide a container for all renderable
 * objects in a 3D environment. It manages:
 * <ul>
 *   <li><b>Surfaces</b>: The geometric objects that can be rendered (inherited from SurfaceList)</li>
 *   <li><b>Lights</b>: Light sources that illuminate the scene</li>
 *   <li><b>Camera</b>: The viewpoint from which the scene is rendered</li>
 * </ul>
 *
 * <h3>Usage Example:</h3>
 * <pre>{@code
 * Scene<ShadableSurface> scene = new Scene<>();
 *
 * // Add surfaces
 * scene.add(new Sphere(new Vector(0, 0, 0), 1.0, new RGB(1, 0, 0)));
 * scene.add(new Plane(new Vector(0, -1, 0), new Vector(0, 1, 0), 10, 10, 0.1));
 *
 * // Add lights
 * scene.addLight(new PointLight(new Vector(5, 5, 5), 1.0, new RGB(1, 1, 1)));
 *
 * // Set camera
 * scene.setCamera(new PinholeCamera(new Vector(0, 0, -10), new Vector(0, 0, 1), new Vector(0, 1, 0)));
 *
 * // Calculate scene bounds
 * BoundingSolid bounds = scene.calculateBoundingSolid();
 * }</pre>
 *
 * <p>Note: The camera field may be deprecated in future versions as the ray tracing engine
 * now accepts the camera separately.
 *
 * @param <T> the type of surface objects in this scene, must extend {@link ShadableSurface}
 * @author Michael Murray
 * @see SurfaceList
 * @see Light
 * @see Camera
 */
public class Scene<T extends ShadableSurface> extends SurfaceList<T> {
	/** The camera used to view the scene. */
	private Camera camera;

	/** The list of lights illuminating the scene. */
	private List<Light> lights;

	/**
	 * Constructs an empty {@link Scene} with no camera, lights, or surfaces.
	 */
	public Scene() {
		this.setLights(new ArrayList<>());
	}

	/**
	 * Constructs a {@link Scene} with the specified camera.
	 *
	 * @param c the camera to use for viewing the scene
	 */
	public Scene(Camera c) { this(); this.setCamera(c); }

	/**
	 * Constructs a {@link Scene} with the specified surfaces but no camera or lights.
	 *
	 * @param surfaces the surfaces to add to the scene
	 */
	public Scene(T surfaces[]) {
		this();
		this.setSurfaces(surfaces);
	}

	/**
	 * Constructs a {@link Scene} with the specified camera, lights, and surfaces.
	 *
	 * @param camera   the camera to use for viewing the scene
	 * @param lights   the lights illuminating the scene
	 * @param surfaces the surfaces in the scene
	 */
	public Scene(Camera camera, List<Light> lights, T surfaces[]) {
		this.setCamera(camera);
		this.setLights(lights);
		this.setSurfaces(surfaces);
	}

	/**
	 * Replaces all surfaces in this scene with the specified array.
	 *
	 * @param surfaces the new surfaces for this scene
	 */
	public void setSurfaces(T surfaces[]) {
		clear();
		addAll(Arrays.asList(surfaces));
	}

	/**
	 * Returns all surfaces in this scene as an array.
	 *
	 * @return an array containing all surfaces in the scene
	 */
	public ShadableSurface[] getSurfaces() { return toArray(new ShadableSurface[0]); }
	
	/**
	 * Sets the camera used to view this scene.
	 *
	 * @param camera the camera to use
	 */
	public void setCamera(Camera camera) { this.camera = camera; }

	/**
	 * Replaces the list of lights for this scene with the specified list.
	 *
	 * @param lights the new list of lights
	 */
	public void setLights(List<Light> lights) { this.lights = lights; }

	/**
	 * Adds a light to this scene.
	 *
	 * @param light the light to add
	 */
	public void addLight(Light light) { lights.add(light); }

	/**
	 * Removes the light at the specified index from this scene.
	 *
	 * @param index the index of the light to remove
	 */
	public void removeLight(int index) { lights.remove(index); }

	/**
	 * Returns the camera used to view this scene.
	 *
	 * @return the camera, or null if not set
	 */
	public Camera getCamera() { return this.camera; }

	/**
	 * Returns the list of lights illuminating this scene.
	 *
	 * @return the list of lights
	 */
	public List<Light> getLights() { return this.lights; }

	/**
	 * Returns the light at the specified index.
	 *
	 * @param index the index of the light to retrieve
	 * @return the light at the specified index
	 */
	public Light getLight(int index) { return this.lights.get(index); }

	/**
	 * Creates a shallow clone of this scene.
	 *
	 * <p>The cloned scene shares the same camera, lights, and surfaces as this scene.
	 *
	 * @return a clone of this scene
	 */
	public Object clone() {
		Scene l = (Scene) super.clone();
		l.setCamera(this.camera);
		l.setLights(this.lights);
		l.addAll(this);
		return l;
	}

	@Deprecated
	public static List<Curve<PackedCollection>> combineSurfaces(Curve<PackedCollection> surface,
										Iterator<Curve<PackedCollection>> otherSurfaces) {
		List<Curve<PackedCollection>> allSurfaces = new ArrayList<>();
		while (otherSurfaces.hasNext()) { allSurfaces.add(otherSurfaces.next()); }
		allSurfaces.add(surface);
		return allSurfaces;
	}

	@Deprecated
	public static List<Curve<PackedCollection>> combineSurfaces(Curve<PackedCollection> surface, Iterable<? extends Curve<PackedCollection>> otherSurfaces) {
		List<Curve<PackedCollection>> allSurfaces = new ArrayList<>();
		for (Curve<PackedCollection> s : otherSurfaces) { allSurfaces.add(s); }
		allSurfaces.add(surface);
		return allSurfaces;
	}

	/**
	 * Returns the minimum bounding solid that encompases this scene.
	 * For a scene object to be considered in the bounding calculation it
	 * must provide an implementation for {@link AbstractSurface#calculateBoundingSolid}.
	 */
	public BoundingSolid calculateBoundingSolid() {
		BoundingSolid boundingSolid = null;
		for (ShadableSurface surface : this) {
			BoundingSolid _boundingSolid = surface.calculateBoundingSolid();
			if (boundingSolid == null) {
				boundingSolid = _boundingSolid;
			} else if (_boundingSolid != null) {
				boundingSolid = boundingSolid.combine(_boundingSolid);
			}
		}
		return boundingSolid;
	}
}
