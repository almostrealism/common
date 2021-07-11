/*
 * Copyright 2021 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.algebra.computations;

import io.almostrealism.code.Computation;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.algebra.VectorEvaluable;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryBankAdapter.CacheLevel;

public class DefaultVectorEvaluable extends AcceleratedComputationEvaluable<Vector> implements VectorEvaluable {

	public DefaultVectorEvaluable(Computation<Vector> c) {
		super(c);
	}

	@Override
	protected Vector postProcessOutput(MemoryData output, int offset) {
		return new Vector(output, offset);
	}

	@Override
	public MemoryBank<Vector> createKernelDestination(int size) {
		return new VectorBank(size, CacheLevel.ACCESSED);
	}
}
