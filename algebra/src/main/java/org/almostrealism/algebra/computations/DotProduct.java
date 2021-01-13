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

package org.almostrealism.algebra.computations;

import io.almostrealism.code.expressions.Expression;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.ComputerFeatures;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class DotProduct extends DynamicAcceleratedProducerAdapter<Vector, Scalar> implements ScalarProducer, ComputerFeatures {
	private Expression<Double> value[];

	public DotProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		super(2, Scalar.blank(), ScalarBank::new, a, b);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return getArgument(1).get(0).multiply(getArgument(2).get(0)).add(
							getArgument(1).get(1).multiply(getArgument(2).get(1))).add(
							getArgument(1).get(2).multiply(getArgument(2).get(2)));
				} else if (pos == 1) {
					return new Expression(Double.class, stringForDouble(1.0));
				} else {
					throw new IllegalArgumentException("Position " + pos + " is invalid");
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
			value = new Expression[2];
			value[0] = new Expression<>(Double.class, "(" + getInputValue(1, 0).getExpression() + ") * (" + getInputValue(2, 0).getExpression() + ") + " +
					"(" + getInputValue(1, 1).getExpression() + ") * (" + getInputValue(2, 1).getExpression() + ") + " +
					"(" + getInputValue(1, 2).getExpression() + ") * (" + getInputValue(2, 2).getExpression() + ")",
					getInputValue(1, 0), getInputValue(1, 1), getInputValue(1, 2),
					getInputValue(2, 0), getInputValue(2, 1), getInputValue(2, 2));

			value[1] = new Expression<>(Double.class, stringForDouble(1.0));

			if (value[0].getExpression().contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			absorbVariables(getInputs().get(1));
			absorbVariables(getInputs().get(2));
		}

		convertToVariableRef();
	}
}
