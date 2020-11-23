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

import io.almostrealism.code.Expression;
import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.relation.Producer;
import org.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class ScalarFromPair extends DynamicAcceleratedProducerAdapter<Pair, Scalar> implements ScalarProducer {
	public static final int X = 0;
	public static final int Y = 1;

	private int coordinate;

	private Expression<Double> value;
	private boolean isStatic;

	public ScalarFromPair(Supplier<Evaluable<? extends Pair>> pair, int coordinate) {
		super(2, Scalar.blank(), pair);
		this.coordinate = coordinate;
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return new Expression<>(Double.class, getArgumentValueName(1, coordinate), getArgument(1));
				} else if (pos == 1) {
					return new Expression<>(Double.class, stringForDouble(1.0));
				} else {
					throw new IllegalArgumentException(String.valueOf(pos));
				}
			} else {
				return pos == 0 ? value : new Expression<>(Double.class, stringForDouble(1.0));
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = getInputProducerValue(1, coordinate);
			if (value.getExpression().contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			// TODO  Probably should check if supplier itself is static...
			if (((Producer) getArguments().get(1).getProducer()).isStatic()) {
				isStatic = true;
			}

			absorbVariables(getInputProducer(1));
		}
	}

	@Override
	public boolean isStatic() { return !isVariableRef() && isStatic; }
}