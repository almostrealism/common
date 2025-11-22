/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.physics;

import io.almostrealism.relation.Producer;
import org.almostrealism.geometry.ContinuousField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.Curve;
import io.almostrealism.relation.Evaluable;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * A specialized shader context that includes photon field and film (absorber) references.
 * <p>
 * {@code PhotonFieldContext} extends {@link ShaderContext} to provide additional context
 * for shading operations that involve photon field simulations. This is useful for
 * physically-based rendering where photon interactions need to be considered during
 * the shading process.
 * </p>
 *
 * <h2>Components</h2>
 * <ul>
 *   <li><b>PhotonField</b> - The field containing photons for this shading context</li>
 *   <li><b>Film (Absorber)</b> - The absorber that captures photons, typically representing
 *       a camera sensor or film plane</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <p>
 * This context is typically created during photon mapping or photon tracing rendering
 * passes and passed to shaders that need access to the photon field data.
 * </p>
 *
 * @param <T> the type of photon field
 * @param <F> the type of film/absorber
 * @author Michael Murray
 * @see ShaderContext
 * @see PhotonField
 * @see Absorber
 */
public class PhotonFieldContext<T extends PhotonField, F extends Absorber> extends ShaderContext {
	private T field;
	private F film;

	/**
	 * Constructs a photon field context with a surface and light.
	 *
	 * @param surface the surface curve for this context
	 * @param l       the light source
	 * @param field   the photon field
	 * @param film    the film/absorber for capturing photons
	 */
	public PhotonFieldContext(Curve<RGB> surface, Light l, T field, F film) {
		super(surface, l);
		this.field = field;
		this.film = film;
	}

	/**
	 * Constructs a photon field context with intersection data and multiple surfaces.
	 *
	 * @param intersection   the continuous field representing the intersection
	 * @param lightDirection the direction to the light source
	 * @param light          the primary light source
	 * @param otherLights    additional light sources in the scene
	 * @param otherSurfaces  other surfaces in the scene
	 * @param field          the photon field
	 * @param film           the film/absorber for capturing photons
	 */
	public PhotonFieldContext(ContinuousField intersection, Producer<Vector> lightDirection,
							  Light light, Iterable<Light> otherLights,
							  Collection<Curve<RGB>> otherSurfaces,
							  T field, F film) {
		super(intersection, lightDirection, light, otherLights, otherSurfaces);
		this.field = field;
		this.film = film;
	}

	/**
	 * Constructs a photon field context with intersection data and an array of surfaces.
	 *
	 * @param intersection   the continuous field representing the intersection
	 * @param lightDirection the direction to the light source
	 * @param light          the primary light source
	 * @param otherLights    additional light sources in the scene
	 * @param surface        the primary surface
	 * @param otherSurfaces  array of other surfaces in the scene
	 * @param field          the photon field
	 * @param film           the film/absorber for capturing photons
	 */
	public PhotonFieldContext(ContinuousField intersection, Producer<Vector> lightDirection,
							  Light light, Iterable<Light> otherLights,
							  Curve<RGB> surface, Curve<RGB>[] otherSurfaces,
							  T field, F film) {
		super(intersection, lightDirection, light, otherLights, surface, otherSurfaces);
		this.field = field;
		this.film = film;
	}

	/**
	 * Returns the photon field associated with this context.
	 *
	 * @return the photon field
	 */
	public T getPhotonField() { return field; }

	/**
	 * Returns the film/absorber associated with this context.
	 * <p>
	 * The film typically represents a camera sensor or other surface
	 * that captures photons during the simulation.
	 * </p>
	 *
	 * @return the film/absorber
	 */
	public F getFilm() { return film; }

	// TODO  Implement clone
}
