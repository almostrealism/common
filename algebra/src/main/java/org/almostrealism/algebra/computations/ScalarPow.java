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

import io.almostrealism.expression.Exponent;
import io.almostrealism.expression.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarPow extends DynamicProducerComputationAdapter<Scalar, Scalar> implements ScalarProducer {
	private Expression<Double> value[];

	public ScalarPow(Supplier<Evaluable<? extends Scalar>> base, Supplier<Evaluable<? extends Scalar>> exponent) {
		super(2, Scalar.blank(), ScalarBank::new, base, exponent);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return new Exponent(getArgument(1, 2).valueAt(0), getArgument(2, 2).valueAt(0));
				} else if (pos == 1) {
					// TODO  Certainty of exponent is ignored
					return new Exponent(getArgument(1, 2).valueAt(1), getArgument(2, 2).valueAt(0));
				} else {
					throw new IllegalArgumentException(String.valueOf(pos));
				}
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			absorbVariables(getInputs().get(1));
			absorbVariables(getInputs().get(2));

			// TODO  Certainty of exponent is ignored
			value = new Expression[] {
					new Expression<>(Double.class, "pow(" + getInputValue(1, 0).getExpression() +
							", " + getInputValue(2, 0).getExpression() + ")",
							getInputValue(1, 0), getInputValue(2, 0)),
					new Expression<>(Double.class, "pow(" + getInputValue(1, 1).getExpression() +
							", " + getInputValue(2, 0).getExpression() + ")",
							getInputValue(1, 1), getInputValue(2, 0))
			};

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}
		}

		convertToVariableRef();
	}
}
