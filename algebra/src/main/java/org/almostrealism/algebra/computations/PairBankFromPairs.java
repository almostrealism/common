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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.PairBankProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.IntStream;

public class PairBankFromPairs extends DynamicProducerComputationAdapter<Pair, PairBank>
								implements PairBankProducer {
	private Expression<Double> value[];

	@SafeVarargs
	public PairBankFromPairs(Supplier<Evaluable<? extends Pair>>... input) {
		super(2 * input.length, () -> args -> new PairBank(input.length),
				i -> { throw new UnsupportedOperationException(); }, input);
	}

	private int arg(int index) { return 1 + index / 2; }
	private int pos(int index) { return index % 2; }

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				return getArgument(arg(pos), 2).valueAt(pos(pos));
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = IntStream.range(0, getMemLength())
					.mapToObj(i -> getInputValue(arg(i), pos(i)))
					.toArray(Expression[]::new);

			IntStream.range(1, getArgsCount())
					.filter(i -> !getInputProducer(i).isStatic())
					.forEach(i -> absorbVariables(getInputProducer(i)));
		}
	}
}