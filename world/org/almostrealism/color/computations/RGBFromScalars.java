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

package org.almostrealism.color.computations;

import org.almostrealism.algebra.Scalar;
import org.almostrealism.color.RGB;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RGBFromScalars extends DynamicAcceleratedProducerAdapter<RGB> implements RGBProducer {
	private String value[];

	public RGBFromScalars(Producer<Scalar> r, Producer<Scalar> g, Producer<Scalar> b) {
		super(3, RGB.blank(), r, g, b);
	}

	@Override
	public String getValue(Argument arg, int pos) {
		if (value == null) {
			return getArgumentValueName(pos + 1, 0);
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			value = new String[] {
					getInputProducerValue(1, 0),
					getInputProducerValue(2, 0),
					getInputProducerValue(3, 0)
			};

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			for (int i = 1; i <= 3; i++) {
				if (!getInputProducer(i).isStatic())
					newArgs.addAll(Arrays.asList(excludeResult(getInputProducer(i).getInputProducers())));
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}