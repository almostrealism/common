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

package org.almostrealism.algebra;

import org.almostrealism.geometry.Ray;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class ClosestIntersection<T extends Intersection> implements Producer<T> {
	private Producer<Ray> r;
	private List<Producer<T>> s;

	public ClosestIntersection(Producer<Ray> ray, Iterable<? extends Intersectable<T, ?>> surfaces) {
		r = ray;
		s = new ArrayList<>();

		for (Intersectable<T, ?> in : surfaces) {
			s.add(in.intersectAt(ray));
		}
	}

	@Override
	public T evaluate(Object[] args) {
		double d = Double.MAX_VALUE;
		T intersection = null;

		p: for (Producer<T> in : s) {
			T inter = in.evaluate(args);
			if (inter == null) continue p;

			double v = inter.getDistance().getValue();
			if (v >= 0.0 && v < d) {
				d = v;
				intersection = inter;
			}
		}

		return intersection;
	}

	/** Calls {@link Producer#compact()} on the {@link Producer} for the
	 * {@link Ray} and all {@link Intersection} {@link Producer}s.
	 */
	// TODO  Hardware acceleration
	@Override
	public void compact() {
		r.compact();
	}
}
