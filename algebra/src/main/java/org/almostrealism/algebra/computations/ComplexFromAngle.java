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
import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairBank;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ComplexFromAngle extends DynamicProducerComputationAdapter<Scalar, Pair> implements PairProducer {

	public ComplexFromAngle(Supplier<Evaluable<? extends Scalar>> angle) {
		super(2, Pair.empty(), PairBank::new, angle);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return i -> new Expression<>(Double.class, (i == 0 ? "cos(" : "sin(") +
											getArgument(1).get(0).getExpression() + ")",
											getArgument(1));
	}
}
