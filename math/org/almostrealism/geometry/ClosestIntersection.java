/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.code.Scope;
import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.Intersectable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.relation.NameProvider;
import org.almostrealism.space.ShadableIntersection;
import org.almostrealism.util.Evaluable;

import java.util.ArrayList;
import java.util.List;

public class ClosestIntersection extends ArrayList<Evaluable<Ray>> implements ContinuousField {
	private Evaluable<? extends Ray> r;
	private List<ContinuousField> s;

	public ClosestIntersection(Evaluable<? extends Ray> ray, Iterable<Intersectable> surfaces) {
		r = ray;
		s = new ArrayList<>();

		for (Intersectable<?> in : surfaces) {
			s.add(in.intersectAt((Evaluable<Ray>) ray));
		}

		this.add(new Evaluable<Ray>() {
			@Override
			public Ray evaluate(Object[] args) {
				double d = Double.MAX_VALUE;
				ContinuousField intersection = null;

				p: for (ContinuousField in : s) {
					if (in == null) continue p;

					Scalar s = ((Evaluable<Scalar>) ((ShadableIntersection) in).getDistance().get()).evaluate(args);
					if (s == null) continue p;

					double v = s.getValue();
					if (v >= 0.0 && v < d) {
						d = v;
						intersection = in;
					}
				}

				return intersection == null ? null : intersection.get(0).evaluate(args);
			}

			// TODO  Hardware acceleration
			@Override
			public void compact() {
				r.compact();
			}
		});
	}

	@Override
	public Evaluable<Vector> getNormalAt(Evaluable<Vector> point) {
		return new Evaluable<Vector>() {
			@Override
			public Vector evaluate(Object[] args) {
				double d = Double.MAX_VALUE;
				Vector normal = null;

				p: for (ContinuousField in : s) {
					if (in == null) continue p;

					Scalar s = ((Evaluable<Scalar>) ((ShadableIntersection) in).getDistance().get()).evaluate(args);
					if (s == null) continue p;

					double v = s.getValue();
					if (v >= 0.0 && v < d) {
						d = v;
						normal = in.getNormalAt(point).evaluate(args);
					}
				}

				return normal;
			}

			// TODO  Hardware acceleration
			@Override
			public void compact() {
				point.compact();
				r.compact();
			}
		};
	}

	@Override
	public Vector operate(Vector in) {
		// TODO
		return null;
	}

	@Override
	public Scope<Vector> getScope(NameProvider p) {
		// TODO
		return null;
	}
}
