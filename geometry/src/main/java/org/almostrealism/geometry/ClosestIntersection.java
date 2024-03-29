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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Evaluable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class ClosestIntersection extends ArrayList<Producer<Ray>> implements ContinuousField {
	private Producer<Ray> r;
	private List<ContinuousField> s;

	public ClosestIntersection(Producer<Ray> ray, Iterable<Intersectable> surfaces) {
		r = ray;
		s = new ArrayList<>();

		for (Intersectable<?> in : surfaces) {
			s.add(in.intersectAt(ray));
		}

		this.add(() -> args -> {
			double d = Double.MAX_VALUE;
			ContinuousField intersection = null;

			p:
			for (ContinuousField in : s) {
				if (in == null) continue p;

				Scalar s = ((Evaluable<Scalar>) ((ShadableIntersection) in).getDistance().get()).evaluate(args);
				if (s == null) continue p;

				double v = s.getValue();
				if (v >= 0.0 && v < d) {
					d = v;
					intersection = in;
				}
			}

			return intersection == null ? null : intersection.get(0).get().evaluate(args);
		});
	}

	@Override
	public Producer<Vector> getNormalAt(Producer<Vector> point) {
		return () -> args -> {
			double d = Double.MAX_VALUE;
			Vector normal = null;

			p:
			for (ContinuousField in : s) {
				if (in == null) continue p;

				Scalar s = ((Evaluable<Scalar>) ((ShadableIntersection) in).getDistance().get()).evaluate(args);
				if (s == null) continue p;

				double v = s.getValue();
				if (v >= 0.0 && v < d) {
					d = v;
					normal = in.getNormalAt(point).get().evaluate(args);
				}
			}

			return normal;
		};
	}
}
