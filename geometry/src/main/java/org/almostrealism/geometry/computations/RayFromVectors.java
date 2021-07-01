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

package org.almostrealism.geometry.computations;

import io.almostrealism.code.expressions.Expression;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.RayBank;
import org.almostrealism.geometry.RayProducer;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

public class RayFromVectors extends DynamicProducerComputationAdapter<Vector, Ray> implements RayProducer {
	private Expression<Double> value[];
	
	public RayFromVectors(Supplier<Evaluable<? extends Vector>> origin, Supplier<Evaluable<? extends Vector>> direction) {
		super(6, Ray.blank(), RayBank::new, origin, direction);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return getArgument(1).valueAt(0);
				} else if (pos == 1) {
					return getArgument(1).valueAt(1);
				} else if (pos == 2) {
					return getArgument(1).valueAt(2);
				} else if (pos == 3) {
					return getArgument(2).valueAt(0);
				} else if (pos == 4) {
					return getArgument(2).valueAt(1);
				} else if (pos == 5) {
					return getArgument(2).valueAt(2);
				} else {
					throw new IllegalArgumentException("Position " + pos + " is not valid");
				}
			} else if (value[pos] == null) {
				throw new NullPointerException("Compaction produced a null value");
			} else {
				return value[pos];
			}
		};
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			absorbVariables(getInputProducer(1));
			absorbVariables(getInputProducer(2));

			value = new Expression[6];

			value[0] = getInputValue(1, 0);
			value[1] = getInputValue(1, 1);
			value[2] = getInputValue(1, 2);
			value[3] = getInputValue(2, 0);
			value[4] = getInputValue(2, 1);
			value[5] = getInputValue(2, 2);

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().trim().length() <= 0) {
					throw new IllegalArgumentException("Empty value for index " + i);
				} else if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}
		}
	}
}
