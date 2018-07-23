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

public class RayMatrixTransform implements Producer<Ray> {
	private TransformMatrix t;
	private Producer<Ray> r;

	public RayMatrixTransform(TransformMatrix t, Producer<Ray> r) {
		this.t = t;
		this.r = r;
	}

	@Override
	public Ray evaluate(Object[] args) {
		Ray ray = r.evaluate(args);
		ray.setOrigin(t.transformAsLocation(ray.getOrigin()));
		ray.setDirection(t.transformAsOffset(ray.getDirection()));
		return ray;
	}

	@Override
	public void compact() {
		// TODO  Hardware acceleration
		r.compact();
	}
}
