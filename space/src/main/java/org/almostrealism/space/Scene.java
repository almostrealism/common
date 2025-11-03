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
 * {@link Scene} extends {@link SurfaceList} to store {@link Light}s and a {@link Camera}.
 *
 * TODO  Since the ray tracing engine now accepts a camera separately, this field should
 *       probably be removed (or only stored by a subclass, projectable scene).
 */
public class Scene<T extends ShadableSurface> extends SurfaceList<T> {
	private Camera camera;
	
	private List<Light> lights;
	
	/** Constructs a {@link Scene} with no {@link Camera} and no {@link Light}s or {@link ShadableSurface}s. */
	public Scene() {
		this.setLights(new ArrayList<>());
	}

	/** Constructs a {@link Scene} with the specified camera and no {@link Light}s or {@link ShadableSurface}s. */
	public Scene(Camera c) { this(); this.setCamera(c); }

	/**
	 * Constructs a {@link Scene} object with no {@link Camera}, no {@link Light}s,
	 * and the surfaces represented by the specified {@link ShadableSurface} array.
	 */
	public Scene(T surfaces[]) {
		this();
		this.setSurfaces(surfaces);
	}
	
	/**
	 * Constructs a {@link Scene} with the specified {@link Camera}, {@link Light}s,
	 * and {@link ShadableSurface}s.
	 */
	public Scene(Camera camera, List<Light> lights, T surfaces[]) {
		this.setCamera(camera);
		this.setLights(lights);
		this.setSurfaces(surfaces);
	}
	
	public void setSurfaces(T surfaces[]) {
		clear();
		addAll(Arrays.asList(surfaces));
	}
	
	public ShadableSurface[] getSurfaces() { return toArray(new ShadableSurface[0]); }
	
	/** Sets the camera of this {@link Scene}. */
	public void setCamera(Camera camera) { this.camera = camera; }
	
	/**
	 * Replace the {@link List} of {@link Light}s for this {@link Scene} with the specified
	 * {@link Light} {@link List}.
	 */
	public void setLights(List<Light> lights) { this.lights = lights; }
	
	/** Adds the specified {@link Light} to this {@link Scene}. */
	public void addLight(Light light) { lights.add(light); }
	
	/** Removes the {@link Light} stored at the specified index from this {@link Scene}. */
	public void removeLight(int index) { lights.remove(index); }
	
	/** Returns the Camera object stored by this Scene object. */
	public Camera getCamera() { return this.camera; }
	
	/** Returns {@link Light} {@link List} for this {@link Scene}. */
	public List<Light> getLights() { return this.lights; }
	
	/** Returns the {@link Light} stored by this {@link Scene} at the specified index. */
	public Light getLight(int index) { return this.lights.get(index); }
	
	/**
	 * @return  A Scene object that stores the same Camera, Lights, and Surfaces as this Scene object.
	 */
	public Object clone() {
		Scene l = (Scene) super.clone();
		l.setCamera(this.camera);
		l.setLights(this.lights);
		l.addAll(this);
		return l;
	}

	@Deprecated
	public static List<Curve<RGB>> combineSurfaces(Curve<RGB> surface,
										Iterator<Curve<RGB>> otherSurfaces) {
		List<Curve<RGB>> allSurfaces = new ArrayList<>();
		while (otherSurfaces.hasNext()) { allSurfaces.add(otherSurfaces.next()); }
		allSurfaces.add(surface);
		return allSurfaces;
	}

	@Deprecated
	public static List<Curve<RGB>> combineSurfaces(Curve<RGB> surface, Iterable<? extends Curve<RGB>> otherSurfaces) {
		List<Curve<RGB>> allSurfaces = new ArrayList<>();
		for (Curve<RGB> s : otherSurfaces) { allSurfaces.add(s); }
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
