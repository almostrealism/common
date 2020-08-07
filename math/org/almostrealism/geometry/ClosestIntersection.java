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
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.ContinuousField;
import org.almostrealism.algebra.Intersectable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.Vector;
import org.almostrealism.space.ShadableIntersection;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class ClosestIntersection extends ArrayList<Producer<Ray>> implements ContinuousField {
	private Producer<Ray> r;
	private List<ContinuousField> s;

	public ClosestIntersection(Producer<Ray> ray, Iterable<Intersectable> surfaces) {
		r = ray;
		s = new ArrayList<>();

		for (Intersectable<?> in : surfaces) {
			s.add(in.intersectAt(ray));
		}

		this.add(new Producer<Ray>() {
			@Override
			public Ray evaluate(Object[] args) {
				double d = Double.MAX_VALUE;
				ContinuousField intersection = null;

				p: for (ContinuousField in : s) {
					if (in == null) continue p;

					Scalar s = (Scalar) ((ShadableIntersection) in).getDistance().evaluate(args);
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
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		return new Producer<Vector>() {
			@Override
			public Vector evaluate(Object[] args) {
				double d = Double.MAX_VALUE;
				Vector normal = null;

				p: for (ContinuousField in : s) {
					if (in == null) continue p;

					Scalar s = (Scalar) ((ShadableIntersection) in).getDistance().evaluate(args);
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
	public Vector operate(Triple in) {
		// TODO
		return null;
	}

	@Override
	public Scope<? extends Variable> getScope(String prefix) {
		// TODO
		return null;
	}
}
