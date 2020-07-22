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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.Producer;

public class VectorProduct extends NAryDynamicAcceleratedProducer<Vector> implements VectorProducer {
	public VectorProduct(Producer<Vector>... producers) {
		super("*", 3, Vector.blank(), producers);
	}

	@Override
	public double getIdentity() { return 1.0; }

	@Override
	public double combine(double a, double b) { return a * b; }

	/**
	 * Returns 0.0 if the specified value is zero, false otherwise.
	 */
	public Double isReplaceAll(double value) { return value == 0.0 ? 0.0 : null; }

	/**
	 * Returns true if the specified value is 1.0, false otherwise.
	 */
	public boolean isRemove(double value) { return value == 1.0; }
}
