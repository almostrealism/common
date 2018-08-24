/*
 * Copyright 2017 Michael Murray
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

package org.almostrealism.color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.space.LightingContext;
import org.almostrealism.util.Producer;

/**
 * A {@link ShaderContext} provides access to the set of {@link Producer}s
 * in a {@link org.almostrealism.space.Scene}.
 * 
 * @author  Michael Murray
 */
public class ShaderContext extends LightingContext {
	private ContinuousField intersection;
	
	private Producer<RGB> surface;
	private Producer<RGB> otherSurfaces[];
	
	public RGB fogColor;
	public double fogRatio, fogDensity;

	private int refCount;
	private int exit, enter;

	public ShaderContext(Producer<RGB> surface, Light l) {
		this.surface = surface;
		this.setLight(l);
	}

	/**
	 * Constructs a new ShaderParameters object using the specified arguments.
	 * 
	 * @param intersection  The details about the surface/ray intersection.
	 * @param lightDirection  Vector object representing the direction toward the light (should be unit length).
	 * @param light  Light object representing the light.
	 * @param otherLights  Array of Light objects representing other lights in the scene.
	 * @param otherSurfaces  Collection of other Surface objects in the scene.
	 */
	public ShaderContext(ContinuousField intersection, Vector lightDirection, Light light,
							Iterable<Light> otherLights, Collection<Producer<RGB>> otherSurfaces) {
		this(intersection, lightDirection, light, otherLights, otherSurfaces.toArray(new Producer[0]));
	}
	
	private ShaderContext(ContinuousField intersection, Vector lightDirection, Light light,
			Iterable<Light> otherLights, Producer<RGB> otherSurfaces[]) {
		this(intersection, lightDirection, light, otherLights, null, otherSurfaces);
	}
	
	/**
	 * Constructs a new {@link ShaderContext} object using the specified arguments.
	 * 
	 * @param intersection TODO
	 * @param lightDirection  Vector object representing the direction toward the light (should be unit length).
	 * @param light  Light object representing the light.
	 * @param otherLights  Array of Light objects representing other lights in the scene.
	 * @param surface  Surface object to be shaded.
	 * @param otherSurfaces  Array of other Surface objects in the scene.
	 */
	public ShaderContext(ContinuousField intersection, Vector lightDirection, Light light,
							Iterable<Light> otherLights, Producer<RGB> surface, Producer<RGB> otherSurfaces[]) {
		this.intersection = intersection;
		this.setLightDirection(lightDirection);
		this.setLight(light);
		this.setOtherLights(otherLights);
		this.surface = surface;
		this.otherSurfaces = otherSurfaces;
		
		this.refCount = 0;
	}
	
	public void setIntersection(ContinuousField intersect) { intersection = intersect; }
	
	public ContinuousField getIntersection() { return intersection; }
	
	/**
	 * @param surface  The new Surface object.
	 */
	public void setSurface(Producer<RGB> surface) { this.surface = surface; }
	
	public Producer<RGB> getSurface() { return this.surface; }
	
	/**
	 * Sets the other Surfaces to those stored in the specified array.
	 * 
	 * @param s  Array of Surface objects to use.
	 */
	public void setOtherSurfaces(Producer<RGB>... s) { this.otherSurfaces = s; }
	
	/**
	 * Sets the other Surfaces to those stored in the specified array.
	 * 
	 * @param s  Array of Surface objects to use.
	 */
	public void setOtherSurfaces(Collection<Producer<RGB>> s) {
		this.otherSurfaces = (Producer<RGB>[]) s.toArray(new Producer[0]);
	}
	
	/**
	 * @return  An array of other Surface objects in the scene.
	 */
	public Producer<RGB>[] getOtherSurfaces() { return this.otherSurfaces; }

	public Iterable<? extends Producer<RGB>> getAllSurfaces() {
		List<Producer<RGB>> l = new ArrayList<>();
		if (getSurface() != null) l.add(getSurface());
		l.addAll(Arrays.asList(getOtherSurfaces()));
		return l;
	}
	
	/**
	 * @return  The number of reflections (or other types of direction change) undergone.
	 */
	public int getReflectionCount() { return this.refCount; }
	
	/** @return  The number of surface enterances undergone. */
	public int getEnteranceCount() { return this.enter; }
	
	/** @return  The number of surface exits undergone. */
	public int getExitCount() { return this.exit; }
	
	/** Adds one to the reflection count stored by this {@link ShaderContext}s object. */
	public void addReflection() { this.refCount++; }
	
	/** Adds one to the reflection count and the entrance count. */
	public void addEntrance() { this.enter++; this.refCount++; }
	
	/**
	 * Adds one to the reflection count and the exit count.
	 * This method may result in a random warning if exit count
	 * is greater than entrance count.
	 */
	public void addExit() {
		this.exit++; this.refCount++;
		
//		TODO  Restore this logging
//		if (this.exit > this.enter && Math.random() < Settings.randomWarningThreshold)
//			System.out.println(Settings.randomWarningSymbol +
//					"ShaderParameters: Exit count exceedes entrance count.");
	}

	@Override
	public ShaderContext clone() {
		ShaderContext c = new ShaderContext(surface, getLight());
		c.setLightDirection(getLightDirection());
		c.setOtherLights(getOtherLights());
		c.setIntersection(getIntersection());
		c.setOtherSurfaces(getOtherSurfaces());
		c.fogColor = fogColor;
		c.fogRatio = fogRatio;
		c.fogDensity = fogDensity;
		c.refCount = refCount;
		c.exit = exit;
		c.enter = enter;
		return c;
	}

	@Override
	public String toString() {
		return this.intersection + ", " + this.getLightDirection() + ", " +
				this.getLight() + ", " + this.getOtherLights() + ", " + this.surface;
	}
}
