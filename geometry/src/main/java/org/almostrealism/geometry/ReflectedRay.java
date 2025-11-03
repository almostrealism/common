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

public class ReflectedRay implements ProducerComputation<Ray>, GeometryFeatures {
	private Producer<Vector> point;
	private Producer<Vector> normal;
	private Producer<Vector> reflected;
	private double blur;

	public ReflectedRay(Producer<Vector> point, Producer<Vector> incident, Producer<Vector> normal, double blur) {
		this.point = point;
		this.normal = normal;
		this.reflected = reflect(incident, normal);
		this.blur = blur;
	}

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
