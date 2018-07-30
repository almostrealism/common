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

package org.almostrealism.space;

import org.almostrealism.algebra.Intersectable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayDirection;
import org.almostrealism.geometry.RayPointAt;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

public class ShadableIntersectionProducer implements Producer<ShadableIntersection> {
	private Producer<Ray> ray;
	private Intersectable<ShadableIntersection, ?> surface;
	private Producer<Scalar> intersectionDistance;

	public ShadableIntersectionProducer(Producer<Ray> ray, Intersectable<ShadableIntersection, ?> surface, Producer<Scalar> intersectionDistance) {
		this.ray = ray;
		this.surface = surface;
		this.intersectionDistance = intersectionDistance;
	}

	@Override
	public ShadableIntersection evaluate(Object[] args) {
		// TODO  Pass intersectionDistance directly to ShadableIntersection
		//       but this will require that the normal vector there be a producer
		Scalar s = this.intersectionDistance.evaluate(args);
		if (s == null || s.getValue() < 0) return null;
		StaticProducer<Scalar> sp = new StaticProducer<>(s);
		return new ShadableIntersection(surface, new RayPointAt(ray, sp), new RayDirection(ray), s);
	}

	@Override
	public void compact() {
		// TODO  Hardware acceleration
		intersectionDistance.compact();
	}
}
