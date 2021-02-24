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
import io.almostrealism.code.expressions.Product;
import io.almostrealism.code.expressions.Sum;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PowerSpectrum extends DynamicProducerComputationAdapter<PairBank, ScalarBank> implements ScalarBankProducer {

	public PowerSpectrum(int count, Supplier<Evaluable<? extends PairBank>> input) {
		super(2 * (count / 2 + 1), () -> args -> new ScalarBank(count),
				i -> { throw new UnsupportedOperationException(); }, input);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> {
			if (i % 2 == 0) {
				if (i == 0) {
					return new Product(getArgument(1).get(0), getArgument(1).get(0));
				} else if (i == getMemLength() - 2) {
					return new Product(getArgument(1).get(1), getArgument(1).get(1));
				} else {
					return new Sum(
							new Product(getArgument(1).get(i), getArgument(1).get(i)),
							new Product(getArgument(1).get(i + 1), getArgument(1).get(i + 1)));
				}
			} else {
				return new Expression<>(Double.class, "1.0");
			}
		};
	}
}
