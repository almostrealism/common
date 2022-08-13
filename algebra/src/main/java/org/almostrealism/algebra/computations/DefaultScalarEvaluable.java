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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarEvaluable;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import io.almostrealism.code.Computation;

public class DefaultScalarEvaluable extends AcceleratedComputationEvaluable<Scalar> implements ScalarEvaluable {

	public DefaultScalarEvaluable(Computation<Scalar> c) {
		super(c);
	}

	@Override
	protected Scalar postProcessOutput(MemoryData output, int offset) {
		return new Scalar(output, offset);
	}

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) {
		return new ScalarBank(size);
	}
}
