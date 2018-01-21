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
import java.util.concurrent.Callable;

import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.DiscreteField;
import org.almostrealism.algebra.TripleFunction;
import org.almostrealism.algebra.Vector;
import org.almostrealism.space.LightingContext;


/**
 * A {@link ShaderContext} object provides access to the set of {@link ColorProducer}s in a scene.
 * 
 * @author  Michael Murray
 */
public class ShaderContext extends LightingContext {
	private ContinuousField intersection;
	
	private Callable<ColorProducer> surface;
	private Callable<ColorProducer> otherSurfaces[];
	
	public RGB fogColor;
	public double fogRatio, fogDensity;

	private int refCount;
	private int exit, enter;

	public ShaderContext(Callable<ColorProducer> surface, Light l) {
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
							Light otherLights[], Collection<Callable<ColorProducer>> otherSurfaces) {
		this(intersection, lightDirection, light, otherLights, otherSurfaces.toArray(new Callable[0]));
	}
	
	private ShaderContext(ContinuousField intersection, Vector lightDirection, Light light,
			Light otherLights[], Callable<ColorProducer> otherSurfaces[]) {
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
							Light otherLights[], Callable<ColorProducer> surface, Callable<ColorProducer> otherSurfaces[]) {
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
	public void setSurface(Callable<ColorProducer> surface) { this.surface = surface; }
	
	public Callable<ColorProducer> getSurface() { return this.surface; }
	
	/**
	 * Sets the other Surfaces to those stored in the specified array.
	 * 
	 * @param s  Array of Surface objects to use.
	 */
	public void setOtherSurfaces(Callable<ColorProducer>... s) { this.otherSurfaces = s; }
	
	/**
	 * Sets the other Surfaces to those stored in the specified array.
	 * 
	 * @param s  Array of Surface objects to use.
	 */
	public void setOtherSurfaces(Collection<Callable<ColorProducer>> s) {
		this.otherSurfaces = (Callable<ColorProducer>[]) s.toArray(new Callable[0]);
	}
	
	/**
	 * @return  An array of other Surface objects in the scene.
	 */
	public Callable<ColorProducer>[] getOtherSurfaces() { return this.otherSurfaces; }

	public Iterable<? extends Callable<ColorProducer>> getAllSurfaces() {
		List<Callable<ColorProducer>> l = new ArrayList<>();
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
	
	public String toString() {
		return this.intersection + ", " + this.getLightDirection() + ", " +
				this.getLight() + ", " + Arrays.toString(this.getOtherLights()) + ", " + this.surface;
	}
}
