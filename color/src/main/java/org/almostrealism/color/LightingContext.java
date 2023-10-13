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

import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * @author  Michael Murray
 */
public class LightingContext {
	private Producer<Vector> lightDirection;
	private Light light;
	private Iterable<Light> otherLights;
	
	/**
	 * Sets the direction toward the light to the specified {@link Vector} {@link Producer}.
	 * 
	 * @param l  Vector object to use.
	 */
	public void setLightDirection(Producer<Vector> l) { this.lightDirection = l; }
	
	/**
	 * @return  A {@link Vector} {@link Producer} representing the direction toward the light (this can be expected to be unit length).
	 */
	public Producer<Vector> getLightDirection() { return this.lightDirection; }
	
	/**
	 * Sets the Light to the specified Light object.
	 * 
	 * @param l  Light object to use.
	 */
	public void setLight(Light l) { this.light = l; }
	
	/** @return  A Light object representing the light. */
	public Light getLight() { return this.light; }
	
	/**
	 * Sets the other Lights to those stored in the specified array.
	 * 
	 * @param l  Array of Light objects to use.
	 */
	public void setOtherLights(Iterable<Light> l) { this.otherLights = l; }
	
	/** @return  An array of Light objects representing the other lights in the scene. */
	public Iterable<Light> getOtherLights() { return this.otherLights; }
	
	/**
	 * TOOO  This should cache all lights, so that a new array is not created every time.
	 */
	public List<Light> getAllLights() {
		List<Light> li = new ArrayList<>();
		li.add(light);
		for (Light l : otherLights) li.add(l);
		return li;
	}
}
