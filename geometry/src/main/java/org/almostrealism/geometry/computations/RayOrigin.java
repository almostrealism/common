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
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.DynamicProducerComputationAdapter;
import io.almostrealism.relation.Evaluable;

import java.util.function.IntFunction;
import java.util.function.Supplier;

 public class RayOrigin extends DynamicProducerComputationAdapter<Ray, Vector> implements VectorProducer {
	private Expression<Double> value[];
	private boolean isStatic;

	public RayOrigin(Supplier<Evaluable<? extends Ray>> r) {
		super(3, Vector.blank(), VectorBank::new, r);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				return getArgument(1).get(pos);
			} else {
				return value[pos];
			}
		};
	}

	/**
	 * Returns true if the {@link Ray} {@link Evaluable} is static.
	 */
	@Override
	public boolean isStatic() { return !isVariableRef() && isStatic; }

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[3];

			for (int i = 0; i < value.length; i++) {
				value[i] = getInputValue(1, i);
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			isStatic = getInputProducer(1).isStatic();

			absorbVariables(getInputProducer(1));
		}
	}
}