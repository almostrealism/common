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
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class PairFromPairBank extends DynamicProducerComputationAdapter<PairBank, Pair> implements PairProducer {
	public PairFromPairBank(Supplier<Evaluable<? extends PairBank>> bank, Supplier<Evaluable<? extends Scalar>> index) {
		super(2, Pair.empty(), PairBank::new, bank, (Supplier) index);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos == 0) {
				if (getArgument(2).isStatic()) {
					return getArgument(1).get("2 * " + getInputValue(2, 0).getExpression());
				} else {
					return getArgument(1).get("2 * " + getInputValue(2, 0).getExpression(), getArgument(2));
				}
			} else if (pos == 1) {
				if (getArgument(2).isStatic()) {
					return getArgument(1).get("2 * " + getInputValue(2, 0).getExpression() + " + 1");
				} else {
					return getArgument(1).get("2 * " + getInputValue(2, 0).getExpression() + " + 1", getArgument(2));
				}
			} else {
				throw new IllegalArgumentException();
			}
		};
	}
}
