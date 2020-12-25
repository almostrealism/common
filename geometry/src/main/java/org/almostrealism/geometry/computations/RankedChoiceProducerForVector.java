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

package org.almostrealism.geometry.computations;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.hardware.MemoryBank;

public class RankedChoiceProducerForVector extends RankedChoiceProducerForMemWrapper<Vector> {
	public RankedChoiceProducerForVector(double e) {
		super(e);
	}

	public RankedChoiceProducerForVector(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	public AcceleratedRankedChoiceEvaluable<Vector> getAccelerated() {
		return getAccelerated(3, Vector::new, VectorBank::new);
	}

	@Override
	public MemoryBank<Vector> createKernelDestination(int size) { return new VectorBank(size); }
}
