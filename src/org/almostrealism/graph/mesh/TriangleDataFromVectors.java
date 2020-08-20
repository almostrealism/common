/*
 * Copyright 2020 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Vector;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class TriangleDataFromVectors extends DynamicAcceleratedProducerAdapter<TriangleData> implements TriangleDataProducer {
	private String value[];

	public TriangleDataFromVectors(Producer<Vector> abc, Producer<Vector> def, Producer<Vector> jkl) {
		super(9, TriangleData.blank(), abc, def, jkl);
	}

	@Override
	public String getValue(int pos) {
		if (value == null) {
			return getArgumentValueName((pos / 3) + 1, pos % 3);
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			DynamicAcceleratedProducerAdapter vectors[] = new DynamicAcceleratedProducerAdapter[] {
					((DynamicAcceleratedProducerAdapter) getInputProducers()[1].getProducer()),
					((DynamicAcceleratedProducerAdapter) getInputProducers()[2].getProducer()),
					((DynamicAcceleratedProducerAdapter) getInputProducers()[3].getProducer())
			};

			value = new String[] {
					vectors[0].getValue(0),
					vectors[0].getValue(1),
					vectors[0].getValue(2),
					vectors[1].getValue(0),
					vectors[1].getValue(1),
					vectors[1].getValue(2),
					vectors[2].getValue(0),
					vectors[2].getValue(1),
					vectors[2].getValue(2)
			};

			for (int i = 0; i < value.length; i++) {
				if (value[i].contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);

			if (!vectors[0].isStatic()) {
				for (int i = 1; i < vectors[0].getInputProducers().length; i++) {
					newArgs.add(vectors[0].getInputProducers()[i]);
				}
			}

			if (!vectors[1].isStatic()) {
				for (int i = 1; i < vectors[1].getInputProducers().length; i++) {
					newArgs.add(vectors[1].getInputProducers()[i]);
				}
			}


			if (!vectors[0].isStatic()) {
				for (int i = 1; i < vectors[2].getInputProducers().length; i++) {
					newArgs.add(vectors[2].getInputProducers()[i]);
				}
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
