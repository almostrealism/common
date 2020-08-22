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
		if (value == null || value[pos] == null) {
			return getArgumentValueName((pos / 3) + 1, pos % 3);
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null) {
			value = new String[9];

			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(inputProducers[0]);

			i: for (int i = 1; i < inputProducers.length; i++) {
				// Ignore those that are not of a compactable kind
				if (inputProducers[i].getProducer() instanceof
						DynamicAcceleratedProducerAdapter == false) {
					newArgs.add(inputProducers[i]);
					continue i;
				}

				DynamicAcceleratedProducerAdapter producer =
						(DynamicAcceleratedProducerAdapter)
						inputProducers[i].getProducer();

				// Ignore those that are more than just a value returned from getValue
				if (!producer.isValueOnly()) {
					newArgs.add(inputProducers[i]);
					continue i;
				}

				// If it is just a value, include it in the compacted version
				int index = 3 * (i - 1);
				value[index] = producer.getValue(0);
				value[index + 1] = producer.getValue(1);
				value[index + 2] = producer.getValue(2);

				// If it is not a static value, it depends on others, so bring
				// those into the arguments for the compacted version
				if (!producer.isStatic()) {
					for (int j = 1; j < producer.getInputProducers().length; j++) {
						newArgs.add(producer.getInputProducers()[j]);
					}
				}
			}

			// Check for illegal values
			for (int i = 0; i < value.length; i++) {
				if (value[i] != null && value[i].contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			// Replace original parameters
			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
