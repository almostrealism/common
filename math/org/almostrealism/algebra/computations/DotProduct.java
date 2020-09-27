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
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarBank;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.hardware.DynamicAcceleratedProducerAdapter;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public class DotProduct extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	private String value[];

	public DotProduct(Producer<Vector> a, Producer<Vector> b) {
		super(2, Scalar.blank(), a, b);
	}

	@Override
	public Function<Integer, String> getValueFunction() {
		return pos -> {
			if (value == null) {
				if (pos == 0) {
					return getArgumentValueName(1, 0) + " * " + getArgumentValueName(2, 0) + " + " +
							getArgumentValueName(1, 1) + " * " + getArgumentValueName(2, 1) + " + " +
							getArgumentValueName(1, 2) + " * " + getArgumentValueName(2, 2);
				} else if (pos == 1) {
					return stringForDouble(1.0);
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
			value = new String[2];
			value[0] = "(" + getInputProducerValue(1, 0) + ") * (" + getInputProducerValue(2, 0) + ") + " +
					"(" + getInputProducerValue(1, 1) + ") * (" + getInputProducerValue(2, 1) + ") + " +
					"(" + getInputProducerValue(1, 2) + ") * (" + getInputProducerValue(2, 2) + ")";
			value[1] = stringForDouble(1.0);

			if (value[0].contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			newArgs.addAll(Arrays.asList(excludeResult(getInputProducer(1).getInputProducers())));
			newArgs.addAll(Arrays.asList(excludeResult(getInputProducer(2).getInputProducers())));
			absorbVariables(getInputProducer(1));
			absorbVariables(getInputProducer(2));
			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}

		convertToVariableRef();
	}

	@Override
	public MemoryBank<Scalar> createKernelDestination(int size) { return new ScalarBank(size); }
}
