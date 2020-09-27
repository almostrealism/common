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
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Producer;

public class ScalarSum extends NAryDynamicAcceleratedProducer<Scalar> implements ScalarProducer {
	public ScalarSum(Producer<Scalar>... producers) {
		super("+", 2, Scalar.blank(), producers);
	}

	@Override
	public double getIdentity() { return 0; }

	@Override
	public double combine(double a, double b) { return a + b; }

	/**
	 * Returns true if the specified value is 0.0, false otherwise.
	 */
	public boolean isRemove(double value) { return value == 0.0; }

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) { return new ScalarBank(size); }
}
