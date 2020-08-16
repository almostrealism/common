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

import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DotProduct extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	private String value[];

	public DotProduct(Producer<Vector> a, Producer<Vector> b) {
		super(2, Scalar.blank(), a, b);
	}

	@Override
	public String getValue(int pos) {
		if (value == null) {
			if (pos == 0) {
				return getArgumentValueName(1, 0) + " * " + getArgumentValueName(2, 0) + " + " +
						getArgumentValueName(1, 1) + " * " + getArgumentValueName(2, 1) + " + " +
						getArgumentValueName(1, 2) + " * " + getArgumentValueName(2, 2);
			} else if (pos == 1) {
				return "1.0";
			} else {
				throw new IllegalArgumentException("Position " + pos + " is invalid");
			}
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			DynamicAcceleratedProducerAdapter<Vector> a =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[1].getProducer();
			DynamicAcceleratedProducerAdapter<Vector> b =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[2].getProducer();

			value = new String[2];
			value[0] = "(" + a.getValue(0) + ") * (" + b.getValue(0) + ") + " +
					"(" + a.getValue(1) + ") * (" + b.getValue(1) + ") + " +
					"(" + a.getValue(2) + ") * (" + b.getValue(2) + ")";
			value[1] = "1.0";

			if (value[0].contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			newArgs.addAll(Arrays.asList(excludeResult(a.getInputProducers())));
			newArgs.addAll(Arrays.asList(excludeResult(b.getInputProducers())));
			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
