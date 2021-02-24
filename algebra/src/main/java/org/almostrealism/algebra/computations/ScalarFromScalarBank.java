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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarFromScalarBank<T extends ScalarBank> extends DynamicProducerComputationAdapter<T, Scalar> implements ScalarProducer {
	public ScalarFromScalarBank(Supplier<Evaluable<? extends T>> bank, Supplier<Evaluable<? extends T>> index) {
		super(2, Scalar.blank(), ScalarBank::new, bank, index);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (pos == 0) {
				return getArgument(1).get("(2 * (int) " + getArgument(2).get(0).getExpression() + ")", getArgument(2));
			} else if (pos == 1) {
				return new Expression<>(Double.class, "1.0");
			} else {
				throw new IllegalArgumentException();
			}
		};
	}
}
