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

import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.geometry.Curve;
import io.almostrealism.relation.Evaluable;

import java.util.Collection;
import java.util.function.Supplier;

public class PhotonFieldContext<T extends PhotonField, F extends Absorber> extends ShaderContext {
	private T field;
	private F film;

	public PhotonFieldContext(Curve<RGB> surface, Light l, T field, F film) {
		super(surface, l);
		this.field = field;
		this.film = film;
	}

	public PhotonFieldContext(ContinuousField intersection, Supplier<Evaluable<? extends Vector>> lightDirection,
							  Light light, Iterable<Light> otherLights,
							  Collection<Curve<RGB>> otherSurfaces,
							  T field, F film) {
		super(intersection, lightDirection, light, otherLights, otherSurfaces);
		this.field = field;
		this.film = film;
	}

	public PhotonFieldContext(ContinuousField intersection, Supplier<Evaluable<? extends Vector>> lightDirection,
							  Light light, Iterable<Light> otherLights,
							  Curve<RGB> surface, Curve<RGB>[] otherSurfaces,
							  T field, F film) {
		super(intersection, lightDirection, light, otherLights, surface, otherSurfaces);
		this.field = field;
		this.film = film;
	}

	public T getPhotonField() { return field; }

	public F getFilm() { return film; }

	// TODO  Implement clone
}
