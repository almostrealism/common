/*
 * Copyright 2021 Michael Murray
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

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.PairBank;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.PairBankProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PairBankInterleave extends DynamicProducerComputationAdapter<PairBank, PairBank> implements PairBankProducer {
	public PairBankInterleave(int count, Supplier<Evaluable<? extends PairBank>> bankA, Supplier<Evaluable<? extends PairBank>> bankB) {
		super(2 * count, () -> args -> new PairBank(2 * count),
						i -> { throw new UnsupportedOperationException(); },
						bankA, bankB);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			int loc = pos / 4;
			pos = pos % 4;

			if (pos == 0) {
				return getArgument(1).valueAt(2 * loc);
			} else if (pos == 1) {
				return getArgument(1).valueAt(2 * loc + 1);
			} else if (pos == 2) {
				return getArgument(2).valueAt(2 * loc);
			} else if (pos == 3) {
				return getArgument(2).valueAt(2 * loc + 1);
			} else {
				throw new IllegalArgumentException();
			}
		};
	}
}
