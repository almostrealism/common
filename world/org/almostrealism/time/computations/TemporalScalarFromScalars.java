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

package org.almostrealism.time.computations;

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import io.almostrealism.relation.Evaluable;
import org.almostrealism.time.TemporalScalar;
import org.almostrealism.time.TemporalScalarProducer;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class TemporalScalarFromScalars extends DynamicAcceleratedProducerAdapter<Scalar, TemporalScalar> implements TemporalScalarProducer {
	private Expression<Double> value[];

	public TemporalScalarFromScalars(Supplier<Evaluable<? extends Scalar>> time, Supplier<Evaluable<? extends Scalar>> value) {
		super(2, TemporalScalar.blank(), time, value);
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
					getInputValue(2, 0)
			};

			for (int i = 1; i <= 2; i++) {
				if (!getInputProducer(i).isStatic()) {
					absorbVariables(getInputProducer(i));
				}
			}
		}
	}
}
