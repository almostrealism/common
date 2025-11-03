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

package org.almostrealism.color;

import java.util.Collection;
import java.util.List;

import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Curve;
import io.almostrealism.relation.Producer;

/**
 * A {@link DirectionalAmbientLight} represents an ambient light source that always
 * comes from a particular direction. The direction is a vector that represents
 * the direction from which the light enters the scene. By default the light
 * comes from the top, parallel to the yz plane.
 */
public class DirectionalAmbientLight extends AmbientLight implements VectorFeatures {
  private Vector direction;

	/**
	 * Constructs a DirectionalAmbientLight object with the default direction, intensity, and color.
	 */
	public DirectionalAmbientLight() {
		super(1.0, new RGB(1.0, 1.0, 1.0));
		this.setDirection(new Vector(0.0, -1.0, 0.0));
	}
	
	/**
	 * Constructs a {@link DirectionalAmbientLight} with default intensity and color and the direction
	 * represented by the specified {@link Vector}.
	 */
	public DirectionalAmbientLight(Vector direction) {
		super(1.0, new RGB(1.0, 1.0, 1.0));
		this.setDirection(direction);
	}
	
	/**
	  Constructs a DirectionalAmbientLight object with the direction, intensity, and color represented by the specified values.
	*/
	
	public DirectionalAmbientLight(double intensity, RGB color, Vector direction) {
		super(intensity, color);
		this.setDirection(direction);
	}
	
	/**
	 * Sets the direction of this {@link DirectionalAmbientLight} to the direction represented by the
	 * specified {@link Vector}.
	 */
	public void setDirection(Vector direction) {
		this.direction = direction;
	}
	
	/**
	 * Returns the direction of this DirectionalAmbientLight object as a Vector object.
	 */
	public Vector getDirection() {
		return this.direction;
	}

	/**
	 * Performs the lighting calculations for the specified surface at the specified point of intersection
	 * on that surface using the lighting data from the specified DirectionalAmbientLight object and returns
	 * an RGB object that represents the color of the point. A list of all other surfaces in the scene must
	 * be specified for reflection/shadowing. This list does not include the specified surface for which
	 * the lighting calculations are to be done.
	 *
	 * @param intersection  The intersection point on the surface to be shaded.
	 * @param surface  The Surface object to use for shading calculations.
	 * @param otherSurfaces  An array of Surface objects that are also in the scene.
	 * @param p  A {@link ShaderContext} that stores all parameters that are persisted
	 *           during a single set of ray casting events (reflections, refractions,
	 *           etc.) (null is accepted).
	 */
	public Producer<RGB> lightingCalculation(ContinuousField intersection, Curve<RGB> surface,
											 Collection<Curve<RGB>> otherSurfaces,
											 List<Light> otherLights, ShaderContext p) {
		Producer<RGB> color;

		Vector l = (getDirection().divide(getDirection().length())).minus();

		if (p == null) {
			color = surface instanceof Shadable ? ((Shadable) surface).shade(new ShaderContext(intersection, v(l), this, otherLights, otherSurfaces)) : null;
		} else {
			p.setIntersection(intersection);
			p.setLightDirection(v(l));
			p.setLight(this);
			p.setOtherLights(otherLights);
			p.setOtherSurfaces(otherSurfaces);

			color = surface instanceof Shadable ? ((Shadable) surface).shade(p) : null;
		}

		return color;
	}

	/**
	 * Returns "Directional Ambient Light".
	 */
	@Override
	public String toString() {
		return "Directional Ambient Light";
	}
}
