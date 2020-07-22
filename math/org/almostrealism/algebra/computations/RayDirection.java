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

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.geometry.Ray;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RayDirection extends DynamicAcceleratedProducerAdapter<Vector> implements VectorProducer {
	private String value[];

	public RayDirection(Producer<Ray> r) {
		super(3, Vector.blank(), r);
	}

	@Override
	public String getValue(int pos) {
		if (value == null) {
			String v = getArgumentName(1);
			return v + "[" + v + "Offset + " + (pos + 3) + "]";
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyDynamicAcceleratedAdapters()) {
			value = new String[3];

			DynamicAcceleratedProducerAdapter<Vector> r =
					(DynamicAcceleratedProducerAdapter<Vector>) inputProducers[1].getProducer();

			for (int i = 0; i < value.length; i++) {
				value[i] = r.getValue(i + 3);
			}

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);
			newArgs.addAll(Arrays.asList(r.getInputProducers()));
			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
