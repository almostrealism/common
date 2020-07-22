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

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.PairProducer;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.Vector;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class PairFromScalars extends DynamicAcceleratedProducerAdapter<Pair> implements PairProducer {
	private String value[];

	public PairFromScalars(Producer<Scalar> x, Producer<Scalar> y) {
		super(2, Vector.blank(), x, y);
	}

	@Override
	public String getValue(int pos) {
		String v = getFunctionName() + "_v";

		if (value == null) {
			return v + (pos + 1) + "[" + v + (pos + 1) + "Offset]";
		} else {
			return value[pos];
		}
	}

	public void compact() {
		super.compact();

		if (value == null && isCompletelyDynamicAcceleratedAdapters()) {
			DynamicAcceleratedProducerAdapter xy[] = new DynamicAcceleratedProducerAdapter[] {
					((DynamicAcceleratedProducerAdapter) getInputProducers()[1].getProducer()),
					((DynamicAcceleratedProducerAdapter) getInputProducers()[2].getProducer())
			};

			value = new String[] {
					xy[0].getValue(0),
					xy[1].getValue(0)
			};

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);

			for (int i = 1; i < xy[0].getInputProducers().length; i++) {
				newArgs.add(xy[0].getInputProducers()[i]);
			}

			for (int i = 1; i < xy[1].getInputProducers().length; i++) {
				newArgs.add(xy[1].getInputProducers()[i]);
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
