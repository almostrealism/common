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

import io.almostrealism.code.Argument;
import io.almostrealism.code.Expression;
import org.almostrealism.algebra.Vector;
import org.almostrealism.geometry.Ray;
import org.almostrealism.hardware.AcceleratedProducer;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

public class RayOrigin extends DynamicAcceleratedProducerAdapter<Vector> {
	private Expression<Double> value[];
	private boolean isStatic;

	public RayOrigin(Producer<Ray> r) {
		super(3, Vector.blank(), r);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				return new Expression(Double.class, getArgumentValueName(1, pos), getArgument(1));
			} else {
				return value[pos];
			}
		};
	}

	/**
	 * Returns true if the {@link Ray} {@link Producer} is static.
	 */
	@Override
	public boolean isStatic() { return !isVariableRef() && isStatic; }

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new Expression[3];

			for (int i = 0; i < value.length; i++) {
				value[i] = getInputProducerValue(1, i);
				if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			isStatic = getInputProducer(1).isStatic();

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getArguments().get(0));
			newArgs.addAll(AcceleratedProducer.excludeResult(getInputProducer(1).getArguments()));
			absorbVariables(getInputProducer(1));
			// setArguments(newArgs);
			removeDuplicateArguments();
		}
	}
}
