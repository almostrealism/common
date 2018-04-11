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
import java.util.concurrent.Callable;

import org.almostrealism.algebra.Camera;
import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.Light;

/**
 * {@link Scene} extends {@link SurfaceList} to store {@link Light}s and a {@link Camera}.
 *
 * TODO  Since the ray tracing engine now accepts a camera separately, this field should
 *       probably be removed (or only stored by a subclass, projectable scene).
 */
public class Scene<T extends ShadableSurface> extends SurfaceList<T> {
	private Camera camera;
	
	private Light lights[];
	
	/** Constructs a {@link Scene} with no {@link Camera} and no {@link Light}s or {@link ShadableSurface}s. */
	public Scene() {
		this.setLights(new Light[0]);
	}

	/** Constructs a {@link Scene} with the specified camera and no {@link Light}s or {@link ShadableSurface}s. */
	public Scene(Camera c) { this.setCamera(c); }

	/**
	 * Constructs a {@link Scene} object with no camera object, no Light objects,
	 * and the surfaces represented by the specified {@link ShadableSurface} array.
	 */
	public Scene(T surfaces[]) {
		this.setLights(new Light[0]);
		this.setSurfaces(surfaces);
	}
	
	/**
	 * Constructs a {@link Scene} with the specified {@link Camera}, {@link Light}s,
	 * and {@link Gradient}s.
	 */
	public Scene(Camera camera, Light lights[], T surfaces[]) {
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
	 * Replaces all of the lights of this {@link Scene} object with those represented
	 * by the specified {@link Light} array.
	 */
	public void setLights(Light lights[]) { this.lights = lights; }
	
	/** Adds the specified Light object to this Scene object. */
	public void addLight(Light light) {
		Light newLights[] = new Light[this.lights.length + 1];
		
		System.arraycopy(this.lights, 0, newLights, 0, this.lights.length);
		newLights[newLights.length - 1] = light;
		
		this.setLights(newLights);
	}
	
	/**
	 * Removes the Light object stored at the specified index from this Scene object.
	 */
	public void removeLight(int index) {
		Light newLights[] = new Light[this.lights.length - 1];
		
		System.arraycopy(this.lights, 0, newLights, 0, index);
		if (index != this.lights.length - 1) {
			System.arraycopy(this.lights, index + 1, newLights, index, this.lights.length - (index + 1));
		}
		
		this.setLights(newLights);
	}
	
	/** Returns the Camera object stored by this Scene object. */
	public Camera getCamera() { return this.camera; }
	
	/** Returns the Light objects stored by this Scene object as a Light array. */
	public Light[] getLights() { return this.lights; }
	
	/** Returns the Surface object stored by this Scene object at the specified index. */
	public Light getLight(int index) { return this.lights[index]; }
	
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

	/**
	 * Removes the specified Surface object from the specified Surface object array and returns the new array.
	 * If the specified Surface object is not matched, the whole array is returned.
	 */
	public static ShadableSurface[] separateSurfaces(ShadableSurface surface, ShadableSurface allSurfaces[]) {
		for(int i = 0; i < allSurfaces.length; i++) {
			if (surface == allSurfaces[i]) {
				// See separateSurfaces method.
				
				ShadableSurface otherSurfaces[] = new ShadableSurface[allSurfaces.length - 1];
				
				for (int j = 0; j < i; j++) { otherSurfaces[j] = allSurfaces[j]; }
				for (int j = i + 1; j < allSurfaces.length; j++) { otherSurfaces[j - 1] = allSurfaces[j]; }
				
				return otherSurfaces;
			}
		}
		
		return allSurfaces;
	}
	
	public static List<Callable<ColorProducer>> combineSurfaces(ShadableSurface surface,
										Iterator<Callable<ColorProducer>> otherSurfaces) {
		List<Callable<ColorProducer>> allSurfaces = new ArrayList<Callable<ColorProducer>>();
		while (otherSurfaces.hasNext()) { allSurfaces.add(otherSurfaces.next()); }
		allSurfaces.add(surface);
		return allSurfaces;
	}
	
	public static List<Callable<ColorProducer>> combineSurfaces(Callable<ColorProducer> surface, Iterable<? extends Callable<ColorProducer>> otherSurfaces) {
		List<Callable<ColorProducer>> allSurfaces = new ArrayList<Callable<ColorProducer>>();
		for (Callable<ColorProducer> s : otherSurfaces) { allSurfaces.add(s); }
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
