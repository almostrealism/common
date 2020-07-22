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

import org.almostrealism.algebra.Vector;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RayProducer extends DynamicAcceleratedProducerAdapter<Ray> {
	private String value[];

	public RayProducer(Producer<Vector> origin, Producer<Vector> direction) {
		super(6, Ray.blank(), origin, direction);
	}

	@Override
	public String getValue(int pos) {
		if (value == null) {
			if (pos == 0) {
				return getArgumentName(1) + "[" + getArgumentName(1) + "Offset]";
			} else if (pos == 1) {
				return getArgumentName(1) + "[" + getArgumentName(1) + "Offset + 1]";
			} else if (pos == 2) {
				return getArgumentName(1) + "[" + getArgumentName(1) + "Offset + 2]";
			} else if (pos == 3) {
				return getArgumentName(2) + "[" + getArgumentName(1) + "Offset]";
			} else if (pos == 4) {
				return getArgumentName(2) + "[" + getArgumentName(1) + "Offset + 1]";
			} else if (pos == 5) {
				return getArgumentName(2) + "[" + getArgumentName(1) + "Offset + 2]";
			} else {
				throw new IllegalArgumentException("Position " + pos + " is not valid");
			}
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyDynamicAcceleratedAdapters()) {
			DynamicAcceleratedProducerAdapter<Vector> origin =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[1].getProducer();
			DynamicAcceleratedProducerAdapter<Vector> direction =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[2].getProducer();

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			newArgs.addAll(Arrays.asList(origin.getInputProducers()));
			newArgs.addAll(Arrays.asList(direction.getInputProducers()));

			value = new String[6];

			value[0] = origin.getValue(0);
			value[1] = origin.getValue(1);
			value[2] = origin.getValue(2);
			value[3] = direction.getValue(0);
			value[4] = direction.getValue(1);
			value[5] = direction.getValue(2);

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
