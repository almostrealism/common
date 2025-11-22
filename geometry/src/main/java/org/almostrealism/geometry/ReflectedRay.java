/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.geometry;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.kernel.KernelStructureContext;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.MemoryBank;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;

/**
 * Computes a reflected ray from an intersection point, given an incident direction and surface normal.
 * This is used in ray tracing to calculate specular reflections.
 *
 * <p>The reflection formula used is: {@code R = I - 2(N . I)N / |N|^2}</p>
 *
 * <p>The blur parameter allows for glossy reflections by randomly perturbing the
 * reflected ray direction. A blur of 0.0 produces perfect mirror reflections,
 * while higher values produce increasingly diffuse reflections.</p>
 *
 * <p>Usage example:</p>
 * <pre>{@code
 * ReflectedRay reflection = new ReflectedRay(hitPoint, incidentDir, normal, 0.0);
 * Ray reflectedRay = reflection.get().evaluate();
 * }</pre>
 *
 * @author Michael Murray
 * @see Ray
 * @see GeometryFeatures#reflect(Producer, Producer)
 */
public class ReflectedRay implements ProducerComputation<Ray>, GeometryFeatures {
	private Producer<Vector> point;
	private Producer<Vector> normal;
	private Producer<Vector> reflected;
	private double blur;

	/**
	 * Constructs a ReflectedRay computation.
	 *
	 * @param point the intersection point where the reflection occurs
	 * @param incident the incident ray direction (pointing towards the surface)
	 * @param normal the surface normal at the intersection point
	 * @param blur the amount of random perturbation for glossy reflections (0.0 = perfect mirror)
	 */
	public ReflectedRay(Producer<Vector> point, Producer<Vector> incident, Producer<Vector> normal, double blur) {
		this.point = point;
		this.normal = normal;
		this.reflected = reflect(incident, normal);
		this.blur = blur;
	}

	/**
	 * Returns an evaluable that computes the reflected ray.
	 * If blur is non-zero, the reflected direction is randomly perturbed
	 * to simulate glossy reflections.
	 *
	 * @return an evaluable that produces the reflected ray when evaluated
	 */
	@Override
	public Evaluable<Ray> get() {
		Evaluable<Vector> nor = normal.get();
		Evaluable<Vector> refl = reflected.get();

		return new Evaluable<>() {
			@Override
			public MemoryBank<Ray> createDestination(int size) { return Ray.bank(size); }

			@Override
			public Ray evaluate(Object[] args) {
				Vector n = nor.evaluate(args);
				Vector ref = refl.evaluate(args);

				if (blur != 0.0) {
					double a = blur * (-0.5 + Math.random());
					double b = blur * (-0.5 + Math.random());

					Vector u, v, w = (Vector) n.clone();

					Vector t = (Vector) n.clone();

					if (t.getX() < t.getY() && t.getY() < t.getZ()) {
						t.setX(1.0);
					} else if (t.getY() < t.getX() && t.getY() < t.getZ()) {
						t.setY(1.0);
					} else {
						t.setZ(1.0);
					}

					w.divideBy(w.length());

					u = t.crossProduct(w);
					u.divideBy(u.length());

					v = w.crossProduct(u);

					ref.addTo(u.multiply(a));
					ref.addTo(v.multiply(b));
					ref.divideBy(ref.length());
				}

				return new Ray(point.get().evaluate(args), ref);
			}
		};
	}

	@Override
	public Scope<Ray> getScope(KernelStructureContext context) {
		throw new RuntimeException("Not implemented");
	}
}
