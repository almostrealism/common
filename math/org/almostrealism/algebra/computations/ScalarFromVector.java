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
import java.util.List;

public class ScalarFromVector extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	public static final int X = 0;
	public static final int Y = 1;
	public static final int Z = 2;

	private Producer<Vector> vector;
	private int coordinate;

	private String value;

	public ScalarFromVector(Producer<Vector> vector, int coordinate) {
		super(2, Scalar.blank(), vector);
		this.vector = vector;
		this.coordinate = coordinate;
	}

	@Override
	public String getValue(Argument arg, int pos) {
		if (value == null) {
			if (pos == 0) {
				return getArgumentValueName(1, coordinate);
			} else if (pos == 1) {
				return "1.0";
			} else {
				throw new IllegalArgumentException(String.valueOf(pos));
			}
		} else {
			return value;
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getInputProducers()[0]);

			value = getInputProducerValue(1, coordinate);
			if (value.contains("Infinity")) {
				throw new IllegalArgumentException("Infinity is not supported");
			}

			for (int i = 1; i < getInputProducer(1).getInputProducers().length; i++) {
				newArgs.add(getInputProducer(1).getInputProducers()[i]);
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
