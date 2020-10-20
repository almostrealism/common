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

package org.almostrealism.geometry;

import io.almostrealism.code.Argument;
import io.almostrealism.code.Expression;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

public class RayFromVectors extends DynamicAcceleratedProducerAdapter<Ray> implements RayProducer {
	private Expression<Double> value[];

	public RayFromVectors(Producer<Vector> origin, Producer<Vector> direction) {
		super(6, Ray.blank(), origin, direction);
	}

	@Override
	public IntFunction<Expression<Double>> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return new Expression<>(getArgumentValueName(1, 0));
				} else if (pos == 1) {
					return new Expression<>(getArgumentValueName(1, 1));
				} else if (pos == 2) {
					return new Expression<>(getArgumentValueName(1, 2));
				} else if (pos == 3) {
					return new Expression<>(getArgumentValueName(2, 0));
				} else if (pos == 4) {
					return new Expression<>(getArgumentValueName(2, 1));
				} else if (pos == 5) {
					return new Expression<>(getArgumentValueName(2, 2));
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
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			newArgs.addAll(Arrays.asList(excludeResult(getInputProducer(1).getInputProducers())));
			newArgs.addAll(Arrays.asList(excludeResult(getInputProducer(2).getInputProducers())));
			absorbVariables(getInputProducer(1));
			absorbVariables(getInputProducer(2));

			value = new Expression[6];

			value[0] = getInputProducerValue(1, 0);
			value[1] = getInputProducerValue(1, 1);
			value[2] = getInputProducerValue(1, 2);
			value[3] = getInputProducerValue(2, 0);
			value[4] = getInputProducerValue(2, 1);
			value[5] = getInputProducerValue(2, 2);

			for (int i = 0; i < value.length; i++) {
				if (value[i].getExpression().trim().length() <= 0) {
					throw new IllegalArgumentException("Empty value for index " + i);
				} else if (value[i].getExpression().contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}

	@Override
	public MemoryBank<Ray> createKernelDestination(int size) { return new RayBank(size); }
}
