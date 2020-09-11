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

package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.util.Producer;

public class RayPointAt extends VectorSum {
	public RayPointAt(Producer<Ray> r, Producer<Scalar> t) {
		super(new RayOrigin(r), new VectorProduct(new RayDirection(r), new VectorFromScalars(t, t, t)));
	}

	/**
	 * This implementation allows the {@link Scalar} argument to be null,
	 * resulting in the function returning null.
	 */
	@Override
	public Vector handleNull(int argIndex) {
		if (argIndex == 2) {
			return null;
		}

		return super.handleNull(argIndex);
	}
}
