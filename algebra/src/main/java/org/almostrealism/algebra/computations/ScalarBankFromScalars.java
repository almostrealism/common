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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarBankProducer;
import org.almostrealism.algebra.ScalarTable;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarBankFromScalars extends DynamicProducerComputationAdapter<Scalar, ScalarBank> implements ScalarBankProducer {
	private Expression<Double> value[];

	public ScalarBankFromScalars(Supplier<Evaluable<? extends Scalar>>... values) {
		super(2 * values.length,
				() -> args -> new ScalarBank(values.length),
				l -> new ScalarTable(values.length, l), values);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				int p = pos / 2;
				int v = pos % 2;
				return getArgument(p + 1, 2).valueAt(v);
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[getMemLength()];

			for (int i = 0; i < value.length; i++) {
				value[i] = getInputValue((i / 2) + 1, i % 2);

				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			for (int i = 1; i < getInputs().size(); i++) {
				absorbVariables(getInputs().get(i));
			}
		}
	}
}
