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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.ComputerFeatures;
import org.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class DotProduct extends DynamicAcceleratedProducerAdapter<Vector, Scalar> implements ScalarProducer, ComputerFeatures {
	private Expression<Double> value[];

	public DotProduct(Supplier<Evaluable<? extends Vector>> a, Supplier<Evaluable<? extends Vector>> b) {
		super(2, Scalar.blank(), a, b);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return new Expression(Double.class, getArgumentValueName(1, 0) + " * " + getArgumentValueName(2, 0) + " + " +
							getArgumentValueName(1, 1) + " * " + getArgumentValueName(2, 1) + " + " +
							getArgumentValueName(1, 2) + " * " + getArgumentValueName(2, 2),
							getArgument(1), getArgument(2));
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
			value[0] = new Expression<>(Double.class, "(" + getInputProducerValue(1, 0).getExpression() + ") * (" + getInputProducerValue(2, 0).getExpression() + ") + " +
					"(" + getInputProducerValue(1, 1).getExpression() + ") * (" + getInputProducerValue(2, 1).getExpression() + ") + " +
					"(" + getInputProducerValue(1, 2).getExpression() + ") * (" + getInputProducerValue(2, 2).getExpression() + ")",
					getInputProducerValue(1, 0), getInputProducerValue(1, 1), getInputProducerValue(1, 2),
					getInputProducerValue(2, 0), getInputProducerValue(2, 1), getInputProducerValue(2, 2));
			value[1] = new Expression<>(Double.class, stringForDouble(1.0));

			if (value[0].getExpression().contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			absorbVariables(getInputProducer(1));
			absorbVariables(getInputProducer(2));
		}

		convertToVariableRef();
	}
}
