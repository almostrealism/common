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

import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.PairBankEvaluable;
import org.almostrealism.hardware.AcceleratedComputationEvaluable;
import org.almostrealism.hardware.MemoryBank;
import io.almostrealism.code.Computation;
import org.almostrealism.hardware.MemoryData;

@Deprecated
public class DefaultPairBankEvaluable extends AcceleratedComputationEvaluable<PairBank> implements PairBankEvaluable {

	public DefaultPairBankEvaluable(Computation<PairBank> c) {
		super(c);
	}

	@Override
	protected PairBank postProcessOutput(MemoryData output, int offset) {
		return new PairBank(output.getMemLength() / 2, output, offset, null);
	}

	@Override
	public MemoryBank<PairBank> createKernelDestination(int size) {
		throw new UnsupportedOperationException(); // TODO  PairBank..Bank? Tensor?
	}
}
