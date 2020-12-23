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

package org.almostrealism.color.computations;

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class RGBFromScalars extends DynamicAcceleratedProducerAdapter<Scalar, RGB> implements RGBProducer {
	private Expression<Double> value[];

	public RGBFromScalars(Supplier<Evaluable<? extends Scalar>> r, Supplier<Evaluable<? extends Scalar>> g, Supplier<Evaluable<? extends Scalar>> b) {
		super(3, RGB.blank(), r, g, b);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				return getArgument(pos + 1).get(0);
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[] {
					getInputValue(1, 0),
					getInputValue(2, 0),
					getInputValue(3, 0)
			};

			for (int i = 1; i <= 3; i++) {
				absorbVariables(getInputProducer(i));
			}
		}

		convertToVariableRef();
	}
}
